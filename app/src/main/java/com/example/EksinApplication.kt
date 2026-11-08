package com.example

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.SystemClock
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.di.AppModule
import com.example.util.PerfLog
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class EksinApplication : Application(), ComponentCallbacks2, ImageLoaderFactory {

    override fun onCreate() {
        val startMs = SystemClock.elapsedRealtime()
        appLaunchStartRealtimeMs = startMs
        super.onCreate()
        // Cold start performansını korumak için AppModule.getContainer(this) burada ÇAĞRILMAZ.
        // Bağımlılıklar ilk erişimde tembel (lazy) olarak başlatılır.
        PerfLog.logDuration("Application.onCreate", SystemClock.elapsedRealtime() - startMs)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ) {
            AppModule.clearMemoryCachesIfInitialized()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppModule.clearMemoryCachesIfInitialized()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
    }

    companion object {
        @Volatile
        var appLaunchStartRealtimeMs: Long = SystemClock.elapsedRealtime()
    }
}
