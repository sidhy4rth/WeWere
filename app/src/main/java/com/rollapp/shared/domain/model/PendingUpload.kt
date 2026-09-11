package com.rollapp.shared.domain.model

enum class UploadState { QUEUED, UPLOADING, FAILED, COMPLETED, CANCELLED }

/**
 * A photo captured on-device that has not reached Firebase yet. Persisted in Room
 * so a queue survives process death, airplane mode and a dead battery.
 */
data class PendingUpload(
    val id: String,
    val groupId: String,
    val localUri: String,
    val caption: String?,
    val capturedAt: Long,
    val createdAt: Long,
    val state: UploadState,
    val progress: Float,
    val attemptCount: Int,
    val errorMessage: String?
) {
    val isTerminal: Boolean get() = state == UploadState.COMPLETED || state == UploadState.CANCELLED
}
