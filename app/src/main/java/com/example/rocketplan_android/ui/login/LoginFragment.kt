package com.example.rocketplan_android.ui.login

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.BuildConfig
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentLoginBinding

/**
 * Login screen Fragment
 * Allows users to sign in with email or Google Sign-In via OAuth
 */
class LoginFragment : Fragment() {

    companion object {
        private const val TAG = "LoginFragment"
    }

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LoginViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupInputFields()
        setupButtons()
        observeViewModel()
    }

    private fun setupInputFields() {
        // Configure email input with Enter key handling
        binding.emailInput.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            // When user presses Enter on email field, just validate it
            // (actual sign-in happens via button)
            true
        }
    }

    private fun setupButtons() {
        // Google Sign-In button
        binding.googleSignInButton.setOnClickListener {
            hideKeyboard()
            signInWithGoogle()
        }
    }

    /**
     * Initiate Google Sign-In flow using OAuth via Chrome Custom Tabs
     * Matches iOS implementation using backend-driven OAuth
     */
    private fun signInWithGoogle() {
        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "Starting Google OAuth flow...")
        }

        // Construct OAuth URL based on environment
        val schema = when (BuildConfig.ENVIRONMENT) {
            "DEV" -> "rocketplan-dev"
            "STAGING" -> "rocketplan-staging"
            "PROD" -> "rocketplan"
            else -> "rocketplan-dev"
        }

        val oauthUrl = "${BuildConfig.API_BASE_URL}/oauth2/redirect/google?schema=$schema"

        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "OAuth URL: $oauthUrl")
            Log.d(TAG, "Callback schema: $schema")
        }

        try {
            // Launch Chrome Custom Tab for OAuth flow
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()

            customTabsIntent.launchUrl(requireContext(), Uri.parse(oauthUrl))

            if (BuildConfig.ENABLE_LOGGING) {
                Log.d(TAG, "Chrome Custom Tab launched for OAuth")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching OAuth flow", e)
            Toast.makeText(
                requireContext(),
                "Failed to open Google Sign-In: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun observeViewModel() {
        // Observe loading state
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.emailInput.isEnabled = !isLoading
            binding.googleSignInButton.isEnabled = !isLoading
        }

        // Observe error messages
        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                binding.errorText.text = error
                binding.errorText.visibility = View.VISIBLE
            } else {
                binding.errorText.visibility = View.GONE
            }
        }

        // Note: OAuth sign-in success is handled by MainActivity via deep link callback
        // No need to observe signInSuccess for Google OAuth flow
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
