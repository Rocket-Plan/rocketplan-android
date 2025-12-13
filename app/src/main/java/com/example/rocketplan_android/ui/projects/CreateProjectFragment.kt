package com.example.rocketplan_android.ui.projects

import android.Manifest
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.R
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Initial screen in the Android create-project flow. Mirrors the iOS UI so users
 * can start by providing the loss address before moving deeper into project setup.
 */
class CreateProjectFragment : Fragment() {

    private lateinit var addressInputLayout: TextInputLayout
    private lateinit var addressInput: MaterialAutoCompleteTextView
    private lateinit var manualEntryLink: TextView
    private lateinit var createProjectButton: MaterialButton
    private lateinit var addressAdapter: ArrayAdapter<String>
    private var lastSuggestions: List<String> = emptyList()
    private var detectedLatLng: Pair<Double, Double>? = null
    private var isProgrammaticallySettingAddress = false
    private var autoFilledAddress: String? = null

    private val viewModel: CreateProjectViewModel by activityViewModels()
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(requireContext())
    }
    private val geocoder by lazy { Geocoder(requireContext(), Locale.getDefault()) }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) fetchCurrentAddress()
    }

    private var locationCancellationToken: CancellationTokenSource? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_create_project, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        addressInputLayout = view.findViewById(R.id.addressInputLayout)
        addressInput = view.findViewById(R.id.addressInput)
        manualEntryLink = view.findViewById(R.id.manualEntryLink)
        createProjectButton = view.findViewById(R.id.createProjectButton)
        addressAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, mutableListOf())

        addressInput.setAdapter(addressAdapter)
        addressInput.threshold = 0
        addressInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && addressAdapter.count > 0 && addressInput.text.isNullOrBlank()) {
                addressInput.showDropDown()
            }
        }
        addressInput.setOnClickListener {
            if (addressAdapter.count > 0) {
                addressInput.showDropDown()
            }
        }

        addressInput.doAfterTextChanged {
            if (!it.isNullOrBlank()) {
                addressInputLayout.error = null
                viewModel.clearError()
            }
            if (!isProgrammaticallySettingAddress) {
                handleUserEditedAddress(it?.toString().orEmpty())
            }
        }

        manualEntryLink.setOnClickListener {
            findNavController().navigate(R.id.action_createProjectFragment_to_manualAddressEntryFragment)
        }

        createProjectButton.setOnClickListener {
            viewModel.createProjectFromQuickAddress(
                addressInput.text?.toString(),
                latitude = detectedLatLng?.first,
                longitude = detectedLatLng?.second
            )
        }

        maybePrefillAddressFromLocation()
        observeViewModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        locationCancellationToken?.cancel()
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
                            CreateProjectValidation.CityRequired,
                            CreateProjectValidation.StateRequired,
                            CreateProjectValidation.CountryRequired,
                            CreateProjectValidation.PostalRequired -> {
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
        updateAddressSuggestions(state.recentAddresses)

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

    private fun updateAddressSuggestions(addresses: List<String>) {
        if (addresses == lastSuggestions) return
        lastSuggestions = addresses
        addressAdapter.clear()
        addressAdapter.addAll(addresses)
        addressAdapter.notifyDataSetChanged()
        addressInputLayout.isEndIconVisible = addresses.isNotEmpty()
        if (addressInput.hasFocus() && addressInput.text.isNullOrBlank() && addresses.isNotEmpty()) {
            addressInput.showDropDown()
        }
    }

    private fun maybePrefillAddressFromLocation() {
        if (hasLocationPermission()) {
            fetchCurrentAddress()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    private fun fetchCurrentAddress() {
        val token = CancellationTokenSource()
        locationCancellationToken?.cancel()
        locationCancellationToken = token

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            token.token
        ).addOnSuccessListener { location ->
            if (location == null || !isAdded) return@addOnSuccessListener

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val addressText = runCatching {
                    reverseGeocode(location.latitude, location.longitude)
                }.getOrNull()?.let { address ->
                    formatAddress(address)
                }.also { result ->
                    if (result == null) {
                        logGeocodeFailure("No address from geocoder")
                    }
                }

                if (addressText.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            Toast.makeText(
                                requireContext(),
                                R.string.map_location_unavailable,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    // Only prefill if the user hasn't started typing something else.
                    if (!isAdded || !addressInput.text.isNullOrBlank()) return@withContext
                    detectedLatLng = location.latitude to location.longitude
                    isProgrammaticallySettingAddress = true
                    addressInput.setText(addressText)
                    addressInput.setSelection(addressText.length)
                    isProgrammaticallySettingAddress = false
                    autoFilledAddress = addressText
                    viewModel.primeRecentAddress(addressText)
                }
            }
        }.addOnFailureListener { error ->
            logGeocodeFailure("Location lookup failed", error)
            if (isAdded) {
                Toast.makeText(
                    requireContext(),
                    R.string.map_location_unavailable,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun formatAddress(address: android.location.Address): String {
        val line1 = listOfNotNull(address.subThoroughfare, address.thoroughfare)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
        val locality = address.locality?.takeIf { it.isNotBlank() }
        val adminArea = address.adminArea?.takeIf { it.isNotBlank() }
        val postal = address.postalCode?.takeIf { it.isNotBlank() }
        val country = address.countryName?.takeIf { it.isNotBlank() }

        return listOfNotNull(line1, locality, adminArea, postal, country)
            .joinToString(", ")
            .ifBlank { address.getAddressLine(0) ?: "" }
    }

    private fun handleUserEditedAddress(currentText: String) {
        if (currentText.isBlank()) {
            detectedLatLng = null
            autoFilledAddress = null
            return
        }

        val autoFill = autoFilledAddress ?: return
        val commonPrefix = currentText.commonPrefixWith(autoFill, ignoreCase = true)
        val hasMeaningfulChange = commonPrefix.length < autoFill.length / 2
        if (hasMeaningfulChange) {
            detectedLatLng = null
            autoFilledAddress = null
        }
    }

    private fun logGeocodeFailure(message: String, error: Exception? = null) {
        if (error != null) {
            android.util.Log.w("CreateProjectFragment", message, error)
        } else {
            android.util.Log.w("CreateProjectFragment", message)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(latitude: Double, longitude: Double): Address? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                    continuation.resume(addresses.firstOrNull())
                }
            }
        } else {
            geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
        }
    }
}
