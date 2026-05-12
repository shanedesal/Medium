package com.connect.medium.ui.main.fragments.create

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class MediaPickerViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediaPickerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MediaPickerViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
