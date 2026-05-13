package com.connect.medium.ui.main.fragments.create

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.util.Rational
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.connect.medium.R
import com.connect.medium.databinding.FragmentCameraBinding
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.io.IOException

class CameraFragment : Fragment() {

    companion object {
        const val RESULT_KEY = "camera_result"
        const val ARG_URI = "camera_uri"
        const val ARG_TYPE = "camera_type"

        private const val TAG = "CameraFragment"
        private const val VIDEO_SIZE_LIMIT_BYTES = 50 * 1024 * 1024L
    }

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CameraViewModel by viewModels { CameraViewModelFactory() }

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setupModeTab()
        setupClickListeners()
        observeViewModel()
        checkPermissionsAndStart()
    }

    // ─────────────────────────── Setup ───────────────────────────

    private fun setupModeTab() {
        binding.tabMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                viewModel.setMode(if (tab.position == 0) CameraMode.PHOTO else CameraMode.VIDEO)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun setupClickListeners() {
        binding.btnClose.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnFlipCamera.setOnClickListener {
            viewModel.toggleLens()
            startCamera()
        }

        binding.btnCapture.setOnClickListener {
            when (viewModel.cameraMode.value) {
                CameraMode.PHOTO -> capturePhoto()
                CameraMode.VIDEO -> {
                    if (viewModel.isRecording.value == true) stopRecording() else startRecording()
                }
                null -> Unit
            }
        }
    }

    private fun observeViewModel() {
        viewModel.isRecording.observe(viewLifecycleOwner) { isRecording ->
            binding.btnCapture.setBackgroundResource(
                if (isRecording) R.drawable.bg_capture_btn_recording else R.drawable.bg_capture_btn_idle
            )
            binding.tvRecordingIndicator.visibility = if (isRecording) View.VISIBLE else View.GONE
            binding.tabMode.isEnabled = !isRecording
            binding.btnFlipCamera.isEnabled = !isRecording
        }
    }

    // ─────────────────────────── Permissions ───────────────────────────

    private fun checkPermissionsAndStart() {
        val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missing.isEmpty()) startCamera() else permissionLauncher.launch(missing)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    // ─────────────────────────── Camera lifecycle ───────────────────────────

    private fun startCamera() {
        if (!hasCameraPermission()) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                bindCameraUseCases(cameraProviderFuture.get())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val b = _binding ?: return

        val lensFacing = viewModel.lensFacing.value ?: CameraSelector.LENS_FACING_BACK
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val rotation = b.previewView.display?.rotation ?: Surface.ROTATION_0

        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(b.previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setTargetRotation(rotation)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(listOf(Quality.HD, Quality.SD))
            )
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        // ViewPort crops both ImageCapture and VideoCapture output to a 1:1 square,
        // matching the square PreviewView the user sees.
        val viewPort = ViewPort.Builder(Rational(1, 1), rotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()

        val useCaseGroup = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(imageCapture!!)
            .addUseCase(videoCapture!!)
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, useCaseGroup)
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
            Toast.makeText(requireContext(), "Camera initialisation failed", Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────── Capture — photo ───────────────────────────

    private fun capturePhoto() {
        val capture = imageCapture ?: return

        val outputFile = try {
            File.createTempFile("photo_${System.currentTimeMillis()}", ".jpg", requireContext().externalCacheDir)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create temp file", e)
            Toast.makeText(requireContext(), "Photo capture failed", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnCapture.isEnabled = false

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(outputFile).build(),
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        outputFile
                    )
                    sendResult(uri, "image")
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exc)
                    _binding?.btnCapture?.isEnabled = true
                    Toast.makeText(requireContext(), "Photo capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ─────────────────────────── Capture — video ───────────────────────────

    private fun startRecording() {
        val capture = videoCapture ?: return

        val videoFile = try {
            File.createTempFile("video_${System.currentTimeMillis()}", ".mp4", requireContext().externalCacheDir)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create temp file", e)
            Toast.makeText(requireContext(), "Recording failed to start", Toast.LENGTH_SHORT).show()
            return
        }

        val pendingRecording = capture.output
            .prepareRecording(requireContext(), FileOutputOptions.Builder(videoFile).build())
            .let { pending ->
                if (ContextCompat.checkSelfPermission(
                        requireContext(), Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    pending.withAudioEnabled()
                } else {
                    pending
                }
            }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(requireContext())) { event ->
            when (event) {
                is VideoRecordEvent.Start -> viewModel.setRecording(true)

                is VideoRecordEvent.Finalize -> {
                    viewModel.setRecording(false)
                    if (!event.hasError()) {
                        val fileSize = videoFile.length()
                        if (fileSize > VIDEO_SIZE_LIMIT_BYTES) {
                            Toast.makeText(
                                requireContext(),
                                "Video exceeds 50 MB limit: ${fileSize / (1024 * 1024)} MB",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val uri = FileProvider.getUriForFile(
                                requireContext(),
                                "${requireContext().packageName}.fileprovider",
                                videoFile
                            )
                            sendResult(uri, "video")
                        }
                    } else {
                        Log.e(TAG, "Video recording error: ${event.error}")
                        Toast.makeText(requireContext(), "Recording failed", Toast.LENGTH_SHORT).show()
                    }
                }

                else -> Unit
            }
        }
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    // ─────────────────────────── Result ───────────────────────────

    private fun sendResult(uri: Uri, type: String) {
        setFragmentResult(RESULT_KEY, Bundle().apply {
            putParcelable(ARG_URI, uri)
            putString(ARG_TYPE, type)
        })
        findNavController().popBackStack()
    }

    // ─────────────────────────── Lifecycle ───────────────────────────

    override fun onStop() {
        super.onStop()
        if (viewModel.isRecording.value == true) stopRecording()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        _binding = null
    }
}

class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dimPaint = Paint().apply {
        color = 0xAA000000.toInt()
    }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint().apply {
        color = 0xAAFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f

        // dim the whole view first
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        // punch out the square
        canvas.drawRect(left, top, left + size, top + size, clearPaint)
        // draw border around square
        canvas.drawRect(left, top, left + size, top + size, borderPaint)
    }
}
