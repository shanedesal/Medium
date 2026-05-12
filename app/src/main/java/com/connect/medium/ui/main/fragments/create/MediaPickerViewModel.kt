package com.connect.medium.ui.main.fragments.create

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.connect.medium.data.model.GalleryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaPickerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val MAX_MEDIA_COUNT = 10
    }

    private val _galleryItems = MutableLiveData<List<GalleryItem>>(emptyList())
    val galleryItems: LiveData<List<GalleryItem>> = _galleryItems

    private val _previewItem = MutableLiveData<GalleryItem?>()
    val previewItem: LiveData<GalleryItem?> = _previewItem

    // Items that will be returned as the result (may contain cropped URIs substituted for originals)
    private val _selectedItems = MutableLiveData<List<GalleryItem>>(emptyList())
    val selectedItems: LiveData<List<GalleryItem>> = _selectedItems

    private val _isMultiSelectMode = MutableLiveData(false)
    val isMultiSelectMode: LiveData<Boolean> = _isMultiSelectMode

    // Tracks which original MediaStore URI is currently highlighted in the grid (single mode)
    private var highlightedOriginalUri: Uri? = null

    // Maintains insertion order for multi-select badge numbers; parallel to _selectedItems
    private val selectedOriginalUris = mutableListOf<Uri>()

    // Maps original MediaStore URI -> cropped file URI after the user crops an image
    private val croppedUriMap = mutableMapOf<Uri, Uri>()

    // Guards against re-scanning MediaStore on configuration changes (e.g. rotation).
    // The ViewModel survives rotation, so if items are already loaded there is no reason
    // to re-query; isLoadingGallery prevents a second concurrent scan on first open.
    private var isLoadingGallery = false

    fun loadGallery() {
        if (isLoadingGallery || _galleryItems.value?.isNotEmpty() == true) return
        isLoadingGallery = true
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { queryMediaStore() }
            _galleryItems.value = items
            isLoadingGallery = false
            // Auto-selection of first item is triggered from the fragment after submitList completes
        }
    }

    fun autoSelectFirstIfNone(items: List<GalleryItem>) {
        if (_previewItem.value == null && items.isNotEmpty()) {
            selectItem(items.first())
        }
    }

    fun selectItem(item: GalleryItem) {
        if (_isMultiSelectMode.value == true) {
            val idx = selectedOriginalUris.indexOf(item.uri)
            val current = _selectedItems.value.orEmpty().toMutableList()

            if (idx >= 0) {
                selectedOriginalUris.removeAt(idx)
                current.removeAt(idx)
                _selectedItems.value = current
                _previewItem.value = current.lastOrNull()
            } else if (selectedOriginalUris.size < MAX_MEDIA_COUNT) {
                selectedOriginalUris.add(item.uri)
                val resolved = croppedUriMap[item.uri]?.let { item.copy(uri = it) } ?: item
                current.add(resolved)
                _selectedItems.value = current
                _previewItem.value = resolved
            }
        } else {
            highlightedOriginalUri = item.uri
            val resolved = croppedUriMap[item.uri]?.let { item.copy(uri = it) } ?: item
            _selectedItems.value = listOf(resolved)
            _previewItem.value = resolved
        }
    }

    fun toggleMultiSelect() {
        val nowMulti = !(_isMultiSelectMode.value == true)
        _isMultiSelectMode.value = nowMulti

        if (nowMulti) {
            // Carry current single selection into multi mode
            selectedOriginalUris.clear()
            val currentItem = _selectedItems.value?.firstOrNull()
            if (currentItem != null) {
                // Use the original URI for the multi-list tracking
                val origUri = highlightedOriginalUri ?: currentItem.uri
                selectedOriginalUris.add(origUri)
            }
        } else {
            // Keep only the first item when returning to single mode
            val firstOrig = selectedOriginalUris.firstOrNull()
            selectedOriginalUris.clear()
            val first = _selectedItems.value?.firstOrNull()
            _selectedItems.value = if (first != null) listOf(first) else emptyList()
            _previewItem.value = first
            highlightedOriginalUri = firstOrig
        }
    }

    fun setCroppedUri(originalUri: Uri, croppedUri: Uri) {
        croppedUriMap[originalUri] = croppedUri
        val croppedItem = GalleryItem(uri = croppedUri, type = "image")
        _previewItem.value = croppedItem
        _selectedItems.value = listOf(croppedItem)
    }

    fun getSelectedForResult(): List<Pair<Uri, String>> =
        _selectedItems.value.orEmpty().map { Pair(it.uri, it.type) }

    fun isHighlighted(uri: Uri): Boolean = highlightedOriginalUri == uri

    fun getMultiSelectionIndex(uri: Uri): Int {
        val idx = selectedOriginalUris.indexOf(uri)
        return if (idx == -1) -1 else idx + 1
    }

    private fun queryMediaStore(): List<GalleryItem> {
        val context = getApplication<Application>()
        val items = mutableListOf<GalleryItem>()

        val imageProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val date = cursor.getLong(dateCol)
                items.add(
                    GalleryItem(
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        type = "image",
                        dateAdded = date
                    )
                )
            }
        }

        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_ADDED
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val date = cursor.getLong(dateCol)
                items.add(
                    GalleryItem(
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        type = "video",
                        dateAdded = date
                    )
                )
            }
        }

        items.sortByDescending { it.dateAdded }
        return items
    }
}
