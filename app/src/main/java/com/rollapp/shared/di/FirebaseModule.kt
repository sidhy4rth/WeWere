package com.rollapp.shared.di

import android.content.Context
import androidx.room.Room
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.ktx.messaging
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storage
import com.rollapp.shared.data.local.RollDatabase
import com.rollapp.shared.data.local.UploadDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun provideAuth(): FirebaseAuth = Firebase.auth

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = Firebase.firestore.apply {
        // Offline persistence is what makes the app usable on a patchy hotel wifi:
        // cached photo documents render immediately and writes queue locally.
        firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(
                PersistentCacheSettings.newBuilder()
                    .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .build()
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideStorage(): FirebaseStorage = Firebase.storage.apply {
        maxUploadRetryTimeMillis = TimeUnit.MINUTES.toMillis(2)
        maxDownloadRetryTimeMillis = TimeUnit.MINUTES.toMillis(2)
        maxOperationRetryTimeMillis = TimeUnit.SECONDS.toMillis(30)
    }

    @Provides
    @Singleton
    fun provideMessaging(): FirebaseMessaging = Firebase.messaging

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RollDatabase =
        Room.databaseBuilder(context, RollDatabase::class.java, RollDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideUploadDao(database: RollDatabase): UploadDao = database.uploadDao()
}
