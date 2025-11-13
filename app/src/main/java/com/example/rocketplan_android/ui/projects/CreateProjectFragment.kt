package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Initial screen in the Android create-project flow. Mirrors the iOS UI so users
 * can start by providing the loss address before moving deeper into project setup.
 */
class CreateProjectFragment : Fragment() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var addressInputLayout: TextInputLayout
    private lateinit var addressInput: TextInputEditText
    private lateinit var manualEntryLink: TextView
    private lateinit var createProjectButton: MaterialButton

    private val viewModel: CreateProjectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_create_project, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.createProjectToolbar)
        addressInputLayout = view.findViewById(R.id.addressInputLayout)
        addressInput = view.findViewById(R.id.addressInput)
        manualEntryLink = view.findViewById(R.id.manualEntryLink)
        createProjectButton = view.findViewById(R.id.createProjectButton)

        addressInput.doAfterTextChanged {
            if (!it.isNullOrBlank()) {
                addressInputLayout.error = null
                viewModel.clearError()
            }
        }

        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        manualEntryLink.setOnClickListener {
            findNavController().navigate(R.id.action_createProjectFragment_to_manualAddressEntryFragment)
        }

        createProjectButton.setOnClickListener {
            viewModel.createProjectFromQuickAddress(addressInput.text?.toString())
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is CreateProjectEvent.ProjectCreated -> navigateToProject(event.projectId)
                        }
                    }
                }
                launch {
                    viewModel.validationEvents.collect { validation ->
                        when (validation) {
                            CreateProjectValidation.AddressRequired ->
                                addressInputLayout.error = getString(R.string.create_project_address_required)
                            CreateProjectValidation.StreetRequired -> {
                                // Ignore – handled in manual fragment
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: CreateProjectUiState) {
        createProjectButton.isEnabled = !state.isSubmitting
        manualEntryLink.isEnabled = !state.isSubmitting
        toolbar.isEnabled = !state.isSubmitting

        if (!state.errorMessage.isNullOrBlank()) {
            Toast.makeText(requireContext(), state.errorMessage, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    private fun navigateToProject(projectId: Long) {
        val action = CreateProjectFragmentDirections
            .actionCreateProjectFragmentToProjectLandingFragment(projectId)
        findNavController().navigate(action)
    }
}
