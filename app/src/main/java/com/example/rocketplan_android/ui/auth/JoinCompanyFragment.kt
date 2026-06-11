package com.example.rocketplan_android.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentJoinCompanyBinding

class JoinCompanyFragment : Fragment() {

    private var _binding: FragmentJoinCompanyBinding? = null
    private val binding get() = _binding!!

    private val args: JoinCompanyFragmentArgs by navArgs()
    private val viewModel: JoinCompanyViewModel by viewModels {
        JoinCompanyViewModelFactory(requireActivity().application, args.userId)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentJoinCompanyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.companyCodeInput.doAfterTextChanged { text ->
            viewModel.setCompanyCode(text?.toString() ?: "")
        }

        binding.joinButton.setOnClickListener {
            viewModel.join()
        }

        binding.createInsteadButton.setOnClickListener {
            val action = JoinCompanyFragmentDirections
                .actionJoinCompanyFragmentToFinalDetailsFragment(
                    userId = args.userId,
                    email = args.email,
                    isCreating = true,
                    phone = args.phone,
                    countryCode = args.countryCode
                )
            findNavController().navigate(action)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.isVisible = isLoading
            binding.joinButton.isEnabled = !isLoading
            binding.companyCodeInput.isEnabled = !isLoading
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            binding.errorText.text = error
            binding.errorText.isVisible = !error.isNullOrBlank()
        }

        viewModel.joinComplete.observe(viewLifecycleOwner) { complete ->
            if (complete == true) {
                viewModel.onJoinCompleteHandled()
                val navOptions = androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.emailCheckFragment, true)
                    .build()
                findNavController().navigate(R.id.nav_projects, null, navOptions)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class JoinCompanyViewModelFactory(
    private val application: android.app.Application,
    private val userId: Long
) : ViewModelProvider.AndroidViewModelFactory(application) {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(JoinCompanyViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return JoinCompanyViewModel(application, userId) as T
        }
        return super.create(modelClass)
    }
}
