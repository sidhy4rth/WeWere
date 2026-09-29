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
        val group = Group(photoCount = 0)
        assertEquals(Limits.FREE_ROLL_PHOTO_LIMIT, group.exposuresLeft)
        assertFalse(group.isFull)
    }

    @Test
    fun `a free roll at the limit is full`() {
        val group = Group(photoCount = Limits.FREE_ROLL_PHOTO_LIMIT)
        assertEquals(0, group.exposuresLeft)
        assertTrue(group.isFull)
    }

    @Test
    fun `a count past the limit never goes negative`() {
        // Rolls created before the limit existed can already hold more.
        val group = Group(photoCount = Limits.FREE_ROLL_PHOTO_LIMIT + 57)
        assertEquals(0, group.exposuresLeft)
        assertTrue(group.isFull)
    }

    @Test
    fun `a developed roll has no limit`() {
        val group = Group(photoCount = 5_000, developed = true)
        assertNull(group.exposuresLeft)
        assertFalse(group.isFull)
    }
}
