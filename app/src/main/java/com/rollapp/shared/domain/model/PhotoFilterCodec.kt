package com.rollapp.shared.domain.model

import android.net.Uri

/**
 * Encodes the active filter into a navigation argument.
 *
 * The carousel has to page over exactly the set the grid was showing — tapping the
 * fifth starred photo must open the fifth starred photo, not the fifth photo overall.
 * Passing the filter through navigation (rather than sharing view state between two
 * ViewModels) also means it survives process death.
 */
object PhotoFilterCodec {

    fun encode(filter: PhotoFilter): String = when (filter) {
        PhotoFilter.All -> "all"
        PhotoFilter.Favorites -> "fav"
        is PhotoFilter.ByUploader -> "by:${filter.uid}:${Uri.encode(filter.name)}"
    }

    fun decode(raw: String?): PhotoFilter = when {
        raw == null || raw == "all" -> PhotoFilter.All
        raw == "fav" -> PhotoFilter.Favorites
        raw.startsWith("by:") -> {
            // Split on the first two colons only; a display name may contain one.
            val rest = raw.removePrefix("by:")
            val uid = rest.substringBefore(':')
            val name = Uri.decode(rest.substringAfter(':', ""))
            if (uid.isBlank()) PhotoFilter.All else PhotoFilter.ByUploader(uid, name)
        }
        else -> PhotoFilter.All
    }
}
