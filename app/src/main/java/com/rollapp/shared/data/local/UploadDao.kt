package com.rollapp.shared.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: UploadEntity)

    @Query("SELECT * FROM pending_uploads WHERE id = :id")
    suspend fun getById(id: String): UploadEntity?

    @Query(
        """
        SELECT * FROM pending_uploads
        WHERE ownerUid = :uid AND state != 'COMPLETED' AND state != 'CANCELLED'
        ORDER BY createdAt ASC
        """
    )
    fun observePending(uid: String): Flow<List<UploadEntity>>

    @Query(
        """
        SELECT * FROM pending_uploads
        WHERE ownerUid = :uid AND groupId = :groupId
          AND state != 'COMPLETED' AND state != 'CANCELLED'
        ORDER BY createdAt ASC
        """
    )
    fun observePendingForGroup(uid: String, groupId: String): Flow<List<UploadEntity>>

    /** Oldest first, so photos reach the group in the order they were taken. */
    @Query(
        """
        SELECT * FROM pending_uploads
        WHERE state = 'QUEUED' OR state = 'UPLOADING'
        ORDER BY createdAt ASC
        LIMIT :limit
        """
    )
    suspend fun claimBatch(limit: Int): List<UploadEntity>

    @Query("UPDATE pending_uploads SET state = :state, errorMessage = :error WHERE id = :id")
    suspend fun setState(id: String, state: String, error: String?)

    @Query("UPDATE pending_uploads SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: String, progress: Float)

    @Query("UPDATE pending_uploads SET attemptCount = attemptCount + 1 WHERE id = :id")
    suspend fun incrementAttempts(id: String)

    @Query("UPDATE pending_uploads SET remotePhotoId = :photoId WHERE id = :id")
    suspend fun setRemotePhotoId(id: String, photoId: String)

    @Query(
        """
        UPDATE pending_uploads
        SET state = 'QUEUED', errorMessage = NULL, attemptCount = 0
        WHERE id = :id AND state = 'FAILED'
        """
    )
    suspend fun requeue(id: String)

    @Query(
        """
        UPDATE pending_uploads
        SET state = 'QUEUED', errorMessage = NULL, attemptCount = 0
        WHERE ownerUid = :uid AND state = 'FAILED'
        """
    )
    suspend fun requeueAllFailed(uid: String)

    /**
     * An upload already in flight is left alone — the worker checks for cancellation
     * at its next checkpoint and cleans up after itself.
     */
    @Query("UPDATE pending_uploads SET state = 'CANCELLED' WHERE id = :id")
    suspend fun cancel(id: String)

    @Query("DELETE FROM pending_uploads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pending_uploads WHERE state = 'COMPLETED' OR state = 'CANCELLED'")
    suspend fun clearFinished()

    /**
     * Anything left UPLOADING at startup belongs to a worker the OS killed. Put it
     * back in the queue rather than leaving a spinner that never resolves.
     */
    @Query("UPDATE pending_uploads SET state = 'QUEUED', progress = 0 WHERE state = 'UPLOADING'")
    suspend fun recoverInterrupted()

    @Query("SELECT COUNT(*) FROM pending_uploads WHERE state = 'QUEUED' OR state = 'UPLOADING'")
    suspend fun countActive(): Int
}
