package com.rollapp.shared.data.remote

import com.rollapp.shared.core.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteCodesTest {

    @Test
    fun `generated codes are the configured length`() {
        repeat(50) {
            assertEquals(Limits.INVITE_CODE_LENGTH, InviteCodes.generate().length)
        }
    }

    /** Confusable characters are what make a code unreadable over a phone camera. */
    @Test
    fun `generated codes avoid confusable characters`() {
        val banned = setOf('I', 'O', 'S', '0', '1', '5')
        repeat(200) {
            val code = InviteCodes.generate()
            assertTrue("'$code' contains a confusable character", code.none { it in banned })
        }
    }

    @Test
    fun `generated codes are accepted by the plausibility check`() {
        repeat(50) { assertTrue(InviteCodes.isPlausible(InviteCodes.generate())) }
    }

    @Test
    fun `codes do not collide over a large sample`() {
        val codes = List(5_000) { InviteCodes.generate() }
        // A handful of collisions at this sample size would signal a broken RNG.
        assertTrue("too many duplicates", codes.toSet().size > 4_990)
    }

    @Test
    fun `normalise upcases and strips the separators people add`() {
        assertEquals("GA7X2M", InviteCodes.normalise("ga7x2m"))
        assertEquals("GA7X2M", InviteCodes.normalise("  GA7 X2M "))
        assertEquals("GA7X2M", InviteCodes.normalise("GA7-X2M"))
        assertEquals("GA7X2M", InviteCodes.normalise("ga7_x2m"))
    }

    @Test
    fun `wrong length is not plausible`() {
        assertFalse(InviteCodes.isPlausible("GOA7X"))
        assertFalse(InviteCodes.isPlausible("GA7X2MM"))
        assertFalse(InviteCodes.isPlausible(""))
    }

    @Test
    fun `characters outside the alphabet are not plausible`() {
        assertFalse(InviteCodes.isPlausible("GOA7X0"))
        assertFalse(InviteCodes.isPlausible("GOA7XI"))
        assertFalse(InviteCodes.isPlausible("ga7x2m"))
    }

    @Test
    fun `codes are extracted from both link shapes`() {
        assertEquals("GA7X2M", InviteCodes.fromLink("https://roll.app/join/GA7X2M"))
        assertEquals("GA7X2M", InviteCodes.fromLink("https://roll.app/join/GA7X2M/"))
        assertEquals("GA7X2M", InviteCodes.fromLink("roll://join/ga7x2m"))
    }

    @Test
    fun `links without a usable code return null`() {
        assertNull(InviteCodes.fromLink("https://roll.app/join/"))
        assertNull(InviteCodes.fromLink("https://roll.app/join/TOOLONG"))
        assertNull(InviteCodes.fromLink("https://example.com"))
        assertNull(InviteCodes.fromLink(""))
    }
}
