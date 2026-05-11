package com.connect.medium.ui.main.adapters

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.connect.medium.R
import com.connect.medium.data.model.Comment
import com.connect.medium.databinding.ItemCommentBinding

class CommentAdapter : RecyclerView.Adapter<CommentAdapter.CommentViewHolder>() {

    private var comments = listOf<Comment>()

    fun submitList(newComments: List<Comment>) {
        val diff = DiffUtil.calculateDiff(CommentDiffCallback(comments, newComments))
        comments = newComments
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val binding = ItemCommentBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CommentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        holder.bind(comments[position])
    }

    override fun getItemCount() = comments.size

    inner class CommentViewHolder(
        private val binding: ItemCommentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(comment: Comment) {
            binding.tvUsername.text = comment.authorUsername
            binding.tvComment.text = comment.text
            binding.tvTimestamp.text = getRelativeTime(comment.createdAt)

            if (comment.authorProfileImageUrl.isNotEmpty()) {
                Glide.with(binding.root)
                    .load(comment.authorProfileImageUrl)
                    .placeholder(R.drawable.ic_profile)
                    .circleCrop()
                    .into(binding.ivProfileImage)
            } else {
                binding.ivProfileImage.setImageResource(R.drawable.ic_profile)
            }
            Log.d("CommentAdapter", "Binding comment: ${comment.text} by ${comment.authorUsername} with profile url: ${comment.authorProfileImageUrl}")

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
}

class CommentDiffCallback(
    private val old: List<Comment>,
    private val new: List<Comment>
) : DiffUtil.Callback() {
    override fun getOldListSize() = old.size
    override fun getNewListSize() = new.size
    override fun areItemsTheSame(o: Int, n: Int) = old[o].commentId == new[n].commentId
    override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
}