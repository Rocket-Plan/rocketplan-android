package com.example.rocketplan_android.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.data.model.CountryCode
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import kotlinx.coroutines.launch

class PhoneVerificationViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val authRepository = AuthRepository(SecureStorage.getInstance(application))

    private val _selectedCountry = MutableLiveData(CountryCode.DEFAULT)
    val selectedCountry: LiveData<CountryCode> = _selectedCountry

    private val _phone = MutableLiveData("")
    val phone: LiveData<String> = _phone

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _codeSent = MutableLiveData(false)
    val codeSent: LiveData<Boolean> = _codeSent

    val countries: List<CountryCode> = CountryCode.ALL

    fun setPhone(value: String) {
        _phone.value = value
        _errorMessage.value = null
    }

    fun setSelectedCountry(country: CountryCode) {
        _selectedCountry.value = country
    }

    fun sendCode() {
        val phoneValue = _phone.value?.trim() ?: ""
        if (phoneValue.isBlank()) {
            _errorMessage.value = getApplication<Application>().getString(
                com.example.rocketplan_android.R.string.onboarding_phone_required
            )
            return
        }

        val country = _selectedCountry.value ?: CountryCode.DEFAULT
        // Format as E164: country dial code + digits only
        val digitsOnly = phoneValue.replace(Regex("[^0-9]"), "")
        val e164Phone = "${country.dialCode}$digitsOnly"

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val result = authRepository.sendSmsVerification(e164Phone)
            if (result.isSuccess) {
                _codeSent.value = true
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to send code"
            }
            _isLoading.value = false
        }
    }

    fun onCodeSentHandled() {
        _codeSent.value = false
    }
}
