package com.rollapp.shared.di

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.rollapp.shared.BuildConfig
import com.rollapp.shared.data.storage.ImageStore
import com.rollapp.shared.data.storage.LocalImageStore
import com.rollapp.shared.data.storage.SupabaseImageStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // A 15 MB photo on a slow uplink can legitimately take a while.
        .writeTimeout(2, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * The emulator build stores images on disk; there is no Supabase emulator and the
     * Auth emulator's unsigned tokens would be rejected by a real Supabase project anyway.
     */
    @Provides
    @Singleton
    fun provideImageStore(
        @ApplicationContext context: Context,
        auth: FirebaseAuth,
        client: OkHttpClient
    ): ImageStore = if (BuildConfig.USE_FIREBASE_EMULATOR) {
        LocalImageStore(context)
    } else {
        check(BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()) {
            "SUPABASE_URL and SUPABASE_ANON_KEY must be set in local.properties (see README § Setup)"
        }
        SupabaseImageStore(
            baseUrl = BuildConfig.SUPABASE_URL,
            anonKey = BuildConfig.SUPABASE_ANON_KEY,
            bucket = BuildConfig.SUPABASE_BUCKET,
            auth = auth,
            client = client
        )
    }
}
