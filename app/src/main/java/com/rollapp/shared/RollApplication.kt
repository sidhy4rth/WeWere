package com.rollapp.shared

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.rollapp.shared.data.upload.UploadScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RollApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var uploadScheduler: UploadScheduler

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Anything left in the queue from a previous run resumes on launch.
        uploadScheduler.ensureRunning()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * A shared camera roll is almost entirely images, so the loader is tuned wider
     * than Coil's defaults: a generous disk cache means scrolling back through a trip
     * costs nothing, and 25% of available heap keeps a fast swipe through the carousel
     * from re-decoding neighbours that were on screen a second ago.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                getString(R.string.notification_channel_photos),
                getString(R.string.notification_channel_photos_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                getString(R.string.notification_channel_activity),
                getString(R.string.notification_channel_activity_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                getString(R.string.notification_channel_uploads),
                getString(R.string.notification_channel_uploads_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }
}
