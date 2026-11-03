package com.sanora.giltune

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Rapid-skip guard: own-track bag gives 5 different files and never repeats across bag refills. */
class SkipLogicTest {
    private val files = listOf("a", "b", "c", "d", "e")

    @Test fun bagHasAllFiveDistinct() {
        repeat(200) { s ->
            val r = Random(s); val bag = QueryPlanner.shuffleBag(files, null, null) { r.nextDouble() }
            assertEquals(files.toSet(), bag.toSet()); assertEquals(5, bag.size)
        }
    }

    @Test fun noSameFileTwiceInARowAcrossRefills() {
        val r = Random(7); var last: String? = null
        repeat(300) {
            val scene = files[r.nextInt(5)]
            for (f in QueryPlanner.shuffleBag(files, scene, last) { r.nextDouble() }) { assertNotEquals(last, f); last = f }
        }
    }

    @Test fun sceneFileLeadsUnlessJustPlayed() {
        assertEquals("c", QueryPlanner.shuffleBag(files, "c", "a") { 0.3 }[0])
        assertNotEquals("c", QueryPlanner.shuffleBag(files, "c", "c") { 0.3 }[0])
    }
}
