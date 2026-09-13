package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ParticleClockAssignmentTest {
    @Test
    fun changingOneDigitOnlyRetargetsThatCharactersPool() {
        val assignment = ParticleClockAssignment(seed = 7)
        assignment.assign(samples("12:34:56"))
        val stablePool = assignment.pools[0]
        val stableTargets = stablePool.grains.map { it.targetX to it.targetY }
        val changedPool = assignment.pools[7]
        val changedTargets = changedPool.grains.map { it.targetX to it.targetY }

        val changed = assignment.assign(samples("12:34:57"))

        assertEquals(setOf(7), changed)
        assertSame(stablePool, assignment.pools[0])
        assertEquals(stableTargets, stablePool.grains.map { it.targetX to it.targetY })
        assertSame(changedPool, assignment.pools[7])
        assertEquals(false, changedTargets == changedPool.grains.map { it.targetX to it.targetY })
    }

    @Test
    fun characterPoolsGrowButNeverRecreateOrShrinkTheirGrains() {
        val assignment = ParticleClockAssignment(seed = 9)
        assignment.assign(listOf(ParticleGlyphSample("1", listOf(ParticleTarget(1f, 1f)))))
        val first = assignment.pools.single().grains.single()

        assignment.assign(
            listOf(
                ParticleGlyphSample(
                    "8",
                    listOf(ParticleTarget(2f, 2f), ParticleTarget(3f, 3f), ParticleTarget(4f, 4f)),
                ),
            ),
        )
        assertEquals(3, assignment.pools.single().grains.size)
        assertSame(first, assignment.pools.single().grains.first())

        assignment.assign(listOf(ParticleGlyphSample("1", listOf(ParticleTarget(5f, 5f)))))
        assertEquals(3, assignment.pools.single().grains.size)
        assertSame(first, assignment.pools.single().grains.first())
    }

    private fun samples(text: String): List<ParticleGlyphSample> = text.mapIndexed { index, character ->
        ParticleGlyphSample(
            character.toString(),
            listOf(
                ParticleTarget(index * 20f + character.code % 7, 10f),
                ParticleTarget(index * 20f + character.code % 11, 20f),
            ),
        )
    }
}
