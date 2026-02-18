package com.example.rocketplan_android.ui.auth

import android.app.Application
import android.os.CountDownTimer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import kotlinx.coroutines.launch

class SmsCodeVerifyViewModel(
    application: Application,
    val phone: String,
    val countryCode: String
) : AndroidViewModel(application) {

    private val authRepository = AuthRepository(SecureStorage.getInstance(application))

    /** Phone in E164 format for API calls (e.g. "+15555555555") */
    private val e164Phone: String
        get() = "$countryCode${phone.replace(Regex("[^0-9]"), "")}"

    private val _code = MutableLiveData("")
    val code: LiveData<String> = _code

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _verified = MutableLiveData(false)
    val verified: LiveData<Boolean> = _verified

    private val _resendEnabled = MutableLiveData(false)
    val resendEnabled: LiveData<Boolean> = _resendEnabled

    private val _resendTimerText = MutableLiveData<String?>()
    val resendTimerText: LiveData<String?> = _resendTimerText

    private var resendTimer: CountDownTimer? = null

    init {
        startResendTimer()
    }

    fun setCode(value: String) {
        _code.value = value
        _errorMessage.value = null
    }

    fun verify() {
        if (_isLoading.value == true) return
        val codeValue = _code.value?.trim() ?: ""
        if (codeValue.length != 4) {
            _errorMessage.value = getApplication<Application>().getString(R.string.onboarding_sms_invalid_code)
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val result = authRepository.verifySmsCode(e164Phone, codeValue)
            if (result.isSuccess) {
                _verified.value = true
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Verification failed"
            }
            _isLoading.value = false
        }
    }

    fun resendCode() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val result = authRepository.sendSmsVerification(e164Phone)
            if (result.isSuccess) {
                startResendTimer()
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to resend"
                // Re-enable resend button on failure instead of locking for 120s
                _resendEnabled.value = true
                _resendTimerText.value = null
            }
            _isLoading.value = false
        }
    }

    private fun startResendTimer() {
        _resendEnabled.value = false
        resendTimer?.cancel()
        resendTimer = object : CountDownTimer(120_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                _resendTimerText.value = getApplication<Application>().getString(
                    R.string.onboarding_sms_resend_timer, seconds
                )
            }

            override fun onFinish() {
                _resendEnabled.value = true
                _resendTimerText.value = null
            }
        }.start()
    }

    fun onVerifiedHandled() {
        _verified.value = false
    }

    override fun onCleared() {
        super.onCleared()
        resendTimer?.cancel()
    }
}
