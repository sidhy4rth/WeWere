package com.rollapp.shared.data.remote

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Snapshot listeners as cold Flows. Errors are surfaced by closing the flow so the
 * collecting ViewModel can decide what to show; they are never swallowed.
 */

fun Query.snapshots(includeMetadata: Boolean = false): Flow<QuerySnapshot> = callbackFlow {
    val registration = addSnapshotListener(
        if (includeMetadata) MetadataChanges.INCLUDE else MetadataChanges.EXCLUDE
    ) { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}

fun DocumentReference.snapshots(includeMetadata: Boolean = false): Flow<DocumentSnapshot> = callbackFlow {
    val registration = addSnapshotListener(
        if (includeMetadata) MetadataChanges.INCLUDE else MetadataChanges.EXCLUDE
    ) { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}
