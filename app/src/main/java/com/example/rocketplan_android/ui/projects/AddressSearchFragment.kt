package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.BuildConfig
import com.example.rocketplan_android.R
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fragment for searching addresses using Google Places autocomplete.
 * Provides quick address lookup and falls back to manual entry.
 */
class AddressSearchFragment : Fragment() {

    private lateinit var searchLayout: TextInputLayout
    private lateinit var searchInput: TextInputEditText
    private lateinit var enterManuallyButton: TextView
    private lateinit var recentAddressesLabel: TextView
    private lateinit var suggestionsRecyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar

    private var placesClient: PlacesClient? = null
    private val viewModel: CreateProjectViewModel by activityViewModels()
    private var searchJob: Job? = null

    private val suggestionsAdapter = AddressSuggestionAdapter(
        onSuggestionClick = { suggestion ->
            when (suggestion) {
                is AddressSuggestion.PlaceSuggestion -> fetchPlaceDetails(suggestion.prediction)
                is AddressSuggestion.RecentAddress -> {
                    viewModel.createProjectFromQuickAddress(suggestion.address)
                }
            }
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_address_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        searchLayout = view.findViewById(R.id.addressSearchLayout)
        searchInput = view.findViewById(R.id.addressSearchInput)
        enterManuallyButton = view.findViewById(R.id.enterManuallyButton)
        recentAddressesLabel = view.findViewById(R.id.recentAddressesLabel)
        suggestionsRecyclerView = view.findViewById(R.id.suggestionsRecyclerView)
        progressBar = view.findViewById(R.id.progressBar)

        initializePlaces()
        setupViews()
        observeViewModel()
    }

    private fun initializePlaces() {
        val apiKey = BuildConfig.MAPS_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "Maps API key not configured. Address autocomplete will be unavailable.")
            return
        }

