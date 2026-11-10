package com.example

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.SystemClock
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.repository.EksiRepository
import com.example.di.ViewModelCacheCleaner
import com.example.network.WebViewFetchBridge
import com.example.util.PerfLog
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

@HiltAndroidApp
class EksinApplication : Application(), ComponentCallbacks2, ImageLoaderFactory {

    override fun onCreate() {
        val startMs = SystemClock.elapsedRealtime()
        appLaunchStartRealtimeMs = startMs
        super.onCreate()
        PerfLog.logDuration("Application.onCreate", SystemClock.elapsedRealtime() - startMs)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ) {
            clearMemoryCaches()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        clearMemoryCaches()
    }

    private fun clearMemoryCaches() {
        val entryPoint = EntryPointAccessors.fromApplication(this, CacheEntryPoint::class.java)
        entryPoint.repository().clearMemoryCaches()
        entryPoint.webViewFetchBridge().clearMemoryCaches()
        entryPoint.viewModelCacheCleaner().clear()
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
            .respectCacheHeaders(true)
            .build()
    }

    companion object {
        @Volatile
        var appLaunchStartRealtimeMs: Long = SystemClock.elapsedRealtime()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CacheEntryPoint {
    fun repository(): EksiRepository
    fun webViewFetchBridge(): WebViewFetchBridge
    fun viewModelCacheCleaner(): ViewModelCacheCleaner
}
