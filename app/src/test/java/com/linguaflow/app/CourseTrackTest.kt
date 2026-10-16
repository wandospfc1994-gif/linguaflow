package com.linguaflow.app

import com.linguaflow.app.data.model.CourseTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseTrackTest {

    @Test
    fun testLevelCalculationForXp() {
        val (lvl0, next0) = CourseTrack.calculateLevelForXp(0)
        assertEquals("A1", lvl0)
        assertEquals(300, next0)

        val (lvlA2, nextA2) = CourseTrack.calculateLevelForXp(350)
        assertEquals("A2", lvlA2)
        assertEquals(800, nextA2)

        val (lvlB1, nextB1) = CourseTrack.calculateLevelForXp(900)
        assertEquals("B1", lvlB1)
        assertEquals(1600, nextB1)

        val (lvlB2, nextB2) = CourseTrack.calculateLevelForXp(1800)
        assertEquals("B2", lvlB2)
        assertEquals(3000, nextB2)

        val (lvlC1, nextC1) = CourseTrack.calculateLevelForXp(3500)
        assertEquals("C1", lvlC1)
        assertEquals(5000, nextC1)
    }

    @Test
    fun testFluencyCalculation() {
        val fluencyA1 = CourseTrack.calculateFluencyPercentage(50, 90)
        assertTrue(fluencyA1 in 10..40)

        val fluencyC1 = CourseTrack.calculateFluencyPercentage(4000, 95)
        assertTrue(fluencyC1 in 85..99)
    }

    @Test
    fun testChallengeSetsPresence() {
        assertTrue(CourseTrack.challengeSets.size >= 4)
        CourseTrack.challengeSets.forEach { set ->
            assertTrue(set.exercises.isNotEmpty())
            assertTrue(set.xpReward >= 50)
        }
    }
}
