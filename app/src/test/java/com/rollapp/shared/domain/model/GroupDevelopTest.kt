package com.rollapp.shared.domain.model

import com.rollapp.shared.core.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupDevelopTest {

    @Test
    fun `a fresh free roll has every exposure left`() {
        val group = Group(photoCount = 0, exposureLimit = Limits.FREE_ROLL_PHOTO_LIMIT)
        assertEquals(Limits.FREE_ROLL_PHOTO_LIMIT, group.exposuresLeft)
        assertFalse(group.isFull)
    }

    @Test
    fun `a free roll at the limit is full`() {
        val group = Group(photoCount = Limits.FREE_ROLL_PHOTO_LIMIT, exposureLimit = Limits.FREE_ROLL_PHOTO_LIMIT)
        assertEquals(0, group.exposuresLeft)
        assertTrue(group.isFull)
    }

    @Test
    fun `a count past the limit never goes negative`() {
        val group = Group(photoCount = Limits.FREE_ROLL_PHOTO_LIMIT + 57, exposureLimit = Limits.FREE_ROLL_PHOTO_LIMIT)
        assertEquals(0, group.exposuresLeft)
        assertTrue(group.isFull)
    }

    @Test
    fun `a developed roll has no limit`() {
        val group = Group(photoCount = 5_000, developed = true, exposureLimit = Limits.FREE_ROLL_PHOTO_LIMIT)
        assertNull(group.exposuresLeft)
        assertFalse(group.isFull)
    }

    @Test
    fun `an early roll without a limit is unlimited but still developable`() {
        val group = Group(photoCount = 850, exposureLimit = null)
        assertNull(group.exposuresLeft)
        assertFalse(group.isFull)
        assertTrue(group.isEarlyRoll)
        assertFalse(group.copy(developed = true).isEarlyRoll)
    }
}
