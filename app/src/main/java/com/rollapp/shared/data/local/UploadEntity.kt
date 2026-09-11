package com.rollapp.shared.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rollapp.shared.domain.model.PendingUpload
import com.rollapp.shared.domain.model.UploadState

@Entity(
    tableName = "pending_uploads",
    indices = [Index("groupId"), Index("state"), Index("ownerUid")]
)
data class UploadEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    /** Whose queue this is. A shared device must not upload one user's photos as another. */
    val ownerUid: String,
    val localUri: String,
    val caption: String?,
    val capturedAt: Long,
    val createdAt: Long,
    val state: String,
    @ColumnInfo(defaultValue = "0") val progress: Float,
    @ColumnInfo(defaultValue = "0") val attemptCount: Int,
    val errorMessage: String?,
    /** Set once the bytes land, so a retry never creates a second Firestore document. */
    val remotePhotoId: String?
) {
    fun toDomain() = PendingUpload(
        id = id,
        groupId = groupId,
        localUri = localUri,
        caption = caption,
        capturedAt = capturedAt,
        createdAt = createdAt,
        state = runCatching { UploadState.valueOf(state) }.getOrDefault(UploadState.QUEUED),
        progress = progress,
        attemptCount = attemptCount,
        errorMessage = errorMessage
    )
}
