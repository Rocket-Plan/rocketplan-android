package com.example.rocketplan_android.ui.login

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.BuildConfig
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentLoginBinding
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch

/**
 * Login screen Fragment
 * Allows users to sign in with email or Google Sign-In
 */
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LoginViewModel by viewModels()
    private lateinit var credentialManager: CredentialManager

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

        credentialManager = CredentialManager.create(requireContext())

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
     * Initiate Google Sign-In flow using Credential Manager API
     */
    private fun signInWithGoogle() {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId("411614400810-76fu021sm4ap6kbfk9qf5uiaec475qn1.apps.googleusercontent.com")
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = requireContext()
                )
                handleSignIn(result)
            } catch (e: GetCredentialException) {
                handleGoogleSignInFailure(e)
            }
        }
    }

    /**
     * Handle successful Google Sign-In credential response
     */
    private fun handleSignIn(result: GetCredentialResponse) {
        when (val credential = result.credential) {
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        val idToken = googleIdTokenCredential.idToken

                        if (BuildConfig.ENABLE_LOGGING) {
                            Log.d("LoginFragment", "Google ID Token received: ${idToken.take(20)}...")
                        }

                        // Send ID token to backend via ViewModel
                        viewModel.signInWithGoogle(idToken)

                    } catch (e: GoogleIdTokenParsingException) {
                        Log.e("LoginFragment", "Invalid Google ID token", e)
                        Toast.makeText(
                            requireContext(),
                            "Failed to process Google Sign-In",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Log.e("LoginFragment", "Unexpected credential type: ${credential.type}")
                    Toast.makeText(
                        requireContext(),
                        "Unexpected credential type",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            else -> {
                Log.e("LoginFragment", "Unexpected credential: ${credential}")
                Toast.makeText(
                    requireContext(),
                    "Unexpected credential",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Handle Google Sign-In failures
     */
    private fun handleGoogleSignInFailure(e: GetCredentialException) {
        Log.e("LoginFragment", "Google Sign-In failed", e)
        Toast.makeText(
            requireContext(),
            "Google Sign-In failed: ${e.message}",
            Toast.LENGTH_SHORT
        ).show()
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

        // Observe sign in success
        viewModel.signInSuccess.observe(viewLifecycleOwner) { success ->
            if (success) {
                Toast.makeText(requireContext(), "Sign in successful!", Toast.LENGTH_SHORT).show()
                findNavController().navigate(R.id.action_loginFragment_to_nav_home)
                viewModel.onSignInSuccessHandled()
            }
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
