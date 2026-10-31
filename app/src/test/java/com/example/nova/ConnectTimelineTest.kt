package com.example.nova

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Разбивка времени подключения: метки, повторные метки и пустой замер.
 *
 * Часы и журнал подменяются: `SystemClock` и `LogManager` в JVM-тестах не
 * поднимаются, а проверяется здесь арифметика меток, а не они.
 */
class ConnectTimelineTest {

    private var now = 0L
    private val logged = ArrayList<String>()

    @Before
    fun setUp() {
        ConnectTimeline.timeSource = { now }
        ConnectTimeline.logSink = { logged += it }
    }

    @After
    fun tearDown() {
        ConnectTimeline.discard()
        ConnectTimeline.timeSource = { android.os.SystemClock.elapsedRealtime() }
        ConnectTimeline.logSink = { LogManager.log(it) }
    }

    @Test
    fun `метки считаются от начала замера`() {
        ConnectTimeline.begin("warp")
        now = 180
        ConnectTimeline.mark("транспорт")
        now = 420
        ConnectTimeline.mark("ядро")
        now = 1680
        ConnectTimeline.mark("рукопожатие")
        now = 4310

        val line = ConnectTimeline.finish("подключено")

        assertTrue(line, line.contains("всего 4310 мс"))
        assertTrue(line, line.contains("→транспорт 180"))
        assertTrue(line, line.contains("→ядро 420"))
        assertTrue(line, line.contains("→рукопожатие 1680"))
        assertTrue(line, line.contains("warp"))
        assertEquals(listOf(line), logged)
    }

    @Test
    fun `повторная метка заменяет прежнюю, а не добавляет вторую`() {
        ConnectTimeline.begin("warp")
        now = 100
        ConnectTimeline.mark("проба")
        now = 900
        ConnectTimeline.mark("проба")
        now = 1000

        val line = ConnectTimeline.finish("подключено")

        assertTrue(line, line.contains("→проба 900"))
        assertEquals(1, Regex("→проба").findAll(line).count())
    }

    @Test
    fun `замер без begin ничего не печатает`() {
        ConnectTimeline.discard()
        now = 500
        ConnectTimeline.mark("ядро")

        assertEquals("", ConnectTimeline.finish("подключено"))
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `замер без меток всё равно даёт строку с итогом`() {
        ConnectTimeline.begin("opera")
        now = 700

        val line = ConnectTimeline.finish("подключено")

        assertTrue(line, line.contains("всего 700 мс"))
        assertTrue(line, line.contains("opera"))
    }
}
