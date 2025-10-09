package com.example.rocketplan_android.ui.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentLoginBinding

/**
 * Login screen Fragment
 * Allows users to sign in with email or Google Sign-In
 */
class LoginFragment : Fragment() {

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
        // Google Sign-In button - currently not implemented
        // TODO: Implement Google Sign-In functionality
        binding.googleSignInButton.setOnClickListener {
            hideKeyboard()
            // Google Sign-In not yet implemented
            Toast.makeText(requireContext(), "Google Sign-In not yet implemented", Toast.LENGTH_SHORT).show()
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
