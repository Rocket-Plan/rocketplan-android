package com.example.rocketplan_android.ui.projects.lossinfo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.ClaimMutationRequest
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class ClaimsInfoFragment : Fragment() {

    private val projectId: Long by lazy {
        requireArguments().getLong(ARG_PROJECT_ID)
    }

    private val viewModel: ProjectLossInfoViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
        factoryProducer = {
            ProjectLossInfoViewModel.provideFactory(requireActivity().application, projectId)
        }
    )

    private lateinit var claimsList: androidx.recyclerview.widget.RecyclerView
    private lateinit var emptyState: View
    private lateinit var loadingIndicator: View
    private val adapter = ClaimsListAdapter(::onEditClaim)
    private var editDialogState: ClaimEditDialogState? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_claims_info_tab, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        claimsList = view.findViewById(R.id.claimsRecyclerView)
        emptyState = view.findViewById(R.id.claimsEmptyState)
        loadingIndicator = view.findViewById(R.id.claimsLoading)

        claimsList.layoutManager = LinearLayoutManager(requireContext())
        claimsList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is ProjectLossInfoEvent.ClaimUpdated -> handleClaimUpdated(event)
                        is ProjectLossInfoEvent.ClaimUpdateFailed -> handleClaimUpdateFailed(event)
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun render(state: ProjectLossInfoUiState) {
        loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        if (state.claims.isEmpty()) {
            claimsList.visibility = View.GONE
            emptyState.visibility = if (state.isLoading) View.GONE else View.VISIBLE
        } else {
            claimsList.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            adapter.submitList(state.claims)
        }
    }

    private fun onEditClaim(item: ClaimListItem) {
        val dialog = BottomSheetDialog(requireContext())
        val content = layoutInflater.inflate(R.layout.dialog_claim_edit, null)
        dialog.setContentView(content)

        val subtitle = content.findViewById<TextView>(R.id.claimEditSubtitle)
        subtitle.text = item.locationName ?: getString(R.string.loss_info_claim_project_tag)

        val policyHolderInput = content.findViewById<TextInputEditText>(R.id.claimPolicyHolderInput)
        val ownershipStatusInput = content.findViewById<TextInputEditText>(R.id.claimOwnershipStatusInput)
        val phoneInput = content.findViewById<TextInputEditText>(R.id.claimPhoneInput)
        val emailInput = content.findViewById<TextInputEditText>(R.id.claimEmailInput)
        val representativeInput = content.findViewById<TextInputEditText>(R.id.claimRepresentativeInput)
        val providerInput = content.findViewById<TextInputEditText>(R.id.claimProviderInput)
        val deductibleInput = content.findViewById<TextInputEditText>(R.id.claimDeductibleInput)
        val policyNumberInput = content.findViewById<TextInputEditText>(R.id.claimPolicyNumberInput)
        val claimNumberInput = content.findViewById<TextInputEditText>(R.id.claimClaimNumberInput)
        val adjusterInput = content.findViewById<TextInputEditText>(R.id.claimAdjusterInput)
        val adjusterPhoneInput = content.findViewById<TextInputEditText>(R.id.claimAdjusterPhoneInput)
        val adjusterEmailInput = content.findViewById<TextInputEditText>(R.id.claimAdjusterEmailInput)
        val saveButton = content.findViewById<MaterialButton>(R.id.saveClaimButton)
        val cancelButton = content.findViewById<MaterialButton>(R.id.cancelClaimButton)
        val progress = content.findViewById<CircularProgressIndicator>(R.id.claimEditProgress)

        policyHolderInput.setText(item.claim.policyHolder.orEmpty())
        ownershipStatusInput.setText(item.claim.ownershipStatus.orEmpty())
        phoneInput.setText(item.claim.policyHolderPhone.orEmpty())
        emailInput.setText(item.claim.policyHolderEmail.orEmpty())
        representativeInput.setText(item.claim.representative.orEmpty())
        providerInput.setText(item.claim.provider.orEmpty())
        deductibleInput.setText(item.claim.insuranceDeductible.orEmpty())
        policyNumberInput.setText(item.claim.policyNumber.orEmpty())
        claimNumberInput.setText(item.claim.claimNumber.orEmpty())
        adjusterInput.setText(item.claim.adjuster.orEmpty())
        adjusterPhoneInput.setText(item.claim.adjusterPhone.orEmpty())
        adjusterEmailInput.setText(item.claim.adjusterEmail.orEmpty())

        val inputs = listOf(
            policyHolderInput,
            ownershipStatusInput,
            phoneInput,
            emailInput,
            representativeInput,
            providerInput,
            deductibleInput,
            policyNumberInput,
            claimNumberInput,
            adjusterInput,
            adjusterPhoneInput,
            adjusterEmailInput
        )

        editDialogState = ClaimEditDialogState(
            claimId = item.claim.id,
            dialog = dialog,
            saveButton = saveButton,
            progress = progress,
            inputs = inputs
        )

        saveButton.setOnClickListener {
            setEditDialogSaving(true)
            val request = ClaimMutationRequest(
                policyHolder = policyHolderInput.valueOrNull(),
                ownershipStatus = ownershipStatusInput.valueOrNull(),
                policyHolderPhone = phoneInput.valueOrNull(),
                policyHolderEmail = emailInput.valueOrNull(),
                representative = representativeInput.valueOrNull(),
                provider = providerInput.valueOrNull(),
                insuranceDeductible = deductibleInput.valueOrNull(),
                policyNumber = policyNumberInput.valueOrNull(),
                claimNumber = claimNumberInput.valueOrNull(),
                adjuster = adjusterInput.valueOrNull(),
                adjusterPhone = adjusterPhoneInput.valueOrNull(),
                adjusterEmail = adjusterEmailInput.valueOrNull(),
                claimTypeId = item.claim.claimType?.id,
                projectId = item.claim.projectId,
                locationId = item.claim.locationId
            )
            viewModel.updateClaim(item.claim.id, request)
        }

        cancelButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { editDialogState = null }

        dialog.show()
    }

    private fun handleClaimUpdated(event: ProjectLossInfoEvent.ClaimUpdated) {
        val state = editDialogState
        if (state?.claimId == event.claim.id) {
            Toast.makeText(
                requireContext(),
                getString(R.string.loss_info_claim_save_success),
                Toast.LENGTH_SHORT
            ).show()
            state.dialog.dismiss()
            editDialogState = null
        }
    }

    private fun handleClaimUpdateFailed(event: ProjectLossInfoEvent.ClaimUpdateFailed) {
        setEditDialogSaving(false)
        Toast.makeText(
            requireContext(),
            event.message.ifBlank { getString(R.string.loss_info_claim_save_failed) },
            Toast.LENGTH_LONG
        ).show()
    }

    private fun setEditDialogSaving(isSaving: Boolean) {
        val state = editDialogState ?: return
        state.inputs.forEach { it.isEnabled = !isSaving }
        state.saveButton.isEnabled = !isSaving
        state.progress.visibility = if (isSaving) View.VISIBLE else View.GONE
    }

    companion object {
        private const val ARG_PROJECT_ID = "arg_project_id"

        fun newInstance(projectId: Long): ClaimsInfoFragment {
            return ClaimsInfoFragment().apply {
                arguments = Bundle().apply { putLong(ARG_PROJECT_ID, projectId) }
            }
        }
    }
}

private data class ClaimEditDialogState(
    val claimId: Long,
    val dialog: BottomSheetDialog,
    val saveButton: MaterialButton,
    val progress: CircularProgressIndicator,
    val inputs: List<TextInputEditText>
)

private fun TextInputEditText.valueOrNull(): String? =
    text?.toString()?.trim().orEmpty().ifBlank { null }
