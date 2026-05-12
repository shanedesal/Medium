package com.connect.medium.ui.main.fragments.create

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.connect.medium.R
import com.connect.medium.databinding.FragmentCreatePostBinding
import com.connect.medium.ui.main.adapters.MediaPreviewAdapter
import com.connect.medium.utils.Resource
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class CreatePostFragment : Fragment() {

    companion object {
        private const val VIDEO_SIZE_LIMIT_BYTES = 50 * 1024 * 1024L
        private const val KEY_CAMERA_IMAGE_URI = "key_camera_image_uri"
        private const val KEY_CAMERA_VIDEO_URI = "key_camera_video_uri"
        private const val KEY_SELECTED_URIS = "key_selected_uris"
        private const val KEY_SELECTED_TYPES = "key_selected_types"
        private const val KEY_TOOLBAR_EXPANDED = "key_toolbar_expanded"
    }

    private var _binding: FragmentCreatePostBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CreatePostViewModel by viewModels {
        CreatePostViewModelFactory(requireActivity().application)
    }

    private val selectedMedia = mutableListOf<Pair<Uri, String>>()
    private lateinit var mediaPreviewAdapter: MediaPreviewAdapter

    private var isToolbarExpanded = false
    private var cameraImageUri: Uri? = null
    private var cameraVideoUri: Uri? = null

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraImageUri?.let { uri ->
                selectedMedia.add(Pair(uri, "image"))
                mediaPreviewAdapter.submitList(selectedMedia.toList())
                updateMediaCount()
            }
        }
    }

    private val takeVideoLauncher = registerForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { success ->
        if (!success) return@registerForActivityResult
        cameraVideoUri?.let { uri ->
            val fileSize = requireContext().contentResolver
                .openFileDescriptor(uri, "r")?.statSize ?: 0
            if (fileSize > VIDEO_SIZE_LIMIT_BYTES) {
                Toast.makeText(
                    requireContext(),
                    "Video exceeds 50MB limit: ${fileSize / (1024 * 1024)}MB",
                    Toast.LENGTH_SHORT
                ).show()
                return@registerForActivityResult
            }
            selectedMedia.add(Pair(uri, "video"))
            mediaPreviewAdapter.submitList(selectedMedia.toList())
            updateMediaCount()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            showCameraOptions()
        } else {
            Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreatePostBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) {
            cameraImageUri = BundleCompat.getParcelable(savedInstanceState, KEY_CAMERA_IMAGE_URI, Uri::class.java)
            cameraVideoUri = BundleCompat.getParcelable(savedInstanceState, KEY_CAMERA_VIDEO_URI, Uri::class.java)
        }
        setupMediaPreview()
        setupClickListeners()
        observeViewModel()
        listenForPickerResult()
        if (savedInstanceState != null) restoreState(savedInstanceState)
    }

    // ─────────────────────────── Setup ───────────────────────────

    private fun setupMediaPreview() {
        mediaPreviewAdapter = MediaPreviewAdapter { position ->
            selectedMedia.removeAt(position)
            mediaPreviewAdapter.submitList(selectedMedia.toList())
            updateMediaCount()
        }

        binding.rvMediaPreview.apply {
            layoutManager = LinearLayoutManager(
                requireContext(), LinearLayoutManager.HORIZONTAL, false
            )
            adapter = mediaPreviewAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnClose.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnPickMedia.setOnClickListener {
            findNavController().navigate(R.id.action_createPost_to_mediaPicker)
        }

        binding.btnCamera.setOnClickListener {
            checkCameraPermissionAndOpen()
        }

        binding.btnExpand.setOnClickListener {
            toggleToolbar()
        }

        binding.btnPost.setOnClickListener {
            val caption = binding.etCaption.text.toString().trim()
            if (selectedMedia.isEmpty()) {
                Toast.makeText(requireContext(), "Please select media", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.createPost(selectedMedia.toList(), caption)
        }
    }

    // ─────────────────────────── Gallery picker result ───────────────────────────

    private fun listenForPickerResult() {
        setFragmentResultListener(MediaPickerFragment.RESULT_KEY) { _, bundle ->
            val uris = BundleCompat.getParcelableArrayList(
                bundle, MediaPickerFragment.ARG_URIS, Uri::class.java
            ) ?: return@setFragmentResultListener
            val types = bundle.getStringArrayList(MediaPickerFragment.ARG_TYPES)
                ?: return@setFragmentResultListener

            selectedMedia.clear()

            uris.forEachIndexed { index, uri ->
                val type = types.getOrNull(index) ?: return@forEachIndexed
                if (type == "video") {
                    val fileSize = runCatching {
                        requireContext().contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0L
                    }.getOrDefault(0L)
                    if (fileSize > VIDEO_SIZE_LIMIT_BYTES) {
                        Toast.makeText(
                            requireContext(),
                            "Video exceeds 50MB limit: ${fileSize / (1024 * 1024)}MB",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@forEachIndexed
                    }
                }
                selectedMedia.add(Pair(uri, type))
            }

            mediaPreviewAdapter.submitList(selectedMedia.toList())
            updateMediaCount()
        }
    }

    // ─────────────────────────── Camera ───────────────────────────

    private fun checkCameraPermissionAndOpen() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> showCameraOptions()

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Camera Permission")
                    .setMessage("Camera access is needed to take photos and videos for your post.")
                    .setPositiveButton("Grant") { _, _ ->
                        cameraPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO
                            )
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            else -> cameraPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    private fun showCameraOptions() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Camera")
            .setItems(arrayOf("Take Photo", "Record Video")) { _, which ->
                when (which) {
                    0 -> launchCameraPhoto()
                    1 -> launchCameraVideo()
                }
            }
            .show()
    }

    private fun launchCameraPhoto() {
        val photoFile = File.createTempFile(
            "photo_${System.currentTimeMillis()}",
            ".jpg",
            requireContext().externalCacheDir
        )
        cameraImageUri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            photoFile
        )
        takePictureLauncher.launch(cameraImageUri!!)
    }

    private fun launchCameraVideo() {
        val videoFile = File.createTempFile(
            "video_${System.currentTimeMillis()}",
            ".mp4",
            requireContext().externalCacheDir
        )
        cameraVideoUri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            videoFile
        )
        takeVideoLauncher.launch(cameraVideoUri!!)
    }

    // ─────────────────────────── ViewModel ───────────────────────────

    private fun observeViewModel() {
        viewModel.uploadProgress.observe(viewLifecycleOwner) { progress ->
            binding.progressUpload.visibility = View.VISIBLE
            binding.progressUpload.progress = progress
        }

        viewModel.uploadStatus.observe(viewLifecycleOwner) { status ->
            binding.tvProgress.visibility = View.VISIBLE
            binding.tvProgress.text = status
        }

        viewModel.createPostState.observe(viewLifecycleOwner) { resource ->
            when (resource) {
                is Resource.Loading -> {
                    binding.btnPost.isEnabled = false
                    binding.btnPickMedia.isEnabled = false
                }
                is Resource.Success -> {
                    binding.btnPost.isEnabled = true
                    binding.btnPickMedia.isEnabled = true
                    binding.progressUpload.visibility = View.GONE
                    binding.tvProgress.visibility = View.GONE
                    Toast.makeText(requireContext(), "Post shared!", Toast.LENGTH_SHORT).show()
                    clearForm()
                }
                is Resource.Error -> {
                    binding.btnPost.isEnabled = true
                    binding.btnPickMedia.isEnabled = true
                    binding.progressUpload.visibility = View.GONE
                    binding.tvProgress.visibility = View.GONE
                    Toast.makeText(requireContext(), resource.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ─────────────────────────── Helpers ───────────────────────────

    private fun toggleToolbar() {
        isToolbarExpanded = !isToolbarExpanded
        binding.expandedOptions.visibility =
            if (isToolbarExpanded) View.VISIBLE else View.GONE
        binding.btnExpand.setImageResource(
            if (isToolbarExpanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less
        )
    }

    private fun restoreState(savedInstanceState: Bundle) {
        val uris = BundleCompat.getParcelableArrayList(savedInstanceState, KEY_SELECTED_URIS, Uri::class.java)
        val types = savedInstanceState.getStringArrayList(KEY_SELECTED_TYPES)
        if (uris != null && types != null) {
            selectedMedia.clear()
            uris.forEachIndexed { index, uri ->
                types.getOrNull(index)?.let { type -> selectedMedia.add(Pair(uri, type)) }
            }
            mediaPreviewAdapter.submitList(selectedMedia.toList())
            updateMediaCount()
        }
        isToolbarExpanded = savedInstanceState.getBoolean(KEY_TOOLBAR_EXPANDED, false)
        binding.expandedOptions.visibility = if (isToolbarExpanded) View.VISIBLE else View.GONE
        binding.btnExpand.setImageResource(
            if (isToolbarExpanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less
        )
    }

    private fun updateMediaCount() {
        binding.tvMediaCount.text = "${selectedMedia.size} item(s) selected"
        binding.tvMediaCount.visibility =
            if (selectedMedia.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun clearForm() {
        selectedMedia.clear()
        mediaPreviewAdapter.submitList(emptyList())
        binding.etCaption.text?.clear()
        binding.tvMediaCount.visibility = View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraImageUri?.let { outState.putParcelable(KEY_CAMERA_IMAGE_URI, it) }
        cameraVideoUri?.let { outState.putParcelable(KEY_CAMERA_VIDEO_URI, it) }
        if (selectedMedia.isNotEmpty()) {
            outState.putParcelableArrayList(KEY_SELECTED_URIS, ArrayList(selectedMedia.map { it.first }))
            outState.putStringArrayList(KEY_SELECTED_TYPES, ArrayList(selectedMedia.map { it.second }))
        }
        outState.putBoolean(KEY_TOOLBAR_EXPANDED, isToolbarExpanded)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
