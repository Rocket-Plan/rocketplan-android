package com.example.rocketplan_android.ui.auth

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentSmsCodeVerifyBinding
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

class SmsCodeVerifyFragment : Fragment() {

    private var _binding: FragmentSmsCodeVerifyBinding? = null
    private val binding get() = _binding!!

    private val args: SmsCodeVerifyFragmentArgs by navArgs()

    private val viewModel: SmsCodeVerifyViewModel by viewModels {
        SmsCodeVerifyViewModelFactory(requireActivity().application, args.phone, args.countryCode)
    }

    private lateinit var codeBoxes: List<EditText>

    companion object {
        private const val TAG = "SmsCodeVerify"
    }

    /** Handles the SMS User Consent intent result */
    private val smsConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val message = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE) ?: return@registerForActivityResult
            // Extract 4-digit code from SMS message
            val code = Regex("\\b(\\d{4})\\b").find(message)?.value
            if (code != null) {
                fillCode(code)
            }
        }
    }

    /** Broadcast receiver that listens for incoming SMS and prompts user consent */
    private val smsConsentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (SmsRetriever.SMS_RETRIEVED_ACTION == intent.action) {
                val extras = intent.extras ?: return
                val consentStatus = extras.getParcelable<Status>(SmsRetriever.EXTRA_STATUS) ?: return
                when (consentStatus.statusCode) {
                    CommonStatusCodes.SUCCESS -> {
                        val consentIntent = extras.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)
                        if (consentIntent != null) {
                            smsConsentLauncher.launch(consentIntent)
                        }
                    }
                    CommonStatusCodes.TIMEOUT -> {
                        Log.d(TAG, "SMS consent timed out")
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSmsCodeVerifyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        codeBoxes = listOf(binding.codeBox1, binding.codeBox2, binding.codeBox3, binding.codeBox4)

        binding.subtitleText.text = getString(
            R.string.onboarding_sms_subtitle,
            "${args.countryCode} ${args.phone}"
        )

        setupCodeBoxes()
        setupInputListeners()
        setupObservers()
        startSmsUserConsent()

        // Focus first box
        binding.codeBox1.requestFocus()
        binding.codeBox1.postDelayed({
            requireContext().getSystemService<InputMethodManager>()
                ?.showSoftInput(binding.codeBox1, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun setupCodeBoxes() {
        for (i in codeBoxes.indices) {
            val box = codeBoxes[i]

            box.addTextChangedListener(object : TextWatcher {
                private var isUpdating = false
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isUpdating) return
                    isUpdating = true

                    val text = s?.toString() ?: ""
                    // If user pasted multiple digits, distribute across boxes
                    if (text.length > 1) {
                        val digits = text.filter { it.isDigit() }
                        box.setText(digits.take(1).toString())
                        box.setSelection(box.text.length)
                        // Fill remaining digits into subsequent boxes
                        for (j in 1 until digits.length) {
                            val targetIndex = i + j
                            if (targetIndex < codeBoxes.size) {
                                codeBoxes[targetIndex].setText(digits[j].toString())
                            }
                        }
                        // Focus the next empty box or last filled box
                        val nextEmpty = codeBoxes.indexOfFirst { it.text.isNullOrEmpty() }
                        if (nextEmpty >= 0) {
                            codeBoxes[nextEmpty].requestFocus()
                        } else {
                            codeBoxes.last().requestFocus()
                            codeBoxes.last().setSelection(1)
                        }
                    } else if (text.length == 1 && i < codeBoxes.size - 1) {
                        // Single digit entered — advance to next box
                        codeBoxes[i + 1].requestFocus()
                    }

                    isUpdating = false
                    updateCodeFromBoxes()
                }
            })

            // Handle backspace: if box is empty and user presses delete, go to previous box
            box.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (box.text.isNullOrEmpty() && i > 0) {
                        codeBoxes[i - 1].apply {
                            setText("")
                            requestFocus()
                        }
                        updateCodeFromBoxes()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    private fun updateCodeFromBoxes() {
        val code = codeBoxes.joinToString("") { it.text.toString() }
        viewModel.setCode(code)
        binding.verifyButton.isEnabled = code.length == 4

        if (code.length == 4) {
            hideKeyboard()
            viewModel.verify()
        }
    }

    /** Fill all 4 code boxes from a string (e.g. from SMS auto-read) */
    private fun fillCode(code: String) {
        val digits = code.filter { it.isDigit() }.take(4)
        for (i in digits.indices) {
            if (i < codeBoxes.size) {
                codeBoxes[i].setText(digits[i].toString())
            }
        }
        if (digits.length == 4) {
            codeBoxes.last().requestFocus()
            codeBoxes.last().setSelection(1)
        }
    }

    /** Start SMS User Consent API to auto-read verification code from incoming SMS */
    private fun startSmsUserConsent() {
        SmsRetriever.getClient(requireContext())
            .startSmsUserConsent(null)
            .addOnSuccessListener {
                Log.d(TAG, "SMS User Consent started")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "SMS User Consent failed to start", e)
            }

        val filter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        ContextCompat.registerReceiver(
            requireContext(),
            smsConsentReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun setupInputListeners() {
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.verifyButton.setOnClickListener {
            hideKeyboard()
            viewModel.verify()
        }

        binding.resendButton.setOnClickListener {
            resend()
        }
    }

    private fun setupObservers() {
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.verifyButton.isEnabled = !isLoading && (viewModel.code.value?.length == 4)
            codeBoxes.forEach { it.isEnabled = !isLoading }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            binding.errorText.text = error
            binding.errorText.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        viewModel.resendEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.resendButton.isEnabled = enabled
        }

        viewModel.resendTimerText.observe(viewLifecycleOwner) { text ->
            binding.resendButton.text = text ?: getString(R.string.onboarding_sms_resend)
        }

        viewModel.verified.observe(viewLifecycleOwner) { verified ->
            if (verified == true) {
                viewModel.onVerifiedHandled()
                if (findNavController().currentDestination?.id != R.id.smsCodeVerifyFragment) return@observe
                val action = SmsCodeVerifyFragmentDirections
                    .actionSmsCodeVerifyFragmentToAccountTypeFragment(
                        userId = args.userId,
                        email = args.email,
                        phone = args.phone,
                        countryCode = args.countryCode
                    )
                findNavController().navigate(action)
            }
        }
    }

    private fun resend() {
        binding.resendButton.isEnabled = false
        viewModel.resendCode()

        // Re-start SMS consent for the resent code
        startSmsUserConsent()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService<InputMethodManager>()
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            requireContext().unregisterReceiver(smsConsentReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered
        }
        _binding = null
    }
}

private class SmsCodeVerifyViewModelFactory(
    private val application: Application,
    private val phone: String,
    private val countryCode: String
) : ViewModelProvider.AndroidViewModelFactory(application) {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SmsCodeVerifyViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SmsCodeVerifyViewModel(application, phone, countryCode) as T
        }
        return super.create(modelClass)
    }
}
