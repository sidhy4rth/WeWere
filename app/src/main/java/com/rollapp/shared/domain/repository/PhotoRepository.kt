package com.rollapp.shared.domain.repository

import android.net.Uri
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.domain.model.Reaction
import kotlinx.coroutines.flow.Flow

/** One page of photos plus whether older ones remain. */
data class PhotoPage(
    val photos: List<Photo> = emptyList(),
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false
)

interface PhotoRepository {
    /**
     * Live, paginated feed. The newest [pageSize] photos arrive over a realtime
     * listener; calling [loadOlder] extends the same window backwards.
     */
    fun observePhotos(groupId: String): Flow<PhotoPage>
    suspend fun loadOlder(groupId: String)
    fun resetPagination(groupId: String)

    fun observePhoto(groupId: String, photoId: String): Flow<Photo?>

    /** Queues an upload; returns immediately with the pending-upload id. */
    suspend fun enqueueUpload(groupId: String, localUri: Uri, caption: String?, capturedAt: Long): Outcome<String>

    suspend fun deletePhoto(groupId: String, photoId: String): Outcome<Unit>
    suspend fun setCaption(groupId: String, photoId: String, caption: String?): Outcome<Unit>

    /** Passing null clears this user's reaction. Toggling is handled by the caller. */
    suspend fun setReaction(groupId: String, photoId: String, reaction: Reaction?): Outcome<Unit>

    /** Saves the full-resolution image into the device's public Pictures collection. */
    suspend fun downloadToGallery(photo: Photo): Outcome<Uri>

    /** Copies the image to cache and returns a FileProvider uri for the share sheet. */
    suspend fun prepareForSharing(photo: Photo): Outcome<Uri>

    suspend fun reportPhoto(groupId: String, photoId: String, reason: String): Outcome<Unit>
}
