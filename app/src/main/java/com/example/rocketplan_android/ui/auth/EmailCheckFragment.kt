package com.example.rocketplan_android.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.databinding.FragmentEmailCheckBinding

/**
 * Initial authentication screen that collects the user's email,
 * matching the iOS flow where we determine whether to sign in or sign up next.
 */
class EmailCheckFragment : Fragment() {

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

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService<InputMethodManager>()
        imm?.hideSoftInputFromWindow(binding.emailInput.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
