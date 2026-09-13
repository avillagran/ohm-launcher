package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class TtfxFrameParserTest {
    @Test
    fun parsesTrueColorAnsiIntoFixedGridCells() {
        val frame = TtfxFrameParser.parse(" A\u001B[38;2;10;20;30m█\u001B[0m \n B  ")

        assertEquals(4, frame.columns)
        assertEquals(2, frame.rows)
        assertNull(frame.cell(0, 0).symbol)
        assertEquals("A", frame.cell(1, 0).symbol)
        assertEquals("█", frame.cell(2, 0).symbol)
        assertEquals(0xFF0A141E.toInt(), frame.cell(2, 0).color)
        assertEquals("B", frame.cell(1, 1).symbol)
    }

    @Test
    fun readsLengthPrefixedFramesAcrossExactByteBoundaries() {
        val bytes = "5\nhello\n5\nworld\n".toByteArray()
        val reader = TtfxFrameReader(ByteArrayInputStream(bytes))

        assertEquals("hello", reader.next())
        assertEquals("world", reader.next())
        assertNull(reader.next())
    }
}
