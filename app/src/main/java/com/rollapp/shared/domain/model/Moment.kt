package com.rollapp.shared.domain.model

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

/**
 * A 5-second clip of what someone in the roll is up to. Mirrors
 * `groups/{groupId}/moments/{momentId}`. Moments are grouped by ISO week; at the end
 * of the week they are stitched into one montage.
 */
data class Moment(
    val id: String = "",
    val uploadedBy: String = "",
    val uploaderName: String = "",
    val videoUrl: String = "",
    val createdAt: Long = 0L,
    val week: String = ""
)

/** "2026-W40": the key a week's moments share. */
fun weekKey(millis: Long = System.currentTimeMillis()): String {
    val date = java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return weekKey(date)
}

fun weekKey(date: LocalDate): String =
    "%d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
