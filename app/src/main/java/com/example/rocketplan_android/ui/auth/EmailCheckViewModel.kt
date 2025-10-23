package com.example.rocketplan_android.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import kotlinx.coroutines.launch

/**
 * ViewModel for the initial email check screen.
 * Mirrors the iOS flow by verifying whether the email is already registered.
 */
class EmailCheckViewModel(application: Application) : AndroidViewModel(application) {

    private val authRepository = AuthRepository(SecureStorage.getInstance(application))

    private val _email = MutableLiveData("")
    val email: LiveData<String> = _email

    private val _emailError = MutableLiveData<String?>()
    val emailError: LiveData<String?> = _emailError

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _navigateToSignIn = MutableLiveData<String?>()
    val navigateToSignIn: LiveData<String?> = _navigateToSignIn

    private val _navigateToSignUp = MutableLiveData<String?>()
    val navigateToSignUp: LiveData<String?> = _navigateToSignUp

    fun setEmail(value: String) {
        _email.value = value
        _emailError.value = null
        _errorMessage.value = null
    }

    private fun validateEmail(): Boolean {
        val emailValue = _email.value?.trim() ?: ""

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

    fun submitEmail() {
        if (!validateEmail()) {
            return
        }

        val emailValue = _email.value?.trim() ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val result = authRepository.checkEmail(emailValue)
            if (result.isSuccess) {
                val registered = result.getOrNull()?.registered ?: false
                if (registered) {
                    _navigateToSignIn.value = emailValue
                } else {
                    _navigateToSignUp.value = emailValue
                }
            } else {
                // Error message is already user-friendly from ApiError
                val error = result.exceptionOrNull()
                _errorMessage.value = error?.message ?: "Unable to verify email. Please try again."
            }
            _isLoading.value = false
        }
    }

    fun onNavigateToSignInHandled() {
        _navigateToSignIn.value = null
    }

    fun onNavigateToSignUpHandled() {
        _navigateToSignUp.value = null
    }
}
