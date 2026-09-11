package com.rollapp.shared.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.data.local.UploadDao
import com.rollapp.shared.data.upload.UploadScheduler
import com.rollapp.shared.domain.model.PendingUpload
import com.rollapp.shared.domain.repository.UploadQueueRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Singleton
class RoomUploadQueueRepository @Inject constructor(
    private val dao: UploadDao,
    private val auth: FirebaseAuth,
    private val scheduler: UploadScheduler
) : UploadQueueRepository {

    override fun observePending(): Flow<List<PendingUpload>> =
        authUid().flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList())
            else dao.observePending(uid).map { list -> list.map { it.toDomain() } }
        }

    override fun observePendingForGroup(groupId: String): Flow<List<PendingUpload>> =
        authUid().flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList())
            else dao.observePendingForGroup(uid, groupId).map { list -> list.map { it.toDomain() } }
        }

    override suspend fun retry(uploadId: String): Outcome<Unit> {
        dao.requeue(uploadId)
        scheduler.restartNow()
        return Outcome.Success(Unit)
    }

    override suspend fun retryAllFailed(): Outcome<Unit> {
        val uid = auth.currentUser?.uid ?: return Outcome.Failure(AppError.NotAuthenticated)
        dao.requeueAllFailed(uid)
        scheduler.restartNow()
        return Outcome.Success(Unit)
    }

    override suspend fun cancel(uploadId: String): Outcome<Unit> {
        dao.cancel(uploadId)
        return Outcome.Success(Unit)
    }

    override suspend fun clearCompleted(): Outcome<Unit> {
        dao.clearFinished()
        return Outcome.Success(Unit)
    }

    private fun authUid(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }
}
