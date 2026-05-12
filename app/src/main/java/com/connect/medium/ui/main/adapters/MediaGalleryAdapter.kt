package com.connect.medium.ui.main.adapters

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.connect.medium.data.model.GalleryItem
import com.connect.medium.databinding.ItemGalleryMediaBinding

class MediaGalleryAdapter(
    private val onItemClick: (GalleryItem) -> Unit,
    private val getSelectionState: (Uri) -> SelectionState
) : ListAdapter<GalleryItem, MediaGalleryAdapter.GalleryViewHolder>(DIFF_CALLBACK) {

    sealed class SelectionState {
        object None : SelectionState()
        object SingleHighlighted : SelectionState()
        data class MultiSelected(val index: Int) : SelectionState()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
        val binding = ItemGalleryMediaBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        // parent.width (= getWidth()) is only valid after layout() has been called.
        // During RecyclerView's auto-measure phase it is still 0.  The fragment's
        // galleryItems observer guards against submitting items before isLaidOut, so
        // cellSize should always be positive here.  The explicit guard below is a
        // safety net: if cellSize were 0, skipping the LayoutParams assignment keeps
        // the default item size instead of producing 0×0 invisible cells that cause
        // GridLayoutManager to create ViewHolders for every item in the list.
        val cellSize = parent.width / SPAN_COUNT
        if (cellSize > 0) {
            binding.root.layoutParams = RecyclerView.LayoutParams(cellSize, cellSize)
        }
        return GalleryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class GalleryViewHolder(
        private val binding: ItemGalleryMediaBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GalleryItem) {
            Glide.with(binding.root)
                .load(item.uri)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(200, 200)
                .into(binding.ivThumbnail)

            binding.ivVideoIndicator.visibility =
                if (item.type == "video") View.VISIBLE else View.GONE

            when (val state = getSelectionState(item.uri)) {
                SelectionState.None -> {
                    binding.selectionOverlay.visibility = View.GONE
                    binding.tvSelectionNumber.visibility = View.GONE
                }
                SelectionState.SingleHighlighted -> {
                    binding.selectionOverlay.visibility = View.GONE
                    binding.tvSelectionNumber.visibility = View.VISIBLE
                    binding.tvSelectionNumber.text = "\u2713"  // ✓
                }
                is SelectionState.MultiSelected -> {
                    binding.selectionOverlay.visibility = View.VISIBLE
                    binding.tvSelectionNumber.visibility = View.VISIBLE
                    binding.tvSelectionNumber.text = state.index.toString()
                }
            }

            binding.root.setOnClickListener { onItemClick(item) }
        }
    }

    companion object {
        private const val SPAN_COUNT = 3

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<GalleryItem>() {
            override fun areItemsTheSame(oldItem: GalleryItem, newItem: GalleryItem) =
                oldItem.uri == newItem.uri

            override fun areContentsTheSame(oldItem: GalleryItem, newItem: GalleryItem) =
                oldItem == newItem
        }
    }
}
