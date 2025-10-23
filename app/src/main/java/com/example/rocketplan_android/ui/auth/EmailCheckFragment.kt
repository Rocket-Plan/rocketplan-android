package com.example.rocketplan_android.ui.auth

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.BuildConfig
import com.example.rocketplan_android.databinding.FragmentEmailCheckBinding

/**
 * Initial authentication screen that collects the user's email
 * and offers social sign-in options, mirroring the iOS onboarding flow.
 */
class EmailCheckFragment : Fragment() {

    companion object {
        private const val TAG = "EmailCheckFragment"
        private const val PROVIDER_FACEBOOK = "facebook"
        private const val PROVIDER_GOOGLE = "google"
        private const val PROVIDER_APPLE = "apple"
    }

    private var _binding: FragmentEmailCheckBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EmailCheckViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEmailCheckBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupInputListeners()
        setupSocialButtons()
        setupObservers()
    }

    private fun setupInputListeners() {
        binding.emailInput.doAfterTextChanged { text ->
            viewModel.setEmail(text?.toString()?.trim() ?: "")
        }

        binding.emailInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                viewModel.submitEmail()
                true
            } else {
                false
            }
        }

        binding.continueButton.setOnClickListener {
            hideKeyboard()
            viewModel.submitEmail()
        }
    }

    private fun setupSocialButtons() {
        binding.facebookButton.setOnClickListener {
            hideKeyboard()
            launchOAuth(PROVIDER_FACEBOOK)
        }
        binding.googleButton.setOnClickListener {
            hideKeyboard()
            launchOAuth(PROVIDER_GOOGLE)
        }
        binding.appleButton.setOnClickListener {
            hideKeyboard()
            launchOAuth(PROVIDER_APPLE)
        }
    }

    private fun setupObservers() {
        viewModel.email.observe(viewLifecycleOwner) { email ->
            if (binding.emailInput.text?.toString() != email) {
                binding.emailInput.setText(email)
                binding.emailInput.setSelection(email.length)
            }
        }

        viewModel.emailError.observe(viewLifecycleOwner) { error ->
            binding.emailInputLayout.error = error
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            binding.errorText.text = error
            binding.errorText.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.continueButton.isEnabled = !isLoading
            binding.emailInput.isEnabled = !isLoading
            binding.facebookButton.isEnabled = !isLoading
            binding.googleButton.isEnabled = !isLoading
            binding.appleButton.isEnabled = !isLoading
        }

        viewModel.navigateToSignIn.observe(viewLifecycleOwner) { email ->
            if (!email.isNullOrBlank()) {
                val action =
                    EmailCheckFragmentDirections.actionEmailCheckFragmentToLoginFragment(email)
                findNavController().navigate(action)
                viewModel.onNavigateToSignInHandled()
            }
        }

        viewModel.navigateToSignUp.observe(viewLifecycleOwner) { email ->
            if (!email.isNullOrBlank()) {
                val action =
                    EmailCheckFragmentDirections.actionEmailCheckFragmentToSignUpFragment(email)
                findNavController().navigate(action)
                viewModel.onNavigateToSignUpHandled()
            }
        }
    }

    private fun launchOAuth(provider: String) {
        val schema = when (BuildConfig.ENVIRONMENT) {
            "DEV" -> "rocketplan-dev"
            "STAGING" -> "rocketplan-staging"
            "PROD" -> "rocketplan"
            else -> "rocketplan-dev"
        }

        val oauthUrl = "${BuildConfig.API_BASE_URL}/oauth2/redirect/$provider?schema=$schema"

        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "Starting $provider OAuth flow: $oauthUrl")
        }

        runCatching {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(requireContext(), Uri.parse(oauthUrl))
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to launch $provider OAuth flow", throwable)
            Toast.makeText(
                requireContext(),
                "Unable to start $provider sign-in. Please try again.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService<InputMethodManager>()
        imm?.hideSoftInputFromWindow(binding.emailInput.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
