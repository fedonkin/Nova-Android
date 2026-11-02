package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Секунды ожидания подключения: что показываем, а что нет. */
class ConnectElapsedTest {

    @Test
    fun `подключения нет — показывать нечего`() {
        assertNull(ConnectElapsed.secondsOrNull(0L, 10_000L))
        assertNull(ConnectElapsed.secondsOrNull(-5L, 10_000L))
    }

    @Test
    fun `меньше секунды прячем`() {
        assertNull(ConnectElapsed.secondsOrNull(1_000L, 1_000L))
        assertNull(ConnectElapsed.secondsOrNull(1_000L, 1_999L))
    }

    @Test
    fun `секунды считаются целыми вниз`() {
        assertEquals(1L, ConnectElapsed.secondsOrNull(1_000L, 2_000L))
        assertEquals(1L, ConnectElapsed.secondsOrNull(1_000L, 2_999L))
        assertEquals(4L, ConnectElapsed.secondsOrNull(1_000L, 5_400L))
    }

    @Test
    fun `сбитые часы не дают отрицательных секунд`() {
        assertNull(ConnectElapsed.secondsOrNull(50_000L, 10_000L))
    }

    @Test
    fun `потолок держит ширину надписи`() {
        assertEquals(
            ConnectElapsed.MAX_SHOWN_SECONDS,
            ConnectElapsed.secondsOrNull(1_000L, 1_000L + 20 * 60 * 1_000L),
        )
    }
}
