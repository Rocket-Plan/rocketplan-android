package com.example.rocketplan_android.ui.forgotpassword

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.databinding.FragmentForgotPasswordBinding

/**
 * Forgot Password screen Fragment
 * Allows users to request a password reset email
 */
class ForgotPasswordFragment : Fragment() {

    private var _binding: FragmentForgotPasswordBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ForgotPasswordViewModel by viewModels()
    private val args: ForgotPasswordFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentForgotPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set initial email from args
        viewModel.setInitialEmail(args.email)

        setupInputFields()
        setupButtons()
        observeViewModel()
    }

    private fun setupInputFields() {
        binding.emailInput.apply {
            setInputTypeEmail()
            onTextChanged = { text ->
                viewModel.setEmail(text)
            }
        }
    }

    private fun setupButtons() {
        binding.resetButton.setOnClickListener {
            hideKeyboard()
            viewModel.resetPassword()
        }

        binding.backToLoginButton.setOnClickListener {
            viewModel.navigateBack()
        }
    }

    private fun observeViewModel() {
        // Observe email
        viewModel.email.observe(viewLifecycleOwner) { email ->
            if (binding.emailInput.text != email) {
                binding.emailInput.text = email
            }
        }

        // Observe email errors
        viewModel.emailError.observe(viewLifecycleOwner) { error ->
            binding.emailInput.errorMessage = error
        }

        // Observe loading state
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.resetButton.isEnabled = !isLoading
            binding.emailInput.isEnabled = !isLoading
        }

        // Observe success message
        viewModel.successMessage.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                binding.successText.text = message
                binding.successText.visibility = View.VISIBLE
                binding.errorText.visibility = View.GONE
            } else {
                binding.successText.visibility = View.GONE
            }
        }

        // Observe error message
        viewModel.errorMessage.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                binding.errorText.text = message
                binding.errorText.visibility = View.VISIBLE
                binding.successText.visibility = View.GONE
            } else {
                binding.errorText.visibility = View.GONE
            }
        }

        // Observe navigate back
        viewModel.navigateBack.observe(viewLifecycleOwner) { navigate ->
            if (navigate) {
                findNavController().navigateUp()
                viewModel.onNavigatedBack()
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
