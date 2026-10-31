package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Test

/** Подписи этапов подключения для экрана. */
class ConnectStageLabelTest {

    @Test
    fun `все этапы службы названы`() {
        assertEquals("сеть", ConnectStageLabel.label("транспорт"))
        assertEquals("интерфейс", ConnectStageLabel.label("интерфейс"))
        assertEquals("ядро", ConnectStageLabel.label("ядро"))
        assertEquals("ядро", ConnectStageLabel.label("движок"))
        assertEquals("рукопожатие", ConnectStageLabel.label("рукопожатие"))
        assertEquals("проверка", ConnectStageLabel.label("проба"))
    }

    @Test
    fun `регистр и пробелы не важны`() {
        assertEquals("ядро", ConnectStageLabel.label("  Ядро "))
        assertEquals("проба", ConnectStageLabel.label("ПРОБА"))
    }

    @Test
    fun `незнакомый этап молчит, а не показывает сырой ключ`() {
        assertEquals("", ConnectStageLabel.label("masque-bootstrap"))
        assertEquals("", ConnectStageLabel.label(""))
    }
}
