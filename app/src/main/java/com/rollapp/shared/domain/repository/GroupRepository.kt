package com.rollapp.shared.domain.repository

import android.net.Uri
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.ActivityEvent
import com.rollapp.shared.domain.model.Group
import com.rollapp.shared.domain.model.GroupPreview
import com.rollapp.shared.domain.model.Member
import kotlinx.coroutines.flow.Flow

interface GroupRepository {
    /** Live list of groups the signed-in user belongs to, most recent activity first. */
    fun observeMyGroups(): Flow<List<Group>>

    fun observeGroup(groupId: String): Flow<Group?>
    fun observeMembers(groupId: String): Flow<List<Member>>

    /** Emits false the moment this user stops being a member (e.g. removed by an admin). */
    fun observeMembership(groupId: String): Flow<Member?>

    fun observeActivity(limit: Int = 50): Flow<List<ActivityEvent>>

    suspend fun createGroup(name: String, description: String?, coverUri: Uri?): Outcome<Group>
    suspend fun previewByInviteCode(code: String): Outcome<GroupPreview>
    suspend fun joinByInviteCode(code: String): Outcome<Group>
    suspend fun leaveGroup(groupId: String): Outcome<Unit>

    suspend fun updateGroup(groupId: String, name: String?, description: String?): Outcome<Unit>
    suspend fun updateCoverPhoto(groupId: String, uri: Uri): Outcome<String>
    suspend fun removeMember(groupId: String, uid: String): Outcome<Unit>
    suspend fun regenerateInviteCode(groupId: String, expiresAt: Long?): Outcome<String>
    suspend fun revokeInvite(groupId: String): Outcome<Unit>
    suspend fun deleteGroup(groupId: String): Outcome<Unit>
}
