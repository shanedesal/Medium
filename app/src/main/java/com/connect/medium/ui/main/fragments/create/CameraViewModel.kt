package com.connect.medium.ui.main.fragments.create

import androidx.camera.core.CameraSelector
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

enum class CameraMode { PHOTO, VIDEO }

class CameraViewModel : ViewModel() {

    private val _cameraMode = MutableLiveData(CameraMode.PHOTO)
    val cameraMode: LiveData<CameraMode> = _cameraMode

    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording

    private val _lensFacing = MutableLiveData(CameraSelector.LENS_FACING_BACK)
    val lensFacing: LiveData<Int> = _lensFacing

    fun setMode(mode: CameraMode) {
        _cameraMode.value = mode
    }

    fun setRecording(recording: Boolean) {
        _isRecording.value = recording
    }

    fun toggleLens() {
        _lensFacing.value = if (_lensFacing.value == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }
}
