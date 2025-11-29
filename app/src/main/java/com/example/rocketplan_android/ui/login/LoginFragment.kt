package com.example.rocketplan_android.ui.login

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import kotlinx.coroutines.launch
import com.example.rocketplan_android.BuildConfig
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.databinding.FragmentLoginBinding

/**
 * Login screen Fragment
 * Allows users to sign in with email/password or Google Sign-In via OAuth
 */
class LoginFragment : Fragment() {

    companion object {
        private const val TAG = "LoginFragment"
    }

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val args: LoginFragmentArgs by navArgs()

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

        val emailArg = args.email
        if (!emailArg.isNullOrBlank()) {
            viewModel.setEmail(emailArg)
        }
    }

    private fun setupInputFields() {
        binding.emailInput.doAfterTextChanged { text ->
            viewModel.setEmail(text?.toString()?.trim() ?: "")
        }

        binding.passwordInput.doAfterTextChanged { text ->
            viewModel.setPassword(text?.toString() ?: "")
        }

        binding.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                viewModel.signIn()
                true
            } else {
                false
            }
        }

        binding.rememberMeCheckbox.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setRememberMe(isChecked)
        }
    }

    private fun setupButtons() {
        binding.signInButton.setOnClickListener {
            hideKeyboard()
            viewModel.signIn()
        }

        binding.forgotPasswordButton.setOnClickListener {
            hideKeyboard()
            viewModel.forgotPassword()
        }

        binding.googleSignInButton.setOnClickListener {
            hideKeyboard()
            signInWithGoogle()
        }
    }

    private fun observeViewModel() {
        viewModel.email.observe(viewLifecycleOwner) { email ->
            if (binding.emailInput.text?.toString() != email) {
                binding.emailInput.setText(email)
                binding.emailInput.setSelection(email.length)
            }
        }

        viewModel.password.observe(viewLifecycleOwner) { password ->
            if (binding.passwordInput.text?.toString() != password) {
                binding.passwordInput.setText(password)
                binding.passwordInput.setSelection(password.length)
            }
        }

        viewModel.rememberMe.observe(viewLifecycleOwner) { remember ->
            if (binding.rememberMeCheckbox.isChecked != remember) {
                binding.rememberMeCheckbox.setOnCheckedChangeListener(null)
                binding.rememberMeCheckbox.isChecked = remember
                binding.rememberMeCheckbox.setOnCheckedChangeListener { _, isChecked ->
                    viewModel.setRememberMe(isChecked)
                }
            }
        }

        viewModel.emailError.observe(viewLifecycleOwner) { error ->
            binding.emailInputLayout.error = error
        }

        viewModel.passwordError.observe(viewLifecycleOwner) { error ->
            binding.passwordInputLayout.error = error
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            binding.errorText.text = error
            binding.errorText.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.signInButton.isEnabled = !isLoading && (viewModel.emailError.value == null && viewModel.passwordError.value == null)
            binding.emailInputLayout.isEnabled = !isLoading
            binding.passwordInputLayout.isEnabled = !isLoading
            binding.googleSignInButton.isEnabled = !isLoading
        }

        viewModel.navigateToForgotPassword.observe(viewLifecycleOwner) { email ->
            if (email != null) {
                val action = LoginFragmentDirections.actionLoginFragmentToForgotPasswordFragment(email)
                findNavController().navigate(action)
                viewModel.onForgotPasswordNavigated()
            }
        }

        viewModel.signInSuccess.observe(viewLifecycleOwner) { success ->
            if (success == true) {
                viewLifecycleOwner.lifecycleScope.launch {
                    (requireActivity().application as RocketPlanApplication)
                        .syncQueueManager
                        .ensureInitialSync()
                }
                val action = LoginFragmentDirections.actionLoginFragmentToNavHome()
                findNavController().navigate(action)
                viewModel.onSignInSuccessHandled()
            }
        }

        // Biometric prompt visibility is managed by activity for now
    }

    /**
     * Initiate Google Sign-In flow using OAuth via Chrome Custom Tabs
     * Matches iOS implementation using backend-driven OAuth
     */
    private fun signInWithGoogle() {
        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "Starting Google OAuth flow...")
        }

        val schema = when (BuildConfig.ENVIRONMENT) {
            "DEV" -> "rocketplan-local"
            "STAGING" -> "rocketplan-staging"
            "PROD" -> "rocketplan"
            else -> "rocketplan-local"
        }

        val oauthUrl = "${BuildConfig.API_BASE_URL}/oauth2/redirect/google?schema=$schema"

        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "OAuth URL: $oauthUrl")
            Log.d(TAG, "Callback schema: $schema")
        }

        runCatching {
            val action =
                LoginFragmentDirections.actionLoginFragmentToOauthWebViewFragment(
                    url = oauthUrl,
                    title = getString(R.string.google_sign_in_title)
                )
            findNavController().navigate(action)
        }.onFailure { error ->
            Log.e(TAG, "Error launching OAuth flow", error)
            Toast.makeText(
                requireContext(),
                getString(R.string.error_opening_google_sign_in, error.message),
                Toast.LENGTH_LONG
            ).show()
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
