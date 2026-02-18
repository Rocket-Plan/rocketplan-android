package com.example.rocketplan_android.ui.auth

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.databinding.FragmentSignUpBinding

/**
 * Collects password credentials for new users, mirroring the iOS sign-up flow.
 */
class SignUpFragment : Fragment() {

    private var _binding: FragmentSignUpBinding? = null
    private val binding get() = _binding!!

    private val args: SignUpFragmentArgs by navArgs()

    private val viewModel: SignUpViewModel by viewModels {
        SignUpViewModelFactory(requireActivity().application, args.email)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSignUpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }
        setupInputListeners()
        setupObservers()
        setupLegalLinks()
    }

    private fun setupLegalLinks() {
        val fullText = getString(R.string.sign_up_legal_links)
        val privacyLabel = getString(R.string.privacy_policy)
        val termsLabel = getString(R.string.terms_and_conditions)
        val spannable = SpannableString(fullText)
        val purple = ContextCompat.getColor(requireContext(), R.color.main_purple)

        val privacyStart = fullText.indexOf(privacyLabel)
        if (privacyStart >= 0) {
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openUrl("https://rocketplantech.com/privacy-policy/")
                }
            }, privacyStart, privacyStart + privacyLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(purple), privacyStart, privacyStart + privacyLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val termsStart = fullText.indexOf(termsLabel)
        if (termsStart >= 0) {
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openUrl("https://rocketplantech.com/terms-and-conditions/")
                }
            }, termsStart, termsStart + termsLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(purple), termsStart, termsStart + termsLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        binding.legalLinksText.text = spannable
        binding.legalLinksText.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun openUrl(url: String) {
        CustomTabsIntent.Builder().build().launchUrl(requireContext(), Uri.parse(url))
    }

    private fun setupInputListeners() {
        binding.passwordInput.doAfterTextChanged { text ->
            viewModel.setPassword(text?.toString() ?: "")
        }

        binding.confirmPasswordInput.doAfterTextChanged { text ->
            viewModel.setConfirmPassword(text?.toString() ?: "")
        }

        binding.confirmPasswordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                viewModel.signUp()
                true
            } else {
                false
            }
        }

        binding.createAccountButton.setOnClickListener {
            hideKeyboard()
            viewModel.signUp()
        }
    }

    private fun setupObservers() {
        viewModel.email.observe(viewLifecycleOwner) { email ->
            if (binding.emailInput.text?.toString() != email) {
                binding.emailInput.setText(email)
            }
        }

        viewModel.passwordError.observe(viewLifecycleOwner) { error ->
            binding.passwordInputLayout.error = error
        }

        viewModel.emailError.observe(viewLifecycleOwner) { error ->
            binding.emailInputLayout.error = error
        }

        viewModel.confirmPasswordMessage.observe(viewLifecycleOwner) { message ->
            binding.confirmPasswordStatusText.text = message
            binding.confirmPasswordStatusText.visibility = if (message.isNullOrBlank()) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        viewModel.passwordsMatch.observe(viewLifecycleOwner) { matches ->
            matches?.let {
                val colorRes = if (it) R.color.dark_green else R.color.warning_red
                binding.confirmPasswordStatusText.setTextColor(
                    ContextCompat.getColor(requireContext(), colorRes)
                )
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            binding.errorText.text = error
            binding.errorText.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        viewModel.isFormValid.observe(viewLifecycleOwner) { isValid ->
            binding.createAccountButton.isEnabled = isValid && viewModel.isLoading.value != true
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.passwordInputLayout.isEnabled = !isLoading
            binding.confirmPasswordInputLayout.isEnabled = !isLoading
            binding.createAccountButton.isEnabled = !isLoading && (viewModel.isFormValid.value == true)
        }

        viewModel.signUpSuccess.observe(viewLifecycleOwner) { success ->
            if (success == true) {
                viewModel.onSignUpSuccessHandled()
                if (findNavController().currentDestination?.id != R.id.signUpFragment) return@observe

                val session = viewModel.authSession.value
                val userId = session?.user?.id ?: 0L
                val email = viewModel.email.value ?: args.email

                // Guard: if we somehow have no userId, show error
                if (userId <= 0L) {
                    viewModel.setError("Account was created but user ID is missing. Please try signing in.")
                    return@observe
                }

                // Check if user already has a company — skip onboarding if so
                val hasCompany = session?.user?.getPrimaryCompanyId() != null
                if (hasCompany) {
                    (requireActivity() as? AppCompatActivity)?.lifecycleScope?.launch {
                        (requireActivity().application as RocketPlanApplication)
                            .syncQueueManager
                            .ensureInitialSync()
                    }
                    val action = SignUpFragmentDirections.actionSignUpFragmentToNavHome()
                    findNavController().navigate(action)
                } else {
                    val action = SignUpFragmentDirections
                        .actionSignUpFragmentToPhoneVerificationFragment(
                            userId = userId,
                            email = email
                        )
                    findNavController().navigate(action)
                }
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

private class SignUpViewModelFactory(
    private val application: Application,
    private val email: String
) : ViewModelProvider.AndroidViewModelFactory(application) {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SignUpViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SignUpViewModel(application, email) as T
        }
        return super.create(modelClass)
    }
}
