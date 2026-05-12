package com.connect.medium

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.MemoryCategory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.connect.medium.utils.ThemePreferences
import com.connect.medium.utils.VideoCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ConnectApplication : Application() {

    override fun onCreate() {
        super.onCreate()

//        val scope = CoroutineScope(Dispatchers.Main)
//        scope.launch {
//            ThemePreferences.getDarkMode(this@ConnectApplication)
//                .first()
//                .let { isDark ->
//                    AppCompatDelegate.setDefaultNightMode(
//                        if (isDark) AppCompatDelegate.MODE_NIGHT_YES
//                        else AppCompatDelegate.MODE_NIGHT_NO
//                    )
//                }
//        }

        val isDark = runBlocking(Dispatchers.IO) {
            ThemePreferences.getDarkMode(this@ConnectApplication).first()
        }

        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        ExoPlayer.Builder(this)
        val glideBuilder = GlideBuilder()
        glideBuilder.setMemoryCache(LruResourceCache(10 * 1024 * 1024)) // 10MB max
        Glide.get(this).apply{
            setMemoryCategory(MemoryCategory.LOW)
        }
    }

    @UnstableApi
    override fun onLowMemory() {
        super.onLowMemory()
        VideoCache.release()
    }
}