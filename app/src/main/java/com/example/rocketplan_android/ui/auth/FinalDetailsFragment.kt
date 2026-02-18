package com.example.rocketplan_android.ui.auth

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.databinding.FragmentFinalDetailsBinding
import kotlinx.coroutines.launch

class FinalDetailsFragment : Fragment() {

    private var _binding: FragmentFinalDetailsBinding? = null
    private val binding get() = _binding!!

    private val args: FinalDetailsFragmentArgs by navArgs()

    private val viewModel: FinalDetailsViewModel by viewModels {
        FinalDetailsViewModelFactory(
            requireActivity().application,
            args.userId,
            args.isCreating,
            args.email
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFinalDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show company name field only when creating
        binding.companyNameInputLayout.visibility = if (args.isCreating) View.VISIBLE else View.GONE

        setupInputListeners()
        setupObservers()
    }

    private fun setupInputListeners() {
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.firstNameInput.doAfterTextChanged { text ->
            viewModel.setFirstName(text?.toString() ?: "")
        }

        binding.lastNameInput.doAfterTextChanged { text ->
            viewModel.setLastName(text?.toString() ?: "")
        }

        binding.companyNameInput.doAfterTextChanged { text ->
            viewModel.setCompanyName(text?.toString() ?: "")
        }

        binding.finishButton.setOnClickListener {
            hideKeyboard()
            viewModel.finish()
        }
    }

    private fun setupObservers() {
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.finishButton.isEnabled = !isLoading
            binding.firstNameInput.isEnabled = !isLoading
            binding.lastNameInput.isEnabled = !isLoading
            binding.companyNameInput.isEnabled = !isLoading
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            binding.errorText.text = error
            binding.errorText.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        viewModel.setupComplete.observe(viewLifecycleOwner) { complete ->
            if (complete == true) {
                viewModel.onSetupCompleteHandled()
                if (findNavController().currentDestination?.id != R.id.finalDetailsFragment) return@observe
                // Trigger initial sync on activity scope so it survives navigation
                (requireActivity() as? AppCompatActivity)?.lifecycleScope?.launch {
                    (requireActivity().application as RocketPlanApplication)
                        .syncQueueManager
                        .ensureInitialSync()
                }
                val action = FinalDetailsFragmentDirections.actionFinalDetailsFragmentToNavHome()
                findNavController().navigate(action)
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

private class FinalDetailsViewModelFactory(
    private val application: Application,
    private val userId: Long,
    private val isCreating: Boolean,
    private val email: String
) : ViewModelProvider.AndroidViewModelFactory(application) {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FinalDetailsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FinalDetailsViewModel(application, userId, isCreating, email) as T
        }
        return super.create(modelClass)
    }
}
