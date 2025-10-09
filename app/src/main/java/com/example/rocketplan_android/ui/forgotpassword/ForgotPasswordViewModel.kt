package com.example.rocketplan_android.ui.forgotpassword

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.config.AppConfig
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import kotlinx.coroutines.launch

/**
 * ViewModel for Forgot Password screen
 * Handles password reset request
 */
class ForgotPasswordViewModel(application: Application) : AndroidViewModel(application) {

    private val secureStorage = SecureStorage.getInstance(application)
    private val authRepository = AuthRepository(secureStorage)

    private val _email = MutableLiveData<String>()
    val email: LiveData<String> = _email

    private val _emailError = MutableLiveData<String?>()
    val emailError: LiveData<String?> = _emailError

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _successMessage = MutableLiveData<String?>()
    val successMessage: LiveData<String?> = _successMessage

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _navigateBack = MutableLiveData<Boolean>(false)
    val navigateBack: LiveData<Boolean> = _navigateBack

    fun setInitialEmail(email: String) {
        if (_email.value.isNullOrEmpty()) {
            _email.value = email
        }
    }

    fun setEmail(value: String) {
        _email.value = value
        _emailError.value = null
        _successMessage.value = null
        _errorMessage.value = null
    }

    /**
     * Validate email format
     */
    private fun validateEmail(): Boolean {
        val emailValue = _email.value ?: ""

        return when {
            emailValue.isBlank() -> {
                _emailError.value = "Email is required"
                false
            }
            !android.util.Patterns.EMAIL_ADDRESS.matcher(emailValue).matches() -> {
                _emailError.value = "Please enter a valid email address"
                false
            }
            else -> {
                _emailError.value = null
                true
            }
        }
    }

    /**
     * Send password reset request
     */
    fun resetPassword() {
        if (!validateEmail()) {
            return
        }

        val emailValue = _email.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _successMessage.value = null

            try {
                val result = authRepository.resetPassword(emailValue)

                if (result.isSuccess) {
                    val response = result.getOrNull()
                    _successMessage.value = response?.message ?: "Password reset email sent successfully!"

                    if (AppConfig.isLoggingEnabled) {
                        println("Password reset requested for: $emailValue")
                    }
                } else {
                    val error = result.exceptionOrNull()
                    _errorMessage.value = error?.message ?: "Failed to send reset email"

                    if (AppConfig.isLoggingEnabled) {
                        error?.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Network error: ${e.message}"
                if (AppConfig.isLoggingEnabled) {
                    e.printStackTrace()
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Navigate back to login
     */
    fun navigateBack() {
        _navigateBack.value = true
    }

    fun onNavigatedBack() {
        _navigateBack.value = false
    }
}
