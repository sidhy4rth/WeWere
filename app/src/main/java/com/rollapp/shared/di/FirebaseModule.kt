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
import com.rollapp.shared.BuildConfig
import com.rollapp.shared.data.local.RollDatabase
import com.rollapp.shared.data.local.UploadDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun provideAuth(): FirebaseAuth = Firebase.auth.apply {
        if (BuildConfig.USE_FIREBASE_EMULATOR) {
            useEmulator(BuildConfig.EMULATOR_HOST, AUTH_EMULATOR_PORT)
        }
    }

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = Firebase.firestore.apply {
        // Offline persistence is what makes the app usable on a patchy hotel wifi:
        // cached photo documents render immediately and writes queue locally.
        //
        // The emulator host goes into the same settings object rather than through
        // useEmulator(), because Firestore refuses a settings change once the
        // instance has been used and the two calls would race.
        firestoreSettings = FirebaseFirestoreSettings.Builder()
            .apply {
                if (BuildConfig.USE_FIREBASE_EMULATOR) {
                    setHost("${BuildConfig.EMULATOR_HOST}:$FIRESTORE_EMULATOR_PORT")
                    setSslEnabled(false)
                }
            }
            .setLocalCacheSettings(
                PersistentCacheSettings.newBuilder()
                    .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .build()
            )
            .build()
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

    /** Defaults from `firebase.json`. 10.0.2.2 is the host loopback seen from an emulator. */
    private const val AUTH_EMULATOR_PORT = 9099
    private const val FIRESTORE_EMULATOR_PORT = 8080
}
