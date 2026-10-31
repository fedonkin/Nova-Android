package com.example.nova

import android.os.SystemClock
import java.util.Locale

/**
 * Разбивка времени подключения по этапам — одной строкой в журнал.
 *
 * Зачем. Дальнейшие правки скорости упираются не в код, а в отсутствие чисел:
 * в коннект-пути стоят осознанные отсечки (рукопожатие не старше 1200 мс перед
 * первой сквозной пробой, задержка ранней MASQUE-пробы 2–3,2 с, бюджеты проб,
 * тик наблюдения), и каждая из них стоит там из-за ложных срабатываний. Урезать
 * их «на глаз» — значит менять «подключается за 4 с» на «иногда за 15». Пока
 * этапы не размечены, спор о том, где именно уходят секунды, идёт по памяти.
 *
 * Что даёт. Ровно одну строку на подключение:
 * ```
 * Подключение (warp, подключено): всего 4310 мс; →транспорт 180, →интерфейс 240,
 * →ядро 420, →движок 470, →рукопожатие 1680, →проба 3540
 * ```
 * По ней видно, съедено ли время до старта ядра (наша сторона), рукопожатием
 * (путь до узла) или ожиданием пробы (наши отсечки). Срыв печатается так же —
 * с итогом «не подключено» / «остановлено»: именно его и нужно раскладывать.
 *
 * Чего не делает. Не хранит историю, не пишет на диск и не влияет на решения:
 * это наблюдатель. Поколение подключения не проверяет по той же причине —
 * метки принадлежат тому циклу, который их поставил, а `begin()` начинает новый.
 *
 * Стоимость. Четыре-пять записей `elapsedRealtime` за подключение и одна строка
 * в журнал. В кадре анимации и в горячем пути не участвует.
 */
object ConnectTimeline {

    /** Метка: имя этапа и время в `elapsedRealtime`, когда он наступил. */
    private data class Mark(val name: String, val atMs: Long)

    /**
     * Источник времени. Подменяется в тестах: `SystemClock` в JVM-тестах нет.
     * В приложении всегда `elapsedRealtime` — он не прыгает от смены часов.
     */
    internal var timeSource: () -> Long = { SystemClock.elapsedRealtime() }

    /** Куда уходит строка. Подменяется в тестах: `LogManager` там не поднять. */
    internal var logSink: (String) -> Unit = { LogManager.log(it) }

    private val lock = Any()
    private var startedAtMs = 0L
    private var label = ""
    private var marks = ArrayList<Mark>(6)

    /** Начинает новый замер. Прошлый незавершённый молча забывается. */
    fun begin(label: String) {
        synchronized(lock) {
            startedAtMs = timeSource()
            this.label = label
            marks = ArrayList(6)
        }
    }

    /** Отмечает этап. Повторная метка с тем же именем заменяет прежнюю. */
    fun mark(name: String) {
        synchronized(lock) {
            if (startedAtMs == 0L) return
            val at = timeSource() - startedAtMs
            val existing = marks.indexOfFirst { it.name == name }
            if (existing >= 0) {
                marks[existing] = Mark(name, at)
            } else {
                marks.add(Mark(name, at))
            }
        }
    }

    /**
     * Сколько идёт текущий замер, или `null`, если он не начат.
     *
     * Нужен тем, кто показывает ход подключения на экране: у них нет своего
     * начала отсчёта, а замер уже знает его точнее (он стартует раньше, чем
     * экран получает первую метку).
     */
    fun elapsedMsOrNull(): Long? = synchronized(lock) {
        if (startedAtMs == 0L) null else (timeSource() - startedAtMs).coerceAtLeast(0L)
    }

    /** Идёт ли замер. Нужен тем, кто печатает итог только при незакрытом замере. */
    fun hasPending(): Boolean = synchronized(lock) { startedAtMs != 0L }

    /**
     * Завершает замер и печатает строку.
     *
     * @param outcome чем кончилось — попадает в строку, чтобы «подключение за
     *        4 с» не путалось с «остановкой через 4 с».
     * @return строка, которая ушла в журнал; пустая, если замер не начинали.
     */
    fun finish(outcome: String): String {
        val message = synchronized(lock) {
            if (startedAtMs == 0L) return ""
            val total = timeSource() - startedAtMs
            val parts = marks.joinToString(", ") { "→${it.name} ${it.atMs}" }
            val where = label.ifBlank { "connect" }
            val text = if (parts.isEmpty()) {
                String.format(Locale.US, "Подключение (%s, %s): всего %d мс", where, outcome, total)
            } else {
                String.format(
                    Locale.US,
                    "Подключение (%s, %s): всего %d мс; %s",
                    where,
                    outcome,
                    total,
                    parts,
                )
            }
            startedAtMs = 0L
            label = ""
            marks = ArrayList(6)
            text
        }
        if (message.isNotEmpty()) logSink(message)
        return message
    }

    /** Забывает замер, ничего не печатая: подключение отменили. */
    fun discard() {
        synchronized(lock) {
            startedAtMs = 0L
            label = ""
            marks = ArrayList(6)
        }
    }
}
