package com.rollapp.shared.di

import com.rollapp.shared.data.repository.FirebaseAuthRepository
import com.rollapp.shared.data.repository.FirestoreGroupRepository
import com.rollapp.shared.data.repository.FirestorePhotoRepository
import com.rollapp.shared.data.repository.FirestoreUserRepository
import com.rollapp.shared.data.repository.RoomUploadQueueRepository
import com.rollapp.shared.domain.repository.AuthRepository
import com.rollapp.shared.domain.repository.GroupRepository
import com.rollapp.shared.domain.repository.PhotoRepository
import com.rollapp.shared.domain.repository.UploadQueueRepository
import com.rollapp.shared.domain.repository.UserRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The single place Firebase is named as the backend. Everything above this line
 * depends on the interfaces in `domain.repository`, so swapping in a different
 * backend means writing new implementations and editing this file — not the UI.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: FirebaseAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: FirestoreUserRepository): UserRepository

    @Binds
    @Singleton
    abstract fun bindGroupRepository(impl: FirestoreGroupRepository): GroupRepository

    @Binds
    @Singleton
    abstract fun bindPhotoRepository(impl: FirestorePhotoRepository): PhotoRepository

    @Binds
    @Singleton
    abstract fun bindUploadQueueRepository(impl: RoomUploadQueueRepository): UploadQueueRepository
}
