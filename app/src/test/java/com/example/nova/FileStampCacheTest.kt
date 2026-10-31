package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Кэш по отметке файла: когда он вправе не читать и когда обязан.
 *
 * Ошибка здесь тихая — экран показал бы позавчерашнее состояние службы и никак бы
 * об этом не сказал, поэтому проверяются не только попадания, но и все случаи, в
 * которых кэш обязан промолчать: файла нет, он подменён во время чтения, чтение
 * упало, была своя запись.
 */
class FileStampCacheTest {

    private class FakeFile(var stampValue: FileStamp? = FileStamp(10L, 1_000L)) {
        var reads = 0
        var payload: String? = null
        var failOnRead: Throwable? = null
        /** Что сделать с файлом ровно в момент чтения (подмена соседним процессом). */
        var onRead: (() -> Unit)? = null

        val cache = FileStampCache(
            read = {
                reads++
                onRead?.invoke()
                failOnRead?.let { throw it }
                payload
            },
            stamp = { stampValue },
        )
    }

    @Test
    fun `при той же отметке файл больше не читается`() {
        val file = FakeFile()
        file.payload = "состояние"

        assertEquals("состояние", file.cache.get())
        assertEquals("состояние", file.cache.get())
        assertEquals("состояние", file.cache.get())
        assertEquals(1, file.reads)
    }

    @Test
    fun `новая отметка перечитывает файл`() {
        val file = FakeFile()
        file.payload = "CONNECTING"
        assertEquals("CONNECTING", file.cache.get())

        file.payload = "CONNECTED"
        file.stampValue = FileStamp(11L, 2_000L)

        assertEquals("CONNECTED", file.cache.get())
        assertEquals(2, file.reads)
    }

    @Test
    fun `одна длина при новом времени изменения — уже другой файл`() {
        val file = FakeFile()
        file.payload = "a"
        assertEquals("a", file.cache.get())

        file.payload = "b"
        file.stampValue = FileStamp(10L, 1_500L)

        assertEquals("b", file.cache.get())
        assertEquals(2, file.reads)
    }

    @Test
    fun `запомненное живёт не дольше срока`() {
        var clockMs = 0L
        var reads = 0
        val stamp = FileStamp(10L, 1_000L)
        val cache = FileStampCache(
            read = { reads++; "состояние-$reads" },
            stamp = { stamp },
            maxAgeMs = 900L,
            now = { clockMs },
        )

        assertEquals("состояние-1", cache.get())
        clockMs = 899L
        assertEquals("состояние-1", cache.get())
        // Отметка та же, но срок вышел: файл читается заново. Иначе правка,
        // не сдвинувшая отметку (та же миллисекунда, та же длина), застряла бы
        // в кэше до следующей записи.
        clockMs = 1_000L
        assertEquals("состояние-2", cache.get())
        assertEquals(2, reads)
    }

    @Test
    fun `срок по умолчанию не истекает`() {
        var clockMs = 0L
        var reads = 0
        val cache = FileStampCache(
            read = { reads++; "значение" },
            stamp = { FileStamp(1L, 1L) },
            now = { clockMs },
        )

        cache.get()
        clockMs = 86_400_000L
        assertEquals("значение", cache.get())
        assertEquals(1, reads)
    }

    @Test
    fun `без отметки файл читается каждый раз`() {
        val file = FakeFile(stampValue = null)
        file.payload = "состояние"

        file.cache.get()
        file.cache.get()

        // У пустого файла читатель обязан спросить резервную копию и настройки,
        // а они меняются без всякой отметки.
        assertEquals(2, file.reads)
    }

    @Test
    fun `своя запись сбрасывает кэш`() {
        val file = FakeFile()
        file.payload = "STOPPED"
        assertEquals("STOPPED", file.cache.get())

        file.payload = "CONNECTING"
        file.cache.invalidate()

        assertEquals("CONNECTING", file.cache.get())
        assertEquals(2, file.reads)
    }

    @Test
    fun `подмена во время чтения не запоминается`() {
        val file = FakeFile()
        file.payload = "первое"
        assertEquals("первое", file.cache.get())

        // Соседний процесс переписал файл: отметка сменилась, чтение состоится —
        // и ровно в его ходе файл подменили ещё раз.
        file.stampValue = FileStamp(11L, 2_000L)
        file.payload = "второе"
        file.onRead = { file.stampValue = FileStamp(12L, 3_000L) }
        assertEquals("второе", file.cache.get())

        // Прочитанное не запомнено: следующий вопрос обязан дойти до файла.
        file.onRead = null
        file.payload = "третье"
        assertEquals("третье", file.cache.get())
        assertEquals(3, file.reads)
    }

    @Test
    fun `ошибка чтения не оставляет кэша`() {
        val file = FakeFile()
        file.payload = "состояние"
        file.failOnRead = IllegalStateException("нет доступа")

        val thrown = runCatching { file.cache.get() }.exceptionOrNull()
        assertEquals("нет доступа", thrown?.message)

        file.failOnRead = null
        assertEquals("состояние", file.cache.get())
        assertEquals(2, file.reads)
    }

    @Test
    fun `запомненное отсутствие значения — тоже значение`() {
        val file = FakeFile()
        file.payload = null

        assertNull(file.cache.get())
        assertNull(file.cache.get())
        // «Прочитали и ничего нет» — не повод читать снова на каждом такте.
        assertEquals(1, file.reads)
    }
}
