package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationPhaseTest {

    private val eps = 1e-4f

    @Test
    fun diveCycleIsOneMinuteOfClockTime() {
        assertEquals(0.0, DivePhase.time(0.0), 1e-9)
        assertEquals(1.0, DivePhase.time(60.0), 1e-9)
        assertEquals(2, DivePhase.cycleOf(DivePhase.time(130.0)))
    }

    @Test
    fun secondLayerIsHalfCycleBehindAndWeightsSumToOne() {
        for (step in 0 until 200) {
            val t = step * 0.37
            val phaseA = DivePhase.phaseOf(t, 0.0)
            val phaseB = DivePhase.phaseOf(t, 0.5)
            assertTrue(phaseA in 0f..1f)
            assertTrue(phaseB in 0f..1f)
            // Вес A = 1 − вес B = sin²(π·фаза A): при сдвиге на полцикла sin² и cos² дают единицу.
            val wA = 1f - DivePhase.weightB(phaseB)
            val s = Math.sin(Math.PI * phaseA.toDouble()).toFloat()
            assertEquals(s * s, wA, 1e-3f)
        }
    }

    @Test
    fun secondLayerIsInvisibleWhenItStartsItsCycle() {
        // Фаза B в нуле — новый узор ставится невидимо (вес нуль).
        assertEquals(0f, DivePhase.weightB(0f), eps)
    }

    @Test
    fun cycleIndexOfSecondLayerIsOffsetByHalf() {
        val t = 3.25
        assertEquals(3, DivePhase.cycleOf(t))
        assertEquals(3, DivePhase.cycleOf(t + 0.5))
        assertEquals(3, DivePhase.cycleOf(3.49))
        assertEquals(3, DivePhase.cycleOf(3.5))
        assertEquals(4, DivePhase.cycleOf(4.0))
    }

    @Test
    fun wallBounceMatchesElasticReflection() {
        // Из 10 вправо до стенки 100 (90), назад на 5 — итого 95.
        assertEquals(95f, WallBounce.position(10f, 95f, 100f), eps)
        assertEquals(100f, WallBounce.position(10f, 90f, 100f), eps)
        // 190 пути: 90 до стенки, 100 назад до нуля — точно в нуле.
        assertEquals(0f, WallBounce.position(10f, 190f, 100f), eps)
        // Влево: из 10 к нулю (10), затем обратно на 5.
        assertEquals(5f, WallBounce.position(10f, -15f, 100f), eps)
        assertEquals(10f, WallBounce.position(10f, 0f, 100f), eps)
        assertEquals(0f, WallBounce.position(10f, 0f, 0f), eps)
    }

    @Test
    fun wallBounceStaysInsideBounds() {
        for (k in -500..500) {
            val p = WallBounce.position(37f, k * 3.7f, 120f)
            assertTrue("p=$p", p >= -eps && p <= 120f + eps)
        }
    }

    @Test
    fun keyedSeedsReuseOnlyWhenAsked() {
        val seeds = KeyedSeeds()
        var made = 0
        val make = { made++; made.toLong() * 1000L }
        val first = seeds.seed("main-dns", reuse = false, make = make)
        // Повторное появление без нажатия — то же зерно, новое не создаётся.
        val reused = seeds.seed("main-dns", reuse = true, make = make)
        assertEquals(first, reused)
        assertEquals(1, made)
        // Нажатие — новое зерно, и оно становится сохранённым.
        val fresh = seeds.seed("main-dns", reuse = false, make = make)
        assertEquals(2, made)
        assertEquals(fresh, seeds.seed("main-dns", reuse = true) { 0L })
        // Чужой ключ не делит зерно.
        assertEquals(99L, seeds.seed("settings-dns", reuse = true) { 99L })
    }

    @Test
    fun dialHeadTurnsOncePerRevolutionAndWraps() {
        assertEquals(0f, CoreDial.headDegrees(0.0, 46.0), eps)
        assertEquals(180f, CoreDial.headDegrees(23.0, 46.0), eps)
        assertEquals(90f, CoreDial.headDegrees(46.0 * 3 + 11.5, 46.0), eps)
        // Отрицательное время — та же точка на окружности.
        assertEquals(270f, CoreDial.headDegrees(-11.5, 46.0), eps)
        // Оборот не задан — головка стоит.
        assertEquals(0f, CoreDial.headDegrees(5.0, 0.0), eps)
    }

    @Test
    fun dialHeadIsMonotonicInsideOneRevolution() {
        var previous = -1f
        for (step in 0 until 100) {
            val angle = CoreDial.headDegrees(step * 0.46, 46.0)
            assertTrue("angle=$angle", angle >= 0f && angle < 360f)
            assertTrue("angle=$angle", angle > previous)
            previous = angle
        }
    }

    @Test
    fun dialBreathStaysInRangeAndHitsBothEnds() {
        val period = 7.5
        assertEquals(0.5f, CoreDial.breath(0.0, period), eps)
        assertEquals(1f, CoreDial.breath(period / 4.0, period), 1e-4f)
        assertEquals(0f, CoreDial.breath(period * 3 / 4.0, period), 1e-4f)
        for (step in 0 until 200) {
            val value = CoreDial.breath(step * 0.13, period)
            assertTrue("value=$value", value >= 0f && value <= 1f)
        }
        assertEquals(0f, CoreDial.breath(1.0, 0.0), eps)
    }

    @Test
    fun dialEmphasisPeaksUnderTheHeadAndDiesAtTheEdge() {
        assertEquals(1f, CoreDial.emphasis(90f, 90f, 24f), eps)
        assertEquals(0f, CoreDial.emphasis(90f, 140f, 24f), eps)
        // Квадратичный спад: на половине зоны остаётся четверть.
        assertEquals(0.25f, CoreDial.emphasis(90f, 102f, 24f), 1e-3f)
        // Стык 0/360 не даёт вспышки: подсветка идёт по кратчайшей дуге.
        assertTrue(CoreDial.emphasis(359f, 1f, 10f) > 0.6f)
        assertTrue(CoreDial.emphasis(1f, 359f, 10f) > 0.6f)
        assertEquals(0f, CoreDial.emphasis(10f, 200f, 0f), eps)
    }

    @Test
    fun dialRevealRisesSmoothlyFromZeroToOne() {
        assertEquals(0f, CoreDial.reveal(0.0, 0.9), eps)
        assertEquals(1f, CoreDial.reveal(0.9, 0.9), eps)
        // За пределами срока появление не продолжается.
        assertEquals(1f, CoreDial.reveal(9.0, 0.9), eps)
        assertEquals(0.5f, CoreDial.reveal(0.45, 0.9), 0.02f)
        var previous = -1f
        for (step in 0..20) {
            val value = CoreDial.reveal(step * 0.05, 0.9)
            assertTrue("value=$value", value >= previous)
            previous = value
        }
        // Нулевой срок — композиция видна сразу, без деления на ноль.
        assertEquals(1f, CoreDial.reveal(0.0, 0.0), eps)
    }

    @Test
    fun bandProgressWrapsAndKeepsItsOffset() {
        assertEquals(0f, CoreDial.bandProgress(0.0, 8.2, 0f), eps)
        assertEquals(0.5f, CoreDial.bandProgress(0.0, 8.2, 0.5f), eps)
        assertEquals(0.75f, CoreDial.bandProgress(4.1, 8.2, 0.25f), eps)
        assertEquals(0f, CoreDial.bandProgress(8.2 * 4, 8.2, 0f), 1e-4f)
        // Период не задан — волна стоит, а не делится на ноль.
        assertEquals(0f, CoreDial.bandProgress(3.0, 0.0, 0.4f), eps)
    }

    @Test
    fun bandProgressStaysInRangeAndGrowsWithTime() {
        val cycle = 7.3
        var previous = -1f
        var wraps = 0
        for (step in 0 until 400) {
            val value = CoreDial.bandProgress(step * 0.05, cycle, 0f)
            assertTrue("value=$value", value >= 0f && value < 1f)
            if (value < previous) wraps++
            previous = value
        }
        // 20 секунд при периоде 7.3 — это два полных оборота и ещё немного.
        assertEquals(2, wraps)
    }

    @Test
    fun bandExpansionDeceleratesAndStaysNormalized() {
        assertEquals(0f, CoreDial.expansion(0f), eps)
        assertEquals(1f, CoreDial.expansion(1f), eps)
        assertEquals(0f, CoreDial.expansion(-0.5f), eps)
        assertEquals(1f, CoreDial.expansion(1.5f), eps)
        // Замедление: в середине пути фронт ушёл дальше половины.
        assertTrue(CoreDial.expansion(0.5f) > 0.5f)
        var previous = -1f
        for (step in 0..200) {
            val value = CoreDial.expansion(step / 200f)
            assertTrue("value=$value", value >= previous)
            previous = value
        }
    }
}
