package com.example.rocketplan_android.ui.projects.lossinfo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.rocketplan_android.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch

class PropertyInfoFragment : Fragment() {

    private val projectId: Long by lazy {
        requireArguments().getLong(ARG_PROJECT_ID)
    }

    private val viewModel: ProjectLossInfoViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
        factoryProducer = {
            ProjectLossInfoViewModel.provideFactory(requireActivity().application, projectId)
        }
    )

    private lateinit var loadingIndicator: CircularProgressIndicator
    private lateinit var headerCard: MaterialCardView
    private lateinit var projectTitleView: MaterialTextView
    private lateinit var projectCodeView: MaterialTextView
    private lateinit var projectCreatedView: MaterialTextView
    private lateinit var classificationView: MaterialTextView
    private lateinit var asbestosView: MaterialTextView
    private lateinit var yearBuiltView: MaterialTextView
    private lateinit var buildingNameView: MaterialTextView
    private lateinit var referralNameView: MaterialTextView
    private lateinit var referralPhoneView: MaterialTextView
    private lateinit var platinumView: MaterialTextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_property_info_tab, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadingIndicator = view.findViewById(R.id.propertyInfoLoading)
        headerCard = view.findViewById(R.id.propertyHeaderCard)
        projectTitleView = view.findViewById(R.id.projectTitleValue)
        projectCodeView = view.findViewById(R.id.projectCodeValue)
        projectCreatedView = view.findViewById(R.id.projectCreatedValue)
        classificationView = view.findViewById(R.id.classificationValue)
        asbestosView = view.findViewById(R.id.asbestosValue)
        yearBuiltView = view.findViewById(R.id.yearBuiltValue)
        buildingNameView = view.findViewById(R.id.buildingNameValue)
        referralNameView = view.findViewById(R.id.referralNameValue)
        referralPhoneView = view.findViewById(R.id.referralPhoneValue)
        platinumView = view.findViewById(R.id.platinumAgentValue)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: ProjectLossInfoUiState) {
        loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        headerCard.visibility = if (state.isLoading) View.INVISIBLE else View.VISIBLE

        projectTitleView.text = state.projectTitle ?: getString(R.string.loss_info_property_placeholder_title)
        projectCodeView.text = state.projectCode ?: getString(R.string.loss_info_property_placeholder_code)
        projectCreatedView.text = state.projectCreatedAt ?: getString(R.string.loss_info_value_not_available)

        val property = state.property
        classificationView.text = buildClassification(property)
        asbestosView.text = property?.asbestosStatus?.title
            ?: getString(R.string.loss_info_value_not_available)
        yearBuiltView.text = property?.yearBuilt?.toString()
            ?: getString(R.string.loss_info_value_not_available)
        buildingNameView.text = property?.name
            ?: getString(R.string.loss_info_value_not_available)
        referralNameView.text = property?.referredByName
            ?: getString(R.string.loss_info_value_not_available)
        referralPhoneView.text = property?.referredByPhone
            ?: getString(R.string.loss_info_value_not_available)
        platinumView.text = when (property?.isPlatinumAgent) {
            true -> getString(R.string.loss_info_yes)
            false -> getString(R.string.loss_info_no)
            else -> getString(R.string.loss_info_value_not_available)
        }
    }

    private fun buildClassification(property: com.example.rocketplan_android.data.model.offline.PropertyDto?): String {
        if (property == null) return getString(R.string.loss_info_value_not_available)
        val segments = mutableListOf<String>()
        if (property.isResidential == true) segments.add(getString(R.string.loss_info_residential))
        if (property.isCommercial == true) segments.add(getString(R.string.loss_info_commercial))
        if (segments.isEmpty()) return getString(R.string.loss_info_value_not_available)
        return segments.joinToString(" / ")
    }

    companion object {
        private const val ARG_PROJECT_ID = "arg_project_id"

        fun newInstance(projectId: Long): PropertyInfoFragment {
            return PropertyInfoFragment().apply {
                arguments = Bundle().apply { putLong(ARG_PROJECT_ID, projectId) }
            }
        }
    }
}
