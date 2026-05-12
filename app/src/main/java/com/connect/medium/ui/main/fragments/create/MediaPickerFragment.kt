package com.connect.medium.ui.main.fragments.create

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.connect.medium.databinding.FragmentMediaPickerBinding
import com.connect.medium.ui.main.adapters.MediaGalleryAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MediaPickerFragment : Fragment() {

    companion object {
        const val RESULT_KEY = "media_picker_result"
        const val ARG_URIS = "selected_uris"
        const val ARG_TYPES = "selected_types"

        private const val GRID_SPAN_COUNT = 3
        private const val GRID_SPACING_DP = 2
        private const val KEY_PENDING_CROP_URI = "key_pending_crop_uri"

        // Fixed decode size for the preview image. Using a constant rather than the
        // ImageView's live dimensions keeps Glide's disk-cache key stable across
        // configuration changes, so the transformed bitmap is served from cache on
        // rotation instead of being re-decoded from the MediaStore URI.
        private const val PREVIEW_SIZE_PX = 1080
    }

    private var _binding: FragmentMediaPickerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MediaPickerViewModel by viewModels {
        MediaPickerViewModelFactory(requireActivity().application)
    }

    private lateinit var galleryAdapter: MediaGalleryAdapter

    // Original URI of the item sent to CropImageActivity; used to map the result back
    private var pendingCropOriginalUri: Uri? = null

    @Suppress("DEPRECATION") // CropImageContract is deprecated but functional; recommended
    // alternative is to embed CropImageView directly, which is out of scope for this feature.
    private val cropImageLauncher = registerForActivityResult(CropImageContract()) { result ->
        val originalUri = pendingCropOriginalUri
        pendingCropOriginalUri = null
        if (!result.isSuccessful) return@registerForActivityResult
        val croppedUri = result.uriContent ?: return@registerForActivityResult
        if (originalUri != null) {
            viewModel.setCroppedUri(originalUri, croppedUri)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            showGallery()
        } else {
            showPermissionDeniedUi()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) {
            pendingCropOriginalUri = BundleCompat.getParcelable(savedInstanceState, KEY_PENDING_CROP_URI, Uri::class.java)
        }
        setupGalleryGrid()
        setupClickListeners()
        observeViewModel()
        checkPermissionsAndLoad()
    }

    // ─────────────────────────── Setup ───────────────────────────

    private fun setupGalleryGrid() {
        galleryAdapter = MediaGalleryAdapter(
            onItemClick = { item -> viewModel.selectItem(item) },
            getSelectionState = { uri ->
                val isMulti = viewModel.isMultiSelectMode.value == true
                if (isMulti) {
                    val idx = viewModel.getMultiSelectionIndex(uri)
                    if (idx > 0) MediaGalleryAdapter.SelectionState.MultiSelected(idx)
                    else MediaGalleryAdapter.SelectionState.None
                } else {
                    if (viewModel.isHighlighted(uri)) MediaGalleryAdapter.SelectionState.SingleHighlighted
                    else MediaGalleryAdapter.SelectionState.None
                }
            }
        )

        val spacingPx = (GRID_SPACING_DP * resources.displayMetrics.density).toInt()
        binding.rvGallery.apply {
            layoutManager = GridLayoutManager(requireContext(), GRID_SPAN_COUNT)
            adapter = galleryAdapter
            setHasFixedSize(true)
            addItemDecoration(GridSpacingDecoration(GRID_SPAN_COUNT, spacingPx))
        }
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnNext.setOnClickListener {
            val selected = viewModel.getSelectedForResult()
            if (selected.isEmpty()) return@setOnClickListener
            val bundle = Bundle().apply {
                putParcelableArrayList(ARG_URIS, ArrayList(selected.map { it.first }))
                putStringArrayList(ARG_TYPES, ArrayList(selected.map { it.second }))
            }
            setFragmentResult(RESULT_KEY, bundle)
            findNavController().popBackStack()
        }

        @Suppress("DEPRECATION")
        binding.btnCrop.setOnClickListener {
            val preview = viewModel.previewItem.value ?: return@setOnClickListener
            if (preview.type != "image") return@setOnClickListener
            pendingCropOriginalUri = preview.uri
            cropImageLauncher.launch(
                CropImageContractOptions(
                    uri = preview.uri,
                    cropImageOptions = CropImageOptions(
                        guidelines = CropImageView.Guidelines.ON,
                        fixAspectRatio = true,
                        aspectRatioX = 1,
                        aspectRatioY = 1,
                        outputCompressQuality = 90,
                        imageSourceIncludeCamera = false,
                        imageSourceIncludeGallery = false
                    )
                )
            )
        }

        binding.btnMultiSelect.setOnClickListener {
            viewModel.toggleMultiSelect()
        }

        binding.btnGrantPermission.setOnClickListener {
            checkPermissionsAndLoad()
        }
    }

    // ─────────────────────────── Observers ───────────────────────────

    private fun observeViewModel() {
        viewModel.galleryItems.observe(viewLifecycleOwner) { items ->
            // submitList must only run after rv_gallery has been through at least one layout
            // pass. If it fires during RecyclerView's auto-measure phase (inside onMeasure,
            // before layout()), parent.getWidth() = 0 in onCreateViewHolder → cellSize = 0 →
            // GridLayoutManager creates ViewHolders for ALL items → ANR.
            val commit = {
                galleryAdapter.submitList(items) {
                    viewModel.autoSelectFirstIfNone(items)
                }
            }
            if (binding.rvGallery.isLaidOut) {
                commit()
            } else {
                binding.rvGallery.doOnLayout { commit() }
            }
        }

        viewModel.previewItem.observe(viewLifecycleOwner) { item ->
            if (item != null) {
                binding.ivPreview.visibility = View.VISIBLE
                binding.tvEmptyPreview.visibility = View.GONE
                Glide.with(this)
                    .load(item.uri)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(PREVIEW_SIZE_PX, PREVIEW_SIZE_PX)
                    .transition(DrawableTransitionOptions.withCrossFade(150))
                    .into(binding.ivPreview)
                val showCrop = item.type == "image" && viewModel.isMultiSelectMode.value != true
                binding.btnCrop.visibility = if (showCrop) View.VISIBLE else View.GONE
            } else {
                binding.ivPreview.visibility = View.INVISIBLE
                binding.tvEmptyPreview.visibility = View.VISIBLE
                binding.btnCrop.visibility = View.GONE
            }
        }

        viewModel.selectedItems.observe(viewLifecycleOwner) { items ->
            galleryAdapter.notifyDataSetChanged()
            binding.btnNext.isEnabled = items.isNotEmpty()
            binding.btnNext.text = if (items.size > 1) "Next (${items.size})" else "Next"
        }

        viewModel.isMultiSelectMode.observe(viewLifecycleOwner) { isMulti ->
            binding.btnMultiSelect.text = if (isMulti) "Single" else "Multiple"
            val preview = viewModel.previewItem.value
            val showCrop = !isMulti && preview?.type == "image"
            binding.btnCrop.visibility = if (showCrop) View.VISIBLE else View.GONE
            galleryAdapter.notifyDataSetChanged()
        }
    }

    // ─────────────────────────── Permissions ───────────────────────────

    private fun checkPermissionsAndLoad() {
        val permissions = getRequiredPermissions()
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
        when {
            allGranted -> showGallery()
            permissions.any { shouldShowRequestPermissionRationale(it) } -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Media Access")
                    .setMessage("Allow access to your photos and videos to choose content for your post.")
                    .setPositiveButton("Allow") { _, _ -> permissionLauncher.launch(permissions) }
                    .setNegativeButton("Not now", null)
                    .show()
            }
            else -> permissionLauncher.launch(permissions)
        }
    }

    private fun getRequiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun showGallery() {
        binding.permissionLayout.visibility = View.GONE
        binding.galleryLayout.visibility = View.VISIBLE
        viewModel.loadGallery()
    }

    private fun showPermissionDeniedUi() {
        binding.permissionLayout.visibility = View.VISIBLE
        binding.galleryLayout.visibility = View.GONE
        // shouldShowRationale returns false after "Don't ask again" is selected
        val permanentlyDenied = getRequiredPermissions().none { shouldShowRequestPermissionRationale(it) }
        if (permanentlyDenied) {
            binding.btnGrantPermission.text = "Open Settings"
            binding.btnGrantPermission.setOnClickListener { openAppSettings() }
        } else {
            binding.btnGrantPermission.text = "Allow Access"
            binding.btnGrantPermission.setOnClickListener { checkPermissionsAndLoad() }
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            }
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCropOriginalUri?.let { outState.putParcelable(KEY_PENDING_CROP_URI, it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─────────────────────────── Inner helpers ───────────────────────────

    private class GridSpacingDecoration(
        private val spanCount: Int,
        private val spacing: Int
    ) : RecyclerView.ItemDecoration() {

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            val column = position % spanCount
            outRect.left = column * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / spanCount
            if (position >= spanCount) outRect.top = spacing
        }
    }
}
