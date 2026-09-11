package com.rollapp.shared.domain.repository

import android.net.Uri
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.domain.model.PhotoFilter
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
     * Live, paginated feed. The newest page arrives over a realtime listener;
     * [loadOlder] extends the same window backwards. [filter] is applied server-side
     * so paging still works when only one person's photos are showing.
     */
    fun observePhotos(groupId: String, filter: PhotoFilter = PhotoFilter.All): Flow<PhotoPage>
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

    /** Stars or unstars a photo for the signed-in user only. */
    suspend fun setFavorite(groupId: String, photoId: String, favorite: Boolean): Outcome<Unit>

    /**
     * Saves many photos at once, reporting progress as it goes. Returns how many
     * landed — a partial success is still worth telling the user about.
     */
    suspend fun downloadAllToGallery(
        photos: List<Photo>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Outcome<Int>

    /** Deletes several photos, skipping any the user has no right to remove. */
    suspend fun deletePhotos(groupId: String, photoIds: List<String>): Outcome<Int>

    suspend fun reportPhoto(groupId: String, photoId: String, reason: String): Outcome<Unit>
}
