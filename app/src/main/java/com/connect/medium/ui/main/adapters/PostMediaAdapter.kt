package com.connect.medium.ui.main.adapters

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.connect.medium.R
import com.connect.medium.databinding.ItemPostImageBinding
import com.connect.medium.databinding.ItemPostVideoBinding
import com.connect.medium.utils.VideoCache
import com.connect.medium.utils.toCloudinaryStreamingUrl


@UnstableApi
class PostMediaAdapter(
    val mediaUrls: List<String>,
    private val mediaTypes: List<String>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_IMAGE = 0
        private const val TYPE_VIDEO = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (mediaTypes.getOrNull(position) == "video") TYPE_VIDEO else TYPE_IMAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_VIDEO) {
            val binding = ItemPostVideoBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            VideoViewHolder(binding)
        } else {
            val binding = ItemPostImageBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            ImageViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val url = mediaUrls.getOrNull(position) ?: return
        when (holder) {
            is ImageViewHolder -> holder.bind(url)
            is VideoViewHolder -> holder.bind(url)
        }
    }

    override fun getItemCount() = mediaUrls.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)

        when (holder) {
            is VideoViewHolder -> holder.release()
            is ImageViewHolder -> holder.clear()
        }
        // Remove duplicate call
        // if (holder is VideoViewHolder) {
        //     holder.release()
        // }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (holder is VideoViewHolder) {
            holder.pause()
        }
    }

    class ImageViewHolder(
        private val binding: ItemPostImageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(url: String) {
            Glide.with(binding.root)
                .load(url)
                .centerCrop()
                .override(1080, 1920)
                .into(binding.ivPostImage)
        }
        fun clear() {
            try {
                val context = binding.root.context
                when (context) {
                    is Activity -> {
                        // Check if activity is still valid
                        if (!context.isDestroyed && !context.isFinishing) {
                            Glide.with(context as Context).clear(binding.ivPostImage)
                        }
                    } else -> {
                        // Fallback to application context
                        Glide.with(context.applicationContext).clear(binding.ivPostImage)
                    }
                }
            } catch (e: Exception) {
                // Log but don't crash - activity is likely destroyed
                e.printStackTrace()
            }
        }
    }

    class VideoViewHolder(
        private val binding: ItemPostVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var player: ExoPlayer? = null
        private var playerListener: Player.Listener? = null
        private var isMuted = false

        private fun createPlayer(context: Context): ExoPlayer {
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(VideoCache.get(context))
                .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            return ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            15000, // min buffer
                            30000, // max buffer
                            1000,  // playback buffer
                            2000   // rebuffer
                        )
                        .setTargetBufferBytes(10 * 1024 * 1024) // 10MB
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build()
                )
                .build()
        }

        fun bind(url: String) {
            release()

            isMuted = false
            binding.btnMute.setImageResource(R.drawable.ic_volume_on)
            binding.btnPlayPause.setImageResource(R.drawable.ic_play)

            // Use application context for ExoPlayer to avoid leaks
            player = createPlayer(binding.root.context).apply {
                setMediaItem(MediaItem.fromUri(url.toCloudinaryStreamingUrl()))
                repeatMode = ExoPlayer.REPEAT_MODE_OFF
                volume = 1f
                prepare()
                playWhenReady = false
            }

            playerListener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            if (player?.playWhenReady == true) {
                                binding.progressBuffering.visibility = View.VISIBLE
                            }
                        }
                        Player.STATE_READY -> binding.progressBuffering.visibility = View.GONE
                        Player.STATE_ENDED -> {
                            binding.progressBuffering.visibility = View.GONE
                            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                        }
                        else -> binding.progressBuffering.visibility = View.GONE
                    }
                }
            }
            player?.addListener(playerListener!!)

            binding.playerView.player = player

            // Clear previous listeners first
            binding.btnPlayPause.setOnClickListener(null)
            binding.btnMute.setOnClickListener(null)

            binding.btnPlayPause.setOnClickListener {
                player?.let { p ->
                    if (p.isPlaying) {
                        p.pause()
                        binding.progressBuffering.visibility = View.GONE
                        binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                    } else {
                        if (p.playbackState == Player.STATE_ENDED) {
                            p.seekTo(0)
                        }
                        p.play()
                        // If already buffering when play is pressed, the listener won't
                        // fire again (state didn't change), so show the spinner here.
                        if (p.playbackState == Player.STATE_BUFFERING) {
                            binding.progressBuffering.visibility = View.VISIBLE
                        }
                        binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                    }
                }
            }

            binding.btnMute.setOnClickListener {
                player?.let { p ->
                    isMuted = !isMuted
                    p.volume = if (isMuted) 0f else 1f
                    binding.btnMute.setImageResource(
                        if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on
                    )
                }
            }
        }

        fun pause() {
            player?.pause()
            binding.progressBuffering.visibility = View.GONE
            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
        }

        fun release() {
            binding.btnPlayPause.setOnClickListener(null)
            binding.btnMute.setOnClickListener(null)
            binding.progressBuffering.visibility = View.GONE
            binding.playerView.player = null

            player?.let { p ->
                try {
                    playerListener?.let { p.removeListener(it) }
                    p.stop()
                    p.clearMediaItems()
                    p.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            playerListener = null
            player = null
        }
    }
}