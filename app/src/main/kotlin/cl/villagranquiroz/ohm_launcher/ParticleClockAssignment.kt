package cl.villagranquiroz.ohm_launcher

import kotlin.math.min
import kotlin.random.Random

data class ParticleTarget(val x: Float, val y: Float)
data class ParticleGlyphSample(val character: String, val points: List<ParticleTarget>, val centerX: Float = 0f)

class ParticleGrain(
    var x: Float,
    var y: Float,
    var targetX: Float,
    var targetY: Float,
    val jitterX: Float,
    val jitterY: Float,
    var radiusScale: Float,
    val opacity: Float,
    val phase: Float,
    val speed: Float,
)

class ParticleCharacterPool(var character: String = "") {
    val grains = mutableListOf<ParticleGrain>()
}

/** Stable per-character pools matching the original Flutter particle clock. */
class ParticleClockAssignment(
    seed: Int = 0x0A11CE,
    private val maxGrainsPerCharacter: Int = 200,
) {
    private val random = Random(seed)
    val pools = mutableListOf<ParticleCharacterPool>()

    fun assign(samples: List<ParticleGlyphSample>, forceAll: Boolean = false): Set<Int> {
        while (pools.size < samples.size) pools += ParticleCharacterPool()
        if (pools.size > samples.size) pools.subList(samples.size, pools.size).clear()
        val changed = linkedSetOf<Int>()
        samples.forEachIndexed { index, sample ->
            val pool = pools[index]
            if (!forceAll && pool.character == sample.character && pool.grains.isNotEmpty()) return@forEachIndexed
            changed += index
            pool.character = sample.character
            if (sample.points.isEmpty()) {
                pool.grains.forEach { grain ->
                    grain.x = sample.centerX
                    grain.y = 0f
                    grain.targetX = sample.centerX
                    grain.targetY = 0f
                    grain.radiusScale = 0f
                }
                return@forEachIndexed
            }
            val needed = min(sample.points.size, maxGrainsPerCharacter)
            while (pool.grains.size < needed) {
                val point = sample.points[pool.grains.size % sample.points.size]
                val jitterX = (random.nextFloat() - .5f) * 1.5f
                val jitterY = (random.nextFloat() - .5f) * 1.5f
                pool.grains += ParticleGrain(
                    x = point.x + jitterX,
                    y = point.y + jitterY,
                    targetX = point.x + jitterX,
                    targetY = point.y + jitterY,
                    jitterX = jitterX,
                    jitterY = jitterY,
                    radiusScale = .5f + random.nextFloat(),
                    opacity = .55f + random.nextFloat() * .45f,
                    phase = random.nextFloat() * (Math.PI * 2).toFloat(),
                    speed = .6f + random.nextFloat() * .8f,
                )
            }
            pool.grains.forEachIndexed { grainIndex, grain ->
                val point = sample.points[grainIndex % sample.points.size]
                grain.targetX = point.x + grain.jitterX
                grain.targetY = point.y + grain.jitterY
                if (grain.radiusScale <= .1f) grain.radiusScale = .5f + random.nextFloat()
            }
        }
        return changed
    }
}
