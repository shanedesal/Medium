package com.connect.medium.utils

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
object VideoCache {

    private const val CACHE_DIR_NAME = "exo_video_cache"
    private const val CACHE_SIZE_BYTES = 100L * 1024L * 1024L // 100 MB

    @Volatile private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        return cache ?: synchronized(this) {
            cache ?: buildCache(context.applicationContext).also { cache = it }
        }
    }

    fun release() {
        synchronized(this) {
            cache?.release()
            cache = null
        }
    }

    private fun buildCache(context: Context): SimpleCache {
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        return try {
            SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
                StandaloneDatabaseProvider(context)
            )
        } catch (e: Exception) {
            // Cache directory corrupted (e.g. crash mid-write). Clear and recreate so video
            // playback does not remain broken until the user manually clears app data.
            cacheDir.deleteRecursively()
            SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
                StandaloneDatabaseProvider(context)
            )
        }
    }
}
