package com.connect.medium.ui.main.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.connect.medium.R
import com.connect.medium.data.model.Post
import com.connect.medium.databinding.ItemPostBinding
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions


@UnstableApi
class PostAdapter(
    private val onLikeClick: (Post) -> Unit,
    private val onCommentClick: (Post) -> Unit,
    private val onProfileClick: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var posts = listOf<Post>()
    private var likedPosts = mutableSetOf<String>()
    private var commentCountDeltas = mapOf<String, Int>()
    private var isLoadingMore = false


    init {
        setHasStableIds(false)
    }

    fun submitList(newPosts: List<Post>) {
        val diff = DiffUtil.calculateDiff(PostDiffCallback(posts, newPosts))
        posts = newPosts
        diff.dispatchUpdatesTo(this)
    }

    fun setLikedPosts(liked: Set<String>) {
        val oldLiked = likedPosts.toSet()
        likedPosts = liked.toMutableSet()
        posts.forEachIndexed { index, post ->
            if (oldLiked.contains(post.postId) != liked.contains(post.postId)) {
                notifyItemChanged(index + 1) // +1 for header
            }
        }
    }

    fun updateLike(postId: String, isLiked: Boolean) {
        if (isLiked) likedPosts.add(postId) else likedPosts.remove(postId)
        val index = posts.indexOfFirst { it.postId == postId }
        if (index != -1) notifyItemChanged(index + 1)
    }

    fun setCommentCountDeltas(deltas: Map<String, Int>) {
        commentCountDeltas = deltas
        notifyDataSetChanged()
    }

    fun setLoadingMore(loading: Boolean) {
        if (isLoadingMore == loading) return
        isLoadingMore = loading
        if (loading) {
            // Insert footer at the end
            notifyItemInserted(itemCount - 1)
        } else {
            // Remove footer
            notifyItemRemoved(itemCount)
        }
    }



    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_feed_header, parent, false)
                HeaderViewHolder(view)
            }
            VIEW_TYPE_LOADING -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_loading_footer, parent, false)
                LoadingViewHolder(view)
            }
            else -> {
                val binding = ItemPostBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                PostViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is PostViewHolder -> {
                val post = posts[position - 1] // -1 for header
                holder.bind(post, likedPosts)
            }
            is HeaderViewHolder -> {}
            is LoadingViewHolder -> {}
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when {
            position == 0 -> VIEW_TYPE_HEADER
            position == posts.size + 1 && isLoadingMore -> VIEW_TYPE_LOADING
            else -> VIEW_TYPE_POST
        }
    }

    override fun getItemCount() = posts.size + 1 + if (isLoadingMore) 1 else 0

    // release all players when post is recycled
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is PostViewHolder) {

            holder.pageChangeCallback?.let {
                holder.binding.viewPagerMedia.unregisterOnPageChangeCallback(it)
                holder.isCallbackRegistered = false
            }
            holder.pageChangeCallback = null

            val rv = holder.binding.viewPagerMedia.getChildAt(0) as? RecyclerView
            rv?.let {
                for (i in 0 until it.childCount) {
                    val mediaHolder = it.getChildViewHolder(it.getChildAt(i))
                    if (mediaHolder is PostMediaAdapter.VideoViewHolder) {
                        mediaHolder.release()
                    }
                }
            }
            holder.binding.viewPagerMedia.adapter = null
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.setItemViewCacheSize(5) // Limit cached views
        recyclerView.recycledViewPool.setMaxRecycledViews(0, 10) // Limit VIDEO holders
        recyclerView.recycledViewPool.setMaxRecycledViews(1, 20) // Limit IMAGE holders
    }



    inner class PostViewHolder(
        val binding: ItemPostBinding  // changed to val so onViewRecycled can access it
    ) : RecyclerView.ViewHolder(binding.root) {

        var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
        var isCallbackRegistered = false


        fun bind(post: Post, currentLikedPosts: Set<String> = emptySet()) {
            binding.tvUsername.text = post.authorUsername
            binding.tvTimestamp.text = getRelativeTime(post.createdAt)

            val isLiked = currentLikedPosts.contains(post.postId)
            val captionText = android.text.SpannableStringBuilder()
            val boldSpan = android.text.style.StyleSpan(android.graphics.Typeface.BOLD)
            captionText.append(post.authorUsername)
            captionText.setSpan(boldSpan, 0, post.authorUsername.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (post.caption.isNotEmpty()) {
                captionText.append("  ${post.caption}")
            }
            binding.tvCaption.text = captionText

            val displayLikeCount = post.likeCount

            // like count
            binding.tvLikeCount.text = when (displayLikeCount) {
                0 -> "Be the first to like this"
                1 -> "1 like"
                else -> "$displayLikeCount likes"
            }

            // comment count
            val displayCommentCount = post.commentCount + (commentCountDeltas[post.postId] ?: 0)
            binding.tvCommentCount.text = when {
                displayCommentCount == 0 -> ""
                displayCommentCount == 1 -> "View 1 comment"
                else -> "View all $displayCommentCount comments"
            }
            binding.tvCommentCount.setOnClickListener { onCommentClick(post) }

            if (post.authorProfileImageUrl.isNotEmpty()) {
                binding.ivProfileImage.visibility = View.INVISIBLE // hide until loaded
                Glide.with(binding.root)
                    .load(post.authorProfileImageUrl)
                    .placeholder(R.drawable.ic_profile)
                    .error(R.drawable.ic_profile)
                    .circleCrop()
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                        override fun onResourceReady(
                            resource: android.graphics.drawable.Drawable,
                            model: Any,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                            dataSource: com.bumptech.glide.load.DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            binding.ivProfileImage.visibility = View.VISIBLE
                            return false // let Glide handle setting the image
                        }
                        override fun onLoadFailed(
                            e: com.bumptech.glide.load.engine.GlideException?,
                            model: Any?,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            binding.ivProfileImage.visibility = View.VISIBLE
                            return false // let Glide show the error drawable
                        }
                    })
                    .into(binding.ivProfileImage)
            } else {
                binding.ivProfileImage.visibility = View.VISIBLE
                binding.ivProfileImage.setImageResource(R.drawable.ic_profile)
            }

            binding.viewPagerMedia.offscreenPageLimit = 1

            if (isCallbackRegistered) {
                pageChangeCallback?.let {
                    binding.viewPagerMedia.unregisterOnPageChangeCallback(it)
                }
                isCallbackRegistered = false
            }
            pageChangeCallback = null

            val currentAdapter = binding.viewPagerMedia.adapter as? PostMediaAdapter
            if (currentAdapter == null || currentAdapter.mediaUrls != post.mediaUrls) {
                currentAdapter?.let {
                    val rv = binding.viewPagerMedia.getChildAt(0) as? RecyclerView
                    rv?.let {
                        for (i in 0 until it.childCount) {
                            when (val holder = it.getChildViewHolder(it.getChildAt(i))) {
                                is PostMediaAdapter.VideoViewHolder -> holder.release()
                                is PostMediaAdapter.ImageViewHolder -> holder.clear()
                            }
                        }
                    }
                }
                binding.viewPagerMedia.adapter = PostMediaAdapter(post.mediaUrls, post.mediaTypes)
            }

            val callback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    val rv = binding.viewPagerMedia.getChildAt(0) as? RecyclerView
                    rv?.let {
                        for (i in 0 until it.childCount) {
                            val holder = it.getChildViewHolder(it.getChildAt(i))
                            if (holder is PostMediaAdapter.VideoViewHolder) holder.pause()
                        }
                    }
                }
            }
            pageChangeCallback = callback
            binding.viewPagerMedia.registerOnPageChangeCallback(callback)
            isCallbackRegistered = true

            if (post.mediaUrls.size > 1) {
                binding.dotsIndicator.visibility = View.VISIBLE
                binding.dotsIndicator.attachTo(binding.viewPagerMedia)
            } else {
                binding.dotsIndicator.visibility = View.GONE
            }

            // like button state — use ImageButton now
            binding.btnLike.setImageResource(
                if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
            )

            binding.btnLike.setOnClickListener { onLikeClick(post) }
            binding.btnComment.setOnClickListener { onCommentClick(post) }
            binding.ivProfileImage.setOnClickListener { onProfileClick(post.authorUid) }
            binding.tvUsername.setOnClickListener { onProfileClick(post.authorUid) }
            binding.btnMore.setOnClickListener {
                // TODO: show post options menu (delete, report, etc.)
            }
        }


        private fun getRelativeTime(timestamp: Long): String {
            val diff = System.currentTimeMillis() - timestamp
            return when {
                diff < 60_000 -> "just now"
                diff < 3_600_000 -> "${diff / 60_000}m ago"
                diff < 86_400_000 -> "${diff / 3_600_000}h ago"
                else -> "${diff / 86_400_000}d ago"
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_POST = 1
        private const val VIEW_TYPE_LOADING = 2
    }
}

class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view)
class LoadingViewHolder(view: View) : RecyclerView.ViewHolder(view)

class PostDiffCallback(
    private val oldList: List<Post>,
    private val newList: List<Post>
) : DiffUtil.Callback() {
    override fun getOldListSize() = oldList.size
    override fun getNewListSize() = newList.size

    override fun areItemsTheSame(old: Int, new: Int): Boolean {
        return oldList[old].postId == newList[new].postId
    }

    override fun areContentsTheSame(old: Int, new: Int): Boolean {
        return oldList[old] == newList[new]
    }
}