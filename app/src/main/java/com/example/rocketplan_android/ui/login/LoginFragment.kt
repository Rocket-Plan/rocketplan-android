package com.example.rocketplan_android.ui.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentLoginBinding

/**
 * Login screen Fragment
 * Allows users to sign in with email/password or biometric authentication
 */
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LoginViewModel by viewModels()

    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

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

        setupBiometricAuth()
        setupInputFields()
        setupButtons()
        observeViewModel()
    }

    private fun setupBiometricAuth() {
        val executor = ContextCompat.getMainExecutor(requireContext())

        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(
                        requireContext(),
                        "Authentication error: $errString",
                        Toast.LENGTH_SHORT
                    ).show()
                    viewModel.onBiometricPromptDismissed()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    viewModel.signInWithBiometric()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(
                        requireContext(),
                        "Authentication failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sign in to RocketPlan")
            .setSubtitle("Use your biometric credential")
            .setNegativeButtonText("Use password")
            .build()
    }

    private fun setupInputFields() {
        // Configure email input
        binding.emailInput.apply {
            setInputTypeEmail()
            onTextChanged = { text ->
                viewModel.setEmail(text)
            }
        }

        // Configure password input
        binding.passwordInput.apply {
            setInputTypePassword()
            onTextChanged = { text ->
                viewModel.setPassword(text)
            }
        }

        // Configure Remember Me checkbox
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
            viewModel.forgotPassword()
        }
    }

    private fun observeViewModel() {
        // Observe email
        viewModel.email.observe(viewLifecycleOwner) { email ->
            if (binding.emailInput.text != email) {
                binding.emailInput.text = email
            }
        }

        // Observe password
        viewModel.password.observe(viewLifecycleOwner) { password ->
            if (binding.passwordInput.text != password) {
                binding.passwordInput.text = password
            }
        }

        // Observe Remember Me
        viewModel.rememberMe.observe(viewLifecycleOwner) { rememberMe ->
            if (binding.rememberMeCheckbox.isChecked != rememberMe) {
                binding.rememberMeCheckbox.isChecked = rememberMe
            }
        }

        // Observe email errors
        viewModel.emailError.observe(viewLifecycleOwner) { error ->
            binding.emailInput.errorMessage = error
        }

        // Observe password errors
        viewModel.passwordError.observe(viewLifecycleOwner) { error ->
            binding.passwordInput.errorMessage = error
        }

        // Observe loading state
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.signInButton.isEnabled = !isLoading
            binding.emailInput.isEnabled = !isLoading
            binding.passwordInput.isEnabled = !isLoading
            binding.rememberMeCheckbox.isEnabled = !isLoading
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

        // Observe sign in success
        viewModel.signInSuccess.observe(viewLifecycleOwner) { success ->
            if (success) {
                Toast.makeText(requireContext(), "Sign in successful!", Toast.LENGTH_SHORT).show()
                findNavController().navigate(R.id.action_loginFragment_to_nav_home)
                viewModel.onSignInSuccessHandled()
            }
        }

        // Observe forgot password navigation
        viewModel.navigateToForgotPassword.observe(viewLifecycleOwner) { email ->
            if (email != null) {
                // Navigate to forgot password screen with email
                val action = LoginFragmentDirections.actionLoginFragmentToForgotPasswordFragment(email)
                findNavController().navigate(action)
                viewModel.onForgotPasswordNavigated()
            }
        }

        // Observe biometric prompt
        viewModel.biometricPromptVisible.observe(viewLifecycleOwner) { visible ->
            if (visible && isBiometricAvailable()) {
                biometricPrompt.authenticate(promptInfo)
            }
        }
    }

    private fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(requireContext())
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
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
