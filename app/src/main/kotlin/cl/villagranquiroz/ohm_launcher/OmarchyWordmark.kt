package cl.villagranquiroz.ohm_launcher

import kotlin.math.max
import kotlin.math.roundToInt

object OmarchyWordmark {
    private val bitmap = """
.................###.............................................................
..#####......###########......#######....#######....#######....#...#......#...#..
.#######....#############....########...########...########...##...##....##...##.
###...###..###...###...###..###...###..###...###..###...###..###...###..###...###
###...###..###...###...###..###...###..###...###..###...###..###...###..###...###
###...###..###...###...###..###...###..###...###..###...##...###...###..###...###
###...###..###...###...###..###...###..###...###..###...#....###...###..###...###
###...###..###...###...###..###...###..###...###..###........###...###..###...###
###...###..###...###...###.##########.#########...###.......###########.#########
###...###..###...###...###.##########.########....###......###########..#########
###...###..###...###...###..###...###..###........###........###...###........###
###...###..###...###...###..###...###.##########..###...#....###...###...##...###
###...###..###...###...###..###...###.##########..###...##...###...###..###...###
###...###..###...###...###..###...###..###...###..###...###..###...###..###...###
###...###..###...###...###..###...###..###...###..###...###..###...###..###...###
.#######....##...###...##...###...##...###...###..########...###...##....#######.
..#####......#...###...#....###...#....###...###..#######....###...#......#####..
.......................................###...##..................................
.......................................###...#...................................
    """.trimIndent().lines()

    fun canvasColumns(resolution: Int, textSize: Int): Int {
        val configured = (160.0 / kotlin.math.sqrt(resolution.coerceIn(1, 8).toDouble()))
            .roundToInt().coerceIn(40, 120)
        val boost = when (textSize.coerceIn(1, 7)) {
            6 -> 1.15
            7 -> 1.30
            else -> 1.0
        }
        return (configured * boost).roundToInt().coerceIn(40, 120)
    }

    fun render(canvasColumns: Int, canvasRows: Int, textSize: Int): String {
        val factors = doubleArrayOf(.44, .57, .70, .83, .96, .98, 1.0)
        val targetColumns = (canvasColumns * factors[textSize.coerceIn(1, 7) - 1])
            .roundToInt().coerceIn(12, canvasColumns)
        val targetRows = (targetColumns * (.62 / 1.05) * (19.0 / 81.0))
            .roundToInt().coerceIn(3, canvasRows)
        val separators = doubleArrayOf(9.5, 26.0, 37.0, 48.5, 71.0)
            .map { (it * targetColumns / 81.0).roundToInt() }.toSet()
        return buildString {
            repeat(targetRows) { row ->
                val top = row * 19 / targetRows
                val bottom = max(top + 1, (row + 1) * 19 / targetRows)
                repeat(targetColumns) { column ->
                    val left = column * 81 / targetColumns
                    val right = max(left + 1, (column + 1) * 81 / targetColumns)
                    var filled = 0
                    var samples = 0
                    for (y in top until bottom) {
                        for (x in left until right) {
                            if (bitmap[y][x] == '#') filled++
                            samples++
                        }
                    }
                    append(if (column !in separators && filled.toDouble() / samples >= .28) '█' else ' ')
                }
                if (row + 1 < targetRows) append('\n')
            }
        }
    }
}
