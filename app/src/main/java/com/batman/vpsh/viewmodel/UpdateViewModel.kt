package com.batman.vpsh.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.batman.vpsh.core.UpdateCheckResult
import com.batman.vpsh.core.UpdateManager
import com.batman.vpsh.data.AppVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data class UpToDate(val version: String) : UpdateUiState()
    data class Available(val remoteVersion: String, val releasePageUrl: String) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    val currentVersion: String get() = AppVersion.CURRENT

    fun checkForUpdate() {
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            when (val result = UpdateManager.checkForUpdate()) {
                is UpdateCheckResult.UpToDate -> _state.value = UpdateUiState.UpToDate(result.currentVersion)
                is UpdateCheckResult.Available -> _state.value = UpdateUiState.Available(result.remoteVersion, result.releasePageUrl)
                is UpdateCheckResult.Failed -> _state.value = UpdateUiState.Error(result.reason)
            }
        }
    }

    fun openReleasePage(url: String) {
        UpdateManager.openReleasePage(getApplication(), url)
    }

    fun reset() {
        _state.value = UpdateUiState.Idle
    }
}
