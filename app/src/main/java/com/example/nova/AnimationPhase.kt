package com.example.nova

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * Чистая математика фаз фоновых анимаций. Всё берётся от времени общих часов
 * ([NovaAnimationClock]), поэтому состояние не хранится в видах: пересоздание вида
 * или возврат из паузы попадают в ту же точку цикла.
 */

/** Фаза цикла в долях [0, 1) по прошедшему времени. Отрицательное время тоже даёт [0, 1). */
internal object SweepPhase {
    fun fraction(elapsedSeconds: Double, cycleSeconds: Double): Float {
        if (cycleSeconds <= 0.0) return 0f
        val x = elapsedSeconds / cycleSeconds
        return (x - floor(x)).toFloat()
    }
}

/**
 * Погружение фона. Один цикл — приближение вдвое за [CYCLE_SECONDS]; второй слой
 * сдвинут на полцикла. Вес второго слоя — sin²(π·фаза), он ноль ровно тогда, когда
 * слой вернулся к 1×, и смена узора в этот момент не видна.
 */
internal object DivePhase {
    const val CYCLE_SECONDS = 60.0

    /** Время погружения в циклах по времени общих часов. */
    fun time(clockSeconds: Double): Double = clockSeconds / CYCLE_SECONDS

    fun cycleOf(t: Double): Int = floor(t).toInt()

    /** Фаза слоя, сдвинутого на [offset] циклов, в долях [0, 1). */
    fun phaseOf(t: Double, offset: Double): Float {
        val x = t + offset
        return (x - floor(x)).toFloat()
    }

    fun weightB(phaseB: Float): Float {
        val s = sin(PI.toFloat() * phaseB)
        return s * s
    }
}

/**
 * Точка, которая идёт с постоянной скоростью и упруго отражается от стенок
 * `[0, length]`. Положение считается по пройденному пути, поэтому оно зависит
 * только от времени, а не от числа кадров, и совпадает с прежним пошаговым отскоком.
 */
internal object WallBounce {
    /**
     * @param start начальная координата
     * @param travel полный путь со знаком (скорость × время)
     * @param length длина отрезка, по которому отскакиваем
     */
    fun position(start: Float, travel: Float, length: Float): Float {
        if (length <= 0f) return 0f
        val period = 2.0 * length
        val unfolded = (start + travel).toDouble()
        val m = ((unfolded % period) + period) % period
        val folded = if (m <= length) m else period - m
        return folded.toFloat()
    }
}

/**
 * Циферблат вокруг кнопки подключения. Держит только то, что можно проверить
 * без Android: угол головки, дыхание зарева, подсветку рисок под головкой
 * и появление композиции.
 *
 * Все величины считаются от времени, а не от номера кадра: на 24 кадрах в секунду
 * и на 60 движение одно и то же, а возврат из паузы попадает в ту же точку.
 */
internal object CoreDial {
    /**
     * Угол головки в градусах [0, 360). Ноль — три часа, рост идёт по часовой
     * стрелке: так же считает `Canvas.drawArc`, поэтому конвертировать не нужно.
     */
    fun headDegrees(elapsedSeconds: Double, revolutionSeconds: Double): Float {
        if (revolutionSeconds <= 0.0) return 0f
        val turns = elapsedSeconds / revolutionSeconds
        return ((turns - floor(turns)) * 360.0).toFloat()
    }

    /**
     * Медленное дыхание зарева в долях [0, 1]. Синус, а не пила: у пилы на стыке
     * цикла виден рывок, и зарево мигает.
     */
    fun breath(elapsedSeconds: Double, periodSeconds: Double): Float {
        if (periodSeconds <= 0.0) return 0f
        val s = sin(2.0 * PI * elapsedSeconds / periodSeconds)
        return ((s + 1.0) * 0.5).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Насколько риска под головкой подсвечена: [0, 1] с квадратичным спадом.
     * Углы берутся по кратчайшей дуге, поэтому стык 0/360 не даёт вспышки.
     *
     * @param widthDegrees половина ширины зоны подсветки
     */
    fun emphasis(tickDegrees: Float, headDegrees: Float, widthDegrees: Float): Float {
        if (widthDegrees <= 0f) return 0f
        var delta = abs(tickDegrees - headDegrees) % 360f
        if (delta > 180f) delta = 360f - delta
        if (delta >= widthDegrees) return 0f
        val near = 1f - delta / widthDegrees
        return (near * near).coerceIn(0f, 1f)
    }

    /**
     * Прогресс появления композиции [0, 1] за [durationSeconds] секунд.
     * Сглаживание по краям: обод не «включается» ступенькой.
     */
    fun reveal(secondsSinceStart: Double, durationSeconds: Double): Float {
        if (durationSeconds <= 0.0) return 1f
        val x = (secondsSinceStart / durationSeconds).coerceIn(0.0, 1.0).toFloat()
        return x * x * (3f - 2f * x)
    }
}
