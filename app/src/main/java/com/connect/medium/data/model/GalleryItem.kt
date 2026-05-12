package com.connect.medium.data.model

import android.net.Uri

data class GalleryItem(
    val uri: Uri,
    val type: String,        // "image" or "video"
    val dateAdded: Long = 0L
)
