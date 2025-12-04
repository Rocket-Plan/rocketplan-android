package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Allows the user to manually type the full project address when search/lookup
 * is not available or does not return the desired property.
 */
class ManualAddressEntryFragment : Fragment() {

    private lateinit var saveButton: MaterialButton
    private lateinit var streetLayout: TextInputLayout
    private lateinit var streetInput: TextInputEditText
    private lateinit var unitInput: TextInputEditText
    private lateinit var cityLayout: TextInputLayout
    private lateinit var cityInput: TextInputEditText
    private lateinit var stateLayout: TextInputLayout
    private lateinit var stateInput: TextInputEditText
    private lateinit var countryLayout: TextInputLayout
    private lateinit var countryInput: TextInputEditText
    private lateinit var postalLayout: TextInputLayout
    private lateinit var postalInput: TextInputEditText

    private val viewModel: CreateProjectViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_manual_address, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        saveButton = view.findViewById(R.id.manualAddressSaveButton)
        streetLayout = view.findViewById(R.id.manualStreetLayout)
        streetInput = view.findViewById(R.id.manualStreetInput)
        unitInput = view.findViewById(R.id.manualUnitInput)
        cityLayout = view.findViewById(R.id.manualCityLayout)
        cityInput = view.findViewById(R.id.manualCityInput)
        stateLayout = view.findViewById(R.id.manualStateLayout)
        stateInput = view.findViewById(R.id.manualStateInput)
        countryLayout = view.findViewById(R.id.manualCountryLayout)
        countryInput = view.findViewById(R.id.manualCountryInput)
        postalLayout = view.findViewById(R.id.manualPostalLayout)
        postalInput = view.findViewById(R.id.manualPostalInput)

        streetInput.doAfterTextChanged {
            if (!it.isNullOrBlank()) {
                streetLayout.error = null
                viewModel.clearError()
            }
        }

        cityInput.doAfterTextChanged {
            if (!it.isNullOrBlank()) {
                cityLayout.error = null
                viewModel.clearError()
            }
        }

        stateInput.doAfterTextChanged {
            if (!it.isNullOrBlank()) {
                stateLayout.error = null
                viewModel.clearError()
            }
        }

        countryInput.doAfterTextChanged {
            if (!it.isNullOrBlank()) {
                countryLayout.error = null
                viewModel.clearError()
            }
        }

        postalInput.doAfterTextChanged {
            if (!it.isNullOrBlank()) {
                postalLayout.error = null
                viewModel.clearError()
            }
        }

        saveButton.setOnClickListener {
            viewModel.createProjectFromManualAddress(
                street = streetInput.text?.toString(),
                unit = unitInput.text?.toString(),
                city = cityInput.text?.toString(),
                state = stateInput.text?.toString(),
                postalCode = postalInput.text?.toString(),
                country = countryInput.text?.toString()
            )
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        saveButton.isEnabled = !state.isSubmitting
                        if (!state.errorMessage.isNullOrBlank()) {
                            Toast.makeText(requireContext(), state.errorMessage, Toast.LENGTH_LONG).show()
                            viewModel.clearError()
                        }
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
                            CreateProjectValidation.StreetRequired ->
                                streetLayout.error = getString(R.string.manual_address_street_error)
                            CreateProjectValidation.CityRequired ->
                                cityLayout.error = getString(R.string.manual_address_city_error)
                            CreateProjectValidation.StateRequired ->
                                stateLayout.error = getString(R.string.manual_address_state_error)
                            CreateProjectValidation.CountryRequired ->
                                countryLayout.error = getString(R.string.manual_address_country_error)
                            CreateProjectValidation.PostalRequired ->
                                postalLayout.error = getString(R.string.manual_address_postal_error)
                            CreateProjectValidation.AddressRequired -> {
                                // Ignore – quick create screen handles this case
                            }
                        }
                    }
                }
            }
        }
    }

    private fun navigateToProject(projectId: Long) {
        val action = ManualAddressEntryFragmentDirections
            .actionManualAddressEntryFragmentToProjectLandingFragment(projectId)
        findNavController().navigate(action)
    }
}
