package com.rollapp.shared.domain.repository

import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.PendingUpload
import kotlinx.coroutines.flow.Flow

interface UploadQueueRepository {
    /** Everything not yet delivered, across all groups. Drives the "3 waiting" banner. */
    fun observePending(): Flow<List<PendingUpload>>
    fun observePendingForGroup(groupId: String): Flow<List<PendingUpload>>

    suspend fun retry(uploadId: String): Outcome<Unit>
    suspend fun retryAllFailed(): Outcome<Unit>
    suspend fun cancel(uploadId: String): Outcome<Unit>
    suspend fun clearCompleted(): Outcome<Unit>
}
