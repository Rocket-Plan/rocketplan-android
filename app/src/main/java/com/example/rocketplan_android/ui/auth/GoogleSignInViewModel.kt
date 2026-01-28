package com.example.rocketplan_android.ui.auth

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.BuildConfig
import com.example.rocketplan_android.auth.GoogleAuthManager
import com.example.rocketplan_android.auth.GoogleSignInResult
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import kotlinx.coroutines.launch

/**
 * State for Google Sign-In UI
 */
sealed class GoogleSignInState {
    data object Idle : GoogleSignInState()
    data object Loading : GoogleSignInState()
    data object Success : GoogleSignInState()
    data object FallbackToWebView : GoogleSignInState()
    data class Error(val message: String) : GoogleSignInState()
}

/**
 * Shared ViewModel for Google Sign-In via Credential Manager.
 * Can be used by EmailCheckFragment and LoginFragment.
 */
class GoogleSignInViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "GoogleSignInViewModel"
    }

    private val authRepository = AuthRepository(SecureStorage.getInstance(application))
    private val googleAuthManager = GoogleAuthManager(application)

    private val _state = MutableLiveData<GoogleSignInState>(GoogleSignInState.Idle)
    val state: LiveData<GoogleSignInState> = _state

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _signInSuccess = MutableLiveData(false)
    val signInSuccess: LiveData<Boolean> = _signInSuccess

    private val _shouldFallbackToWebView = MutableLiveData(false)
    val shouldFallbackToWebView: LiveData<Boolean> = _shouldFallbackToWebView

    /**
     * Check if native Google Sign-In is available.
     * Returns false if FLIR build or Web Client ID not configured.
     */
    fun isNativeSignInAvailable(): Boolean {
        if (BuildConfig.HAS_FLIR_SUPPORT) {
            return false
        }
        return googleAuthManager.isAvailable()
    }

    /**
     * Initiate Google Sign-In via Credential Manager.
     * On success, authenticates with backend.
     * On certain failures, triggers fallback to WebView OAuth.
     */
    fun signIn() {
        if (_isLoading.value == true) {
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _state.value = GoogleSignInState.Loading

            if (BuildConfig.ENABLE_LOGGING) {
                Log.d(TAG, "Starting native Google Sign-In...")
            }

            when (val result = googleAuthManager.signIn()) {
                is GoogleSignInResult.Success -> {
                    if (BuildConfig.ENABLE_LOGGING) {
                        Log.d(TAG, "Got Google ID token, authenticating with backend...")
                    }
                    authenticateWithBackend(result.idToken)
                }

                is GoogleSignInResult.Cancelled -> {
                    if (BuildConfig.ENABLE_LOGGING) {
                        Log.d(TAG, "User cancelled Google Sign-In")
                    }
                    _isLoading.value = false
                    _state.value = GoogleSignInState.Idle
                }

                is GoogleSignInResult.NoCredentials -> {
                    if (BuildConfig.ENABLE_LOGGING) {
                        Log.d(TAG, "No Google credentials available")
                    }
                    _isLoading.value = false
                    _errorMessage.value = "No Google account found. Please add a Google account in Settings."
                    _state.value = GoogleSignInState.Error("No Google account found")
                }

                is GoogleSignInResult.Unavailable -> {
                    if (BuildConfig.ENABLE_LOGGING) {
                        Log.d(TAG, "Credential Manager unavailable")
                    }
                    _isLoading.value = false
                    _errorMessage.value = "Google Sign-In is not available on this device."
                    _state.value = GoogleSignInState.Error("Google Sign-In unavailable")
                }

                is GoogleSignInResult.Error -> {
                    Log.e(TAG, "Google Sign-In error: ${result.message}", result.exception)
                    _isLoading.value = false
                    _errorMessage.value = result.message
                    _state.value = GoogleSignInState.Error(result.message)
                }
            }
        }
    }

    private suspend fun authenticateWithBackend(idToken: String) {
        val result = authRepository.signInWithGoogle(idToken)

        if (result.isSuccess) {
            if (BuildConfig.ENABLE_LOGGING) {
                Log.d(TAG, "Backend authentication successful")
            }
            _isLoading.value = false
            _signInSuccess.value = true
            _state.value = GoogleSignInState.Success
        } else {
            val error = result.exceptionOrNull()
            Log.e(TAG, "Backend authentication failed: ${error?.message}", error)
            _isLoading.value = false
            _errorMessage.value = error?.message ?: "Authentication failed. Please try again."
            _state.value = GoogleSignInState.Error(error?.message ?: "Authentication failed")
        }
    }

    fun onSignInSuccessHandled() {
        _signInSuccess.value = false
        _state.value = GoogleSignInState.Idle
    }

    fun onFallbackHandled() {
        _shouldFallbackToWebView.value = false
        _state.value = GoogleSignInState.Idle
    }

    fun clearError() {
        _errorMessage.value = null
        _state.value = GoogleSignInState.Idle
    }
}
