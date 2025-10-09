package com.example.rocketplan_android.ui.login

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
 * ViewModel for Login screen
 * Handles email/password validation and sign-in logic with actual API integration
 */
class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val secureStorage = SecureStorage.getInstance(application)
    private val authRepository = AuthRepository(secureStorage)

    // UI State
    private val _email = MutableLiveData<String>()
    val email: LiveData<String> = _email

    private val _password = MutableLiveData<String>()
    val password: LiveData<String> = _password

    private val _rememberMe = MutableLiveData<Boolean>(false)
    val rememberMe: LiveData<Boolean> = _rememberMe

    private val _emailError = MutableLiveData<String?>()
    val emailError: LiveData<String?> = _emailError

    private val _passwordError = MutableLiveData<String?>()
    val passwordError: LiveData<String?> = _passwordError

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _navigateToForgotPassword = MutableLiveData<String?>()
    val navigateToForgotPassword: LiveData<String?> = _navigateToForgotPassword

    private val _signInSuccess = MutableLiveData<Boolean>(false)
    val signInSuccess: LiveData<Boolean> = _signInSuccess

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _biometricPromptVisible = MutableLiveData<Boolean>(false)
    val biometricPromptVisible: LiveData<Boolean> = _biometricPromptVisible

    init {
        _email.value = ""
        _password.value = ""
        loadSavedCredentials()
    }

    /**
     * Load saved credentials if Remember Me was enabled
     */
    private fun loadSavedCredentials() {
        viewModelScope.launch {
            val isRememberMe = authRepository.isRememberMeEnabled()
            _rememberMe.value = isRememberMe

            if (isRememberMe) {
                val (savedEmail, savedPassword) = authRepository.getSavedCredentials()
                _email.value = savedEmail ?: ""
                _password.value = savedPassword ?: ""

                // Check if biometric is enabled
                val isBiometricEnabled = authRepository.isBiometricEnabled()
                if (isBiometricEnabled && savedEmail != null && savedPassword != null) {
                    _biometricPromptVisible.value = true
                }
            }
        }
    }

    fun setEmail(value: String) {
        _email.value = value
        _emailError.value = null
    }

    fun setPassword(value: String) {
        _password.value = value
        _passwordError.value = null
    }

    fun setRememberMe(value: Boolean) {
        _rememberMe.value = value
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
     * Validate password
     */
    private fun validatePassword(): Boolean {
        val passwordValue = _password.value ?: ""

        return when {
            passwordValue.isBlank() -> {
                _passwordError.value = "Password is required"
                false
            }
            passwordValue.length < 6 -> {
                _passwordError.value = "Password must be at least 6 characters"
                false
            }
            else -> {
                _passwordError.value = null
                true
            }
        }
    }

    /**
     * Validate all fields
     */
    private fun validateAllFields(): Boolean {
        val isEmailValid = validateEmail()
        val isPasswordValid = validatePassword()
        return isEmailValid && isPasswordValid
    }

    /**
     * Sign in with email and password using actual API
     */
    fun signIn() {
        if (!validateAllFields()) {
            return
        }

        val emailValue = _email.value ?: return
        val passwordValue = _password.value ?: return
        val rememberMeValue = _rememberMe.value ?: false

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = authRepository.signIn(emailValue, passwordValue, rememberMeValue)

                if (result.isSuccess) {
                    if (AppConfig.isLoggingEnabled) {
                        println("Sign in successful")
                        println("Token: ${result.getOrNull()?.token?.take(20)}...")
                    }
                    _signInSuccess.value = true
                } else {
                    val error = result.exceptionOrNull()
                    _errorMessage.value = error?.message ?: "Sign in failed"

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
     * Sign in via biometric authentication
     */
    fun signInWithBiometric() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val (savedEmail, savedPassword) = authRepository.getSavedCredentials()

                if (savedEmail != null && savedPassword != null) {
                    val result = authRepository.signIn(savedEmail, savedPassword, true)

                    if (result.isSuccess) {
                        _signInSuccess.value = true
                    } else {
                        _errorMessage.value = "Biometric authentication failed"
                    }
                } else {
                    _errorMessage.value = "No saved credentials found"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Biometric sign-in failed: ${e.message}"
            } finally {
                _isLoading.value = false
                _biometricPromptVisible.value = false
            }
        }
    }

    /**
     * Navigate to forgot password screen
     */
    fun forgotPassword() {
        _navigateToForgotPassword.value = _email.value ?: ""
    }

    fun onForgotPasswordNavigated() {
        _navigateToForgotPassword.value = null
    }

    fun onSignInSuccessHandled() {
        _signInSuccess.value = false
    }

    fun onBiometricPromptDismissed() {
        _biometricPromptVisible.value = false
    }
}
