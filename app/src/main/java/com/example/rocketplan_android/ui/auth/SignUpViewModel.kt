package com.example.rocketplan_android.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.AuthSession
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import kotlinx.coroutines.launch

/**
 * ViewModel for the email + password sign-up flow.
 * Mirrors the iOS behaviour with password confirmation feedback.
 */
class SignUpViewModel(
    application: Application,
    initialEmail: String
) : AndroidViewModel(application) {

    private val authRepository = AuthRepository(SecureStorage.getInstance(application))

    private val _email = MutableLiveData(initialEmail)
    val email: LiveData<String> = _email

    private val _password = MutableLiveData("")
    val password: LiveData<String> = _password

    private val _confirmPassword = MutableLiveData("")
    val confirmPassword: LiveData<String> = _confirmPassword

    private val _emailError = MutableLiveData<String?>()
    val emailError: LiveData<String?> = _emailError

    private val _passwordError = MutableLiveData<String?>()
    val passwordError: LiveData<String?> = _passwordError

    private val _confirmPasswordMessage = MutableLiveData<String?>()
    val confirmPasswordMessage: LiveData<String?> = _confirmPasswordMessage

    private val _passwordsMatch = MutableLiveData<Boolean?>()
    val passwordsMatch: LiveData<Boolean?> = _passwordsMatch

    private val _isFormValid = MutableLiveData(false)
    val isFormValid: LiveData<Boolean> = _isFormValid

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _signUpSuccess = MutableLiveData<Boolean>()
    val signUpSuccess: LiveData<Boolean> = _signUpSuccess

    private val _authSession = MutableLiveData<AuthSession?>()
    val authSession: LiveData<AuthSession?> = _authSession

    fun setPassword(value: String) {
        _password.value = value
        _passwordError.value = null
        evaluateForm()
    }

    fun setConfirmPassword(value: String) {
        _confirmPassword.value = value
        evaluateForm()
    }

    fun setEmail(value: String) {
        _email.value = value
        _emailError.value = null
        evaluateForm()
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

    private fun validatePassword(): Boolean {
        val passwordValue = _password.value ?: ""
        return when {
            passwordValue.isBlank() -> {
                _passwordError.value = "Password is required"
                false
            }
            passwordValue.length < 6 -> {
                _passwordError.value = getApplication<Application>().getString(R.string.message_password_requirement)
                false
            }
            else -> {
                _passwordError.value = null
                true
            }
        }
    }

    private fun evaluateConfirmPassword() {
        val passwordValue = _password.value ?: ""
        val confirmValue = _confirmPassword.value ?: ""

        if (confirmValue.isBlank() || confirmValue.length < passwordValue.length) {
            _confirmPasswordMessage.value = null
            _passwordsMatch.value = null
            return
        }

        val matches = passwordValue == confirmValue
        _passwordsMatch.value = matches
        val appContext = getApplication<Application>()
        _confirmPasswordMessage.value = if (matches) {
            appContext.getString(R.string.message_passwords_match)
        } else {
            appContext.getString(R.string.message_passwords_do_not_match)
        }
    }

    private fun evaluateForm() {
        val isEmailValid = validateEmail()
        val isPasswordValid = validatePassword()
        evaluateConfirmPassword()

        val passwordsMatch = _passwordsMatch.value
        _isFormValid.value = isEmailValid && isPasswordValid && passwordsMatch == true
    }

    fun signUp() {
        if (_isFormValid.value != true) {
            evaluateForm()
            return
        }

        val emailValue = _email.value?.trim() ?: return
        val passwordValue = _password.value ?: return
        val confirmValue = _confirmPassword.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _authSession.value = null

            val result = authRepository.signUp(emailValue, passwordValue, confirmValue)
            if (result.isSuccess) {
                _authSession.value = result.getOrNull()
                _signUpSuccess.value = true
            } else {
                // Error message is already user-friendly from ApiError
                val error = result.exceptionOrNull()
                _errorMessage.value = error?.message ?: "Sign up failed. Please try again."
            }
            _isLoading.value = false
        }
    }

    fun onSignUpSuccessHandled() {
        _signUpSuccess.value = false
    }
}
