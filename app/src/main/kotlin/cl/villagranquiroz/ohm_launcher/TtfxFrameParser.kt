package cl.villagranquiroz.ohm_launcher

import java.io.EOFException
import java.io.InputStream

data class TtfxCell(val symbol: String?, val color: Int)

data class TtfxFrame(
    val columns: Int,
    val rows: Int,
    val cells: List<TtfxCell>,
) {
    fun cell(column: Int, row: Int): TtfxCell = cells[row * columns + column]
}

object TtfxFrameParser {
    private const val DEFAULT_COLOR = -1

    fun parse(source: String): TtfxFrame {
        val lines = mutableListOf<MutableList<TtfxCell>>(mutableListOf())
        var color = DEFAULT_COLOR
        var index = 0
        while (index < source.length) {
            val character = source[index]
            if (character == '\u001B' && index + 1 < source.length && source[index + 1] == '[') {
                val end = source.indexOf('m', index + 2)
                if (end >= 0) {
                    color = applySgr(source.substring(index + 2, end), color)
                    index = end + 1
                    continue
                }
            }
            when (character) {
                '\n' -> lines.add(mutableListOf())
                '\r' -> Unit
                else -> {
                    val codePoint = source.codePointAt(index)
                    val symbol = String(Character.toChars(codePoint))
                    lines.last().add(TtfxCell(symbol.takeUnless { it == " " }, color))
                    index += Character.charCount(codePoint)
                    continue
                }
            }
            index++
        }
        while (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        val columns = lines.maxOfOrNull { it.size } ?: 0
        val empty = TtfxCell(null, DEFAULT_COLOR)
        val cells = buildList {
            lines.forEach { line ->
                addAll(line)
                repeat(columns - line.size) { add(empty) }
            }
        }
        return TtfxFrame(columns, lines.size, cells)
    }

    private fun applySgr(raw: String, current: Int): Int {
        if (raw.isEmpty()) return DEFAULT_COLOR
        val values = raw.split(';').mapNotNull(String::toIntOrNull)
        var color = current
        var index = 0
        while (index < values.size) {
            when (values[index]) {
                0, 39 -> color = DEFAULT_COLOR
                in 30..37 -> color = BASIC_COLORS[values[index] - 30]
                in 90..97 -> color = BRIGHT_COLORS[values[index] - 90]
                38 -> when {
                    values.getOrNull(index + 1) == 2 && index + 4 < values.size -> {
                        color = argb(values[index + 2], values[index + 3], values[index + 4])
                        index += 4
                    }
                    values.getOrNull(index + 1) == 5 && index + 2 < values.size -> {
                        color = xterm(values[index + 2])
                        index += 2
                    }
                }
            }
            index++
        }
        return color
    }

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

    private fun xterm(index: Int): Int {
        val value = index.coerceIn(0, 255)
        if (value < 8) return BASIC_COLORS[value]
        if (value < 16) return BRIGHT_COLORS[value - 8]
        if (value >= 232) {
            val gray = 8 + (value - 232) * 10
            return argb(gray, gray, gray)
        }
        val cube = value - 16
        val red = cube / 36
        val green = cube / 6 % 6
        val blue = cube % 6
        fun component(part: Int) = if (part == 0) 0 else 55 + part * 40
        return argb(component(red), component(green), component(blue))
    }

    private val BASIC_COLORS = intArrayOf(
        argb(0, 0, 0), argb(205, 49, 49), argb(13, 188, 121), argb(229, 229, 16),
        argb(36, 114, 200), argb(188, 63, 188), argb(17, 168, 205), argb(229, 229, 229),
    )
    private val BRIGHT_COLORS = intArrayOf(
        argb(102, 102, 102), argb(241, 76, 76), argb(35, 209, 139), argb(245, 245, 67),
        argb(59, 142, 234), argb(214, 112, 214), argb(41, 184, 219), argb(255, 255, 255),
    )
}

class TtfxFrameReader(private val input: InputStream) {
    fun next(): String? {
        val header = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (header.isEmpty()) null else throw EOFException("truncated frame header")
            if (byte == '\n'.code) break
            header.append(byte.toChar())
        }
        val length = header.toString().toIntOrNull() ?: throw IllegalArgumentException("invalid frame length")
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(data, offset, length - offset)
            if (count < 0) throw EOFException("truncated frame body")
            offset += count
        }
        if (input.read() != '\n'.code) throw IllegalArgumentException("missing frame separator")
        return data.toString(Charsets.UTF_8).removeSuffix("\n")
    }
}