        try {
            if (!Places.isInitialized()) {
                Places.initialize(requireContext(), apiKey)
            }
            placesClient = Places.createClient(requireContext())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Places SDK", e)
        }
    }

    private fun setupViews() {
        suggestionsRecyclerView.adapter = suggestionsAdapter

        searchInput.doAfterTextChanged { text ->
            val query = text?.toString()?.trim() ?: ""
            searchJob?.cancel()

            if (query.length >= MIN_QUERY_LENGTH) {
                recentAddressesLabel.isVisible = false
                searchJob = lifecycleScope.launch {
                    delay(DEBOUNCE_MS)
                    searchAddresses(query)
                }
            } else {
                showRecentAddresses()
            }
        }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = searchInput.text?.toString()?.trim() ?: ""
                if (query.isNotEmpty()) {
                    // Create project with the typed address if user presses search
                    viewModel.createProjectFromQuickAddress(query)
                }
                true
            } else {
                false
            }
        }

        enterManuallyButton.setOnClickListener {
            findNavController().navigate(R.id.action_addressSearchFragment_to_manualAddressEntryFragment)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        progressBar.isVisible = state.isSubmitting
                        searchInput.isEnabled = !state.isSubmitting
                        enterManuallyButton.isEnabled = !state.isSubmitting

                        if (!state.errorMessage.isNullOrBlank()) {
                            Toast.makeText(requireContext(), state.errorMessage, Toast.LENGTH_LONG).show()
                            viewModel.clearError()
                        }

                        // Show recent addresses when input is empty
                        if (searchInput.text.isNullOrBlank() && state.recentAddresses.isNotEmpty()) {
                            showRecentAddresses(state.recentAddresses)
                        }
                    }
                }

                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is CreateProjectEvent.ProjectCreated -> {
                                navigateToProjectType(event.projectId)
                            }
                        }
                    }
                }

                launch {
                    viewModel.validationEvents.collect { validation ->
                        when (validation) {
                            CreateProjectValidation.AddressRequired -> {
                                searchLayout.error = getString(R.string.create_project_address_required)
                            }
                            else -> { /* Other validation events handled by manual entry */ }
                        }
                    }
                }
            }
        }
    }

    private fun showRecentAddresses(addresses: List<String> = viewModel.uiState.value.recentAddresses) {
        if (addresses.isEmpty()) {
            recentAddressesLabel.isVisible = false
            suggestionsAdapter.submitList(emptyList())
            return
        }

        recentAddressesLabel.isVisible = true
        val suggestions = addresses.map { AddressSuggestion.RecentAddress(it) }
        suggestionsAdapter.submitList(suggestions)
    }

    private fun searchAddresses(query: String) {
        val client = placesClient
        if (client == null) {
            // Fallback: just use recent addresses or typed text
            Log.d(TAG, "Places client not available, using typed address")
            return
        }

        progressBar.isVisible = true

        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setTypesFilter(listOf("address"))
            .build()

        client.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                if (!isAdded) return@addOnSuccessListener
                progressBar.isVisible = false

                val suggestions = response.autocompletePredictions.map {
                    AddressSuggestion.PlaceSuggestion(it)
                }
                suggestionsAdapter.submitList(suggestions)
            }
            .addOnFailureListener { exception ->
                if (!isAdded) return@addOnFailureListener
                progressBar.isVisible = false
                Log.e(TAG, "Places autocomplete failed", exception)
                Toast.makeText(
                    requireContext(),
                    R.string.address_search_error,
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun fetchPlaceDetails(prediction: AutocompletePrediction) {
        val client = placesClient ?: return
        progressBar.isVisible = true

        val placeFields = listOf(
            Place.Field.ID,
            Place.Field.ADDRESS,
            Place.Field.ADDRESS_COMPONENTS,
            Place.Field.LAT_LNG
        )

        val request = FetchPlaceRequest.builder(prediction.placeId, placeFields).build()

        client.fetchPlace(request)
            .addOnSuccessListener { response ->
                if (!isAdded) return@addOnSuccessListener
                progressBar.isVisible = false
                viewModel.createProjectFromPlace(response.place)
            }
            .addOnFailureListener { exception ->
                if (!isAdded) return@addOnFailureListener
                progressBar.isVisible = false
                Log.e(TAG, "Failed to fetch place details", exception)
                // Fallback: use the prediction text
                viewModel.createProjectFromQuickAddress(
                    prediction.getFullText(null).toString()
                )
            }
    }

    private fun navigateToProjectType(projectId: Long) {
        val action = AddressSearchFragmentDirections
            .actionAddressSearchFragmentToProjectTypeSelectionFragment(projectId)
        findNavController().navigate(action)
    }

    companion object {
        private const val TAG = "AddressSearchFragment"
        private const val MIN_QUERY_LENGTH = 3
        private const val DEBOUNCE_MS = 300L
    }
}

/**
 * Sealed class representing different types of address suggestions.
 */
sealed class AddressSuggestion {
    data class PlaceSuggestion(val prediction: AutocompletePrediction) : AddressSuggestion()
    data class RecentAddress(val address: String) : AddressSuggestion()
}

/**
 * Adapter for displaying address suggestions in a RecyclerView.
 */
class AddressSuggestionAdapter(
    private val onSuggestionClick: (AddressSuggestion) -> Unit
) : RecyclerView.Adapter<AddressSuggestionAdapter.ViewHolder>() {

    private var items: List<AddressSuggestion> = emptyList()

    fun submitList(newItems: List<AddressSuggestion>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_address_suggestion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val primaryText: TextView = itemView.findViewById(R.id.primaryText)
        private val secondaryText: TextView = itemView.findViewById(R.id.secondaryText)

        fun bind(suggestion: AddressSuggestion) {
            when (suggestion) {
                is AddressSuggestion.PlaceSuggestion -> {
                    primaryText.text = suggestion.prediction.getPrimaryText(null)
                    val secondary = suggestion.prediction.getSecondaryText(null)
                    if (secondary.isNotBlank()) {
                        secondaryText.text = secondary
                        secondaryText.visibility = View.VISIBLE
                    } else {
                        secondaryText.visibility = View.GONE
                    }
                }
                is AddressSuggestion.RecentAddress -> {
                    primaryText.text = suggestion.address
                    secondaryText.visibility = View.GONE
                }
            }

            itemView.setOnClickListener {
                onSuggestionClick(suggestion)
            }
        }
    }
}
