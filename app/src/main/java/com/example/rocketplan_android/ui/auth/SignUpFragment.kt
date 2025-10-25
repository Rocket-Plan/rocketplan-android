package com.example.rocketplan_android.ui.auth

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
        setupInputListeners()
        setupObservers()
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
                viewLifecycleOwner.lifecycleScope.launch {
                    (requireActivity().application as RocketPlanApplication)
                        .syncQueueManager
                        .ensureInitialSync()
                }
                val action = SignUpFragmentDirections.actionSignUpFragmentToNavHome()
                findNavController().navigate(action)
                viewModel.onSignUpSuccessHandled()
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
