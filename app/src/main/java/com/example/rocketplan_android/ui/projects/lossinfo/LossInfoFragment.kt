package com.example.rocketplan_android.ui.projects.lossinfo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.rocketplan_android.R
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch

class LossInfoFragment : Fragment() {

    private val viewModel: ProjectLossInfoViewModel by lazy {
        ViewModelProvider(requireParentFragment())[ProjectLossInfoViewModel::class.java]
    }

    private lateinit var damageTypesGroup: ChipGroup
    private lateinit var damageCauseValue: MaterialTextView
    private lateinit var damageCategoryValue: MaterialTextView
    private lateinit var lossClassValue: MaterialTextView
    private lateinit var lossDateValue: MaterialTextView
    private lateinit var callReceivedValue: MaterialTextView
    private lateinit var crewDispatchedValue: MaterialTextView
    private lateinit var arrivedOnSiteValue: MaterialTextView
    private lateinit var affectedLocationsValue: MaterialTextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_loss_info_tab, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        damageTypesGroup = view.findViewById(R.id.damageTypesGroup)
        damageCauseValue = view.findViewById(R.id.damageCauseValue)
        damageCategoryValue = view.findViewById(R.id.damageCategoryValue)
        lossClassValue = view.findViewById(R.id.lossClassValue)
        lossDateValue = view.findViewById(R.id.lossDateValue)
        callReceivedValue = view.findViewById(R.id.callReceivedValue)
        crewDispatchedValue = view.findViewById(R.id.crewDispatchedValue)
        arrivedOnSiteValue = view.findViewById(R.id.arrivedOnSiteValue)
        affectedLocationsValue = view.findViewById(R.id.affectedLocationsValue)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
    }

    private fun render(state: ProjectLossInfoUiState) {
        renderDamageTypes(state)
        damageCauseValue.text = state.selectedDamageCause?.name
            ?: getString(R.string.loss_info_value_not_available)
        damageCategoryValue.text = state.damageCategory?.toString()
            ?: getString(R.string.loss_info_value_not_available)
        lossClassValue.text = state.lossClass?.toString()
            ?: getString(R.string.loss_info_value_not_available)
        lossDateValue.text = viewModel.formatDate(state.lossDate)
        callReceivedValue.text = viewModel.formatDateTime(state.callReceived)
        crewDispatchedValue.text = viewModel.formatDateTime(state.crewDispatched)
        arrivedOnSiteValue.text = viewModel.formatDateTime(state.arrivedOnSite)

        affectedLocationsValue.text = if (state.affectedLocations.isNotEmpty()) {
            state.affectedLocations.joinToString(separator = "\n")
        } else {
            getString(R.string.loss_info_locations_placeholder)
        }
    }

    private fun renderDamageTypes(state: ProjectLossInfoUiState) {
        damageTypesGroup.removeAllViews()
        if (state.damageTypes.isEmpty()) {
            val chip = Chip(requireContext()).apply {
                text = getString(R.string.loss_info_value_not_available)
                isCheckable = false
                isClickable = false
            }
            damageTypesGroup.addView(chip)
            return
        }

        state.damageTypes.forEach { damageType ->
            val chip = Chip(requireContext()).apply {
                text = damageType.title ?: damageType.name ?: damageType.id.toString()
                isCheckable = true
                isClickable = false
                isChecked = state.selectedDamageTypeIds.contains(damageType.id)
            }
            damageTypesGroup.addView(chip)
        }
    }

    companion object {
        private const val ARG_PROJECT_ID = "arg_project_id"

        fun newInstance(projectId: Long): LossInfoFragment {
            return LossInfoFragment().apply {
                arguments = Bundle().apply { putLong(ARG_PROJECT_ID, projectId) }
            }
        }
    }
}
