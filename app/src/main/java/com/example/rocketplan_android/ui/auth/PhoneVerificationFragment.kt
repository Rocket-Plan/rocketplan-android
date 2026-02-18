package com.example.rocketplan_android.ui.auth

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.BuildConfig
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.CountryCode
import com.example.rocketplan_android.databinding.FragmentPhoneVerificationBinding

class PhoneVerificationFragment : Fragment() {

    private var _binding: FragmentPhoneVerificationBinding? = null
    private val binding get() = _binding!!

    private val args: PhoneVerificationFragmentArgs by navArgs()
    private val viewModel: PhoneVerificationViewModel by viewModels()

    companion object {
        private const val TAG = "PhoneVerification"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhoneVerificationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupCountryDropdown()
        setupInputListeners()
        setupObservers()
    }

    private fun setupCountryDropdown() {
        val countries = viewModel.countries
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            countries.map { it.displayName }
        )
        binding.countryCodeDropdown.setAdapter(adapter)
        binding.countryCodeDropdown.setText(CountryCode.DEFAULT.displayName, false)

        binding.countryCodeDropdown.setOnItemClickListener { _, _, position, _ ->
            viewModel.setSelectedCountry(countries[position])
            // Reformat phone number for new country format
            val current = binding.phoneInput.text?.toString() ?: ""
            val digits = current.replace(Regex("[^0-9]"), "")
            if (digits.isNotEmpty()) {
                isFormattingPhone = true
                val formatted = formatPhoneNumber(digits)
                binding.phoneInput.setText(formatted)
                binding.phoneInput.setSelection(formatted.length)
                isFormattingPhone = false
            }
        }
    }

    private var isFormattingPhone = false

    private fun setupInputListeners() {
        binding.phoneInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormattingPhone) return
                val raw = s?.toString() ?: ""
                viewModel.setPhone(raw)

                val digits = raw.replace(Regex("[^0-9]"), "")
                val formatted = formatPhoneNumber(digits)
                if (formatted != raw) {
                    isFormattingPhone = true
                    binding.phoneInput.setText(formatted)
                    binding.phoneInput.setSelection(formatted.length)
                    isFormattingPhone = false
                }
            }
        })

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.sendCodeButton.setOnClickListener {
            hideKeyboard()
            validateAndSend()
        }
    }

    /**
     * Format phone digits for display based on the selected country.
     * US/Canada (+1): (XXX) XXX-XXXX
     * UK (+44): XXXX XXX XXXX
     * Australia (+61): XXXX XXX XXX
     * Others: groups of 3-4 digits
     */
    private fun formatPhoneNumber(digits: String): String {
        if (digits.isEmpty()) return ""
        val country = viewModel.selectedCountry.value ?: CountryCode.DEFAULT
        return when (country.dialCode) {
            "+1" -> formatNorthAmerican(digits)
            "+44" -> formatUk(digits)
            "+61" -> formatAustralian(digits)
            else -> formatGeneric(digits)
        }
    }

    private fun formatNorthAmerican(digits: String): String {
        // (XXX) XXX-XXXX
        return buildString {
            if (digits.length >= 1) append("(")
            append(digits.take(3))
            if (digits.length >= 3) append(") ")
            if (digits.length > 3) append(digits.substring(3, minOf(6, digits.length)))
            if (digits.length > 6) {
                append("-")
                append(digits.substring(6, minOf(10, digits.length)))
            }
        }
    }

    private fun formatUk(digits: String): String {
        // XXXX XXX XXXX
        return buildString {
            append(digits.take(4))
            if (digits.length > 4) {
                append(" ")
                append(digits.substring(4, minOf(7, digits.length)))
            }
            if (digits.length > 7) {
                append(" ")
                append(digits.substring(7, minOf(11, digits.length)))
            }
        }
    }

    private fun formatAustralian(digits: String): String {
        // XXXX XXX XXX
        return buildString {
            append(digits.take(4))
            if (digits.length > 4) {
                append(" ")
                append(digits.substring(4, minOf(7, digits.length)))
            }
            if (digits.length > 7) {
                append(" ")
                append(digits.substring(7, minOf(10, digits.length)))
            }
        }
    }

    private fun formatGeneric(digits: String): String {
        // XXX XXX XXXX
        return buildString {
            append(digits.take(3))
            if (digits.length > 3) {
                append(" ")
                append(digits.substring(3, minOf(6, digits.length)))
            }
            if (digits.length > 6) {
                append(" ")
                append(digits.substring(6, minOf(10, digits.length)))
            }
            if (digits.length > 10) {
                append(" ")
                append(digits.substring(10))
            }
        }
    }

    private fun validateAndSend() {
        val phone = viewModel.phone.value?.trim() ?: ""
        if (phone.isBlank()) {
            binding.errorText.text = getString(R.string.onboarding_phone_required)
            binding.errorText.visibility = View.VISIBLE
            return
        }
        // Basic length check - digits only should be at least 7 characters
        val digitsOnly = phone.replace(Regex("[^0-9]"), "")
        if (digitsOnly.length < 7) {
            binding.errorText.text = getString(R.string.onboarding_phone_invalid)
            binding.errorText.visibility = View.VISIBLE
            return
        }
        binding.errorText.visibility = View.GONE
        binding.sendCodeButton.isEnabled = false
        viewModel.sendCode()
    }

    private fun setupObservers() {
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.sendCodeButton.isEnabled = !isLoading
            binding.phoneInput.isEnabled = !isLoading
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            binding.errorText.text = error
            binding.errorText.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        viewModel.codeSent.observe(viewLifecycleOwner) { sent ->
            if (sent == true) {
                viewModel.onCodeSentHandled()
                if (findNavController().currentDestination?.id != R.id.phoneVerificationFragment) return@observe
                val country = viewModel.selectedCountry.value ?: CountryCode.DEFAULT
                val phone = viewModel.phone.value ?: ""
                val action = PhoneVerificationFragmentDirections
                    .actionPhoneVerificationFragmentToSmsCodeVerifyFragment(
                        userId = args.userId,
                        email = args.email,
                        phone = phone,
                        countryCode = country.dialCode
                    )
                findNavController().navigate(action)
            }
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService<InputMethodManager>()
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
