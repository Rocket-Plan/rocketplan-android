package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

/**
 * Fragment that allows the user to select the property type for a new project.
 * This is shown when a project has no levels/locations yet.
 */
class ProjectTypeSelectionFragment : Fragment() {

    private val args: ProjectTypeSelectionFragmentArgs by navArgs()
    private val viewModel: ProjectTypeSelectionViewModel by viewModels {
        ProjectTypeSelectionViewModel.provideFactory(requireActivity().application, args.projectId)
    }

    private lateinit var projectName: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var singleUnitCard: MaterialCardView
    private lateinit var multiUnitCard: MaterialCardView
    private lateinit var exteriorCard: MaterialCardView
    private lateinit var commercialCard: MaterialCardView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_project_type_selection, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupListeners()
        observeViewModel()
    }

    private fun bindViews(root: View) {
        projectName = root.findViewById(R.id.projectName)
        loadingIndicator = root.findViewById(R.id.loadingIndicator)
        errorText = root.findViewById(R.id.errorText)
        singleUnitCard = root.findViewById(R.id.singleUnitCard)
        multiUnitCard = root.findViewById(R.id.multiUnitCard)
        exteriorCard = root.findViewById(R.id.exteriorCard)
        commercialCard = root.findViewById(R.id.commercialCard)
    }

    private fun setupListeners() {
        singleUnitCard.setOnClickListener {
            handlePropertyTypeSelection(PropertyType.SINGLE_UNIT)
        }

        multiUnitCard.setOnClickListener {
            handlePropertyTypeSelection(PropertyType.MULTI_UNIT)
        }

        exteriorCard.setOnClickListener {
            handlePropertyTypeSelection(PropertyType.EXTERIOR)
        }

        commercialCard.setOnClickListener {
            handlePropertyTypeSelection(PropertyType.COMMERCIAL)
        }
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
                    viewModel.navigationEvents.collect { event ->
                        when (event) {
                            is ProjectTypeSelectionNavigation.NavigateToProjectDetail -> {
                                val action = ProjectTypeSelectionFragmentDirections
                                    .actionProjectTypeSelectionFragmentToProjectDetailFragment(event.projectId)
                                findNavController().navigate(action)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: ProjectTypeSelectionViewState) {
        val isBusy = state.isLoading || state.isSelectionInProgress
        loadingIndicator.visibility = if (isBusy) View.VISIBLE else View.GONE
        val displayName = if (state.projectName.isNotBlank()) {
            state.projectName
        } else {
            getString(R.string.project_type_loading_project_name)
        }
        projectName.text = displayName

        val hasError = !state.errorMessage.isNullOrBlank()
        errorText.visibility = if (hasError) View.VISIBLE else View.GONE
        if (hasError) {
            errorText.text = state.errorMessage
        }

        val cardsEnabled = !isBusy && !hasError
        setCardsEnabled(cardsEnabled)
    }

    private fun handlePropertyTypeSelection(propertyType: PropertyType) {
        viewModel.selectPropertyType(propertyType)
    }

    private fun setCardsEnabled(isEnabled: Boolean) {
        singleUnitCard.isEnabled = isEnabled
        multiUnitCard.isEnabled = isEnabled
        exteriorCard.isEnabled = isEnabled
        commercialCard.isEnabled = isEnabled
        singleUnitCard.isClickable = isEnabled
        multiUnitCard.isClickable = isEnabled
        exteriorCard.isClickable = isEnabled
        commercialCard.isClickable = isEnabled
    }
}
