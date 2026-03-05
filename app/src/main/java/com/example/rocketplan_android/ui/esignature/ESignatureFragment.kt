package com.example.rocketplan_android.ui.esignature

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.PdfFormSubmissionDto
import com.example.rocketplan_android.data.model.PdfFormTemplateDto
import com.example.rocketplan_android.util.safeNavigate
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class ESignatureFragment : Fragment() {

    private val args: ESignatureFragmentArgs by navArgs()

    private val viewModel: ESignatureViewModel by viewModels {
        ESignatureViewModel.provideFactory(requireActivity().application, args.projectId)
    }

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var loadingIndicator: View
    private lateinit var fab: FloatingActionButton

    private val adapter = ESignatureAdapter(
        onSubmissionClick = { submission -> viewModel.onSubmissionClicked(submission) },
        onSubmissionLongClick = { submission -> showDeleteConfirmation(submission) },
        onDeleteClick = { submission -> showDeleteConfirmation(submission) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_esignature, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    private fun bindViews(root: View) {
        swipeRefresh = root.findViewById(R.id.swipeRefresh)
        recyclerView = root.findViewById(R.id.submissionsRecyclerView)
        emptyState = root.findViewById(R.id.emptyState)
        loadingIndicator = root.findViewById(R.id.loadingIndicator)
        fab = root.findViewById(R.id.fab)
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        fab.setOnClickListener { showNewFormDialog() }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is ESignatureUiState.Loading -> {
                            loadingIndicator.isVisible = true
                            recyclerView.isVisible = false
                            emptyState.isVisible = false
                        }
                        is ESignatureUiState.Ready -> {
                            loadingIndicator.isVisible = false
                            adapter.submitList(state.submissions)
                            if (state.submissions.isEmpty()) {
                                recyclerView.isVisible = false
                                emptyState.isVisible = true
                                emptyState.text = getString(R.string.esignature_empty)
                            } else {
                                recyclerView.isVisible = true
                                emptyState.isVisible = false
                            }
                        }
                        is ESignatureUiState.Error -> {
                            loadingIndicator.isVisible = false
                            recyclerView.isVisible = false
                            emptyState.isVisible = true
                            emptyState.text = state.message
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isRefreshing.collect { isRefreshing ->
                    swipeRefresh.isRefreshing = isRefreshing
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is ESignatureEvent.SubmissionCreated -> {
                            navigateToSign(event.uuid)
                        }
                        is ESignatureEvent.NavigateToSign -> {
                            navigateToSign(event.uuid)
                        }
                        is ESignatureEvent.ShowError -> {
                            Toast.makeText(requireContext(), event.message, Toast.LENGTH_LONG).show()
                        }
                        is ESignatureEvent.OpenSignedUrl -> {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(event.url))
                                startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(requireContext(), R.string.esignature_load_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                        is ESignatureEvent.SubmissionShared -> {
                            Toast.makeText(requireContext(), R.string.esignature_shared_success, Toast.LENGTH_SHORT).show()
                        }
                        is ESignatureEvent.SubmissionShareFailed -> {
                            Toast.makeText(requireContext(), event.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun navigateToSign(uuid: String) {
        val action = ESignatureFragmentDirections
            .actionESignatureFragmentToPdfFormSignFragment(uuid, args.projectId)
        safeNavigate(action)
    }

    private fun showDeleteConfirmation(submission: PdfFormSubmissionDto) {
        val name = submission.template?.name ?: getString(R.string.esignature_default_template)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.esignature_delete_title)
            .setMessage(getString(R.string.esignature_delete_message, name))
            .setPositiveButton(R.string.esignature_delete) { _, _ ->
                viewModel.deleteSubmission(submission)
            }
            .setNegativeButton(R.string.esignature_cancel, null)
            .show()
    }

    private fun showNewFormDialog() {
        val state = viewModel.uiState.value
        val templates = when (state) {
            is ESignatureUiState.Ready -> state.templates
            else -> emptyList()
        }

        if (templates.isEmpty()) {
            Toast.makeText(requireContext(), R.string.esignature_no_templates, Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(requireContext())
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_pdf_form_new, null)

        val templateDropdown = dialogView.findViewById<AutoCompleteTextView>(R.id.templateDropdown)
        val clientNameInput = dialogView.findViewById<TextInputEditText>(R.id.clientNameInput)
        val clientEmailInput = dialogView.findViewById<TextInputEditText>(R.id.clientEmailInput)
        val clientPhoneInput = dialogView.findViewById<TextInputEditText>(R.id.clientPhoneInput)
        val clientEmailLayout = dialogView.findViewById<TextInputLayout>(R.id.clientEmailLayout)
        val clientPhoneLayout = dialogView.findViewById<TextInputLayout>(R.id.clientPhoneLayout)
        val btnEmailAndSms = dialogView.findViewById<MaterialButton>(R.id.btnEmailAndSms)
        val btnEmail = dialogView.findViewById<MaterialButton>(R.id.btnEmail)
        val btnSms = dialogView.findViewById<MaterialButton>(R.id.btnSms)
        val btnOpenOnDevice = dialogView.findViewById<MaterialButton>(R.id.btnOpenOnDevice)

        // Setup template dropdown
        val templateNames = templates.map { it.name ?: "Untitled" }
        val dropdownAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, templateNames)
        templateDropdown.setAdapter(dropdownAdapter)
        var selectedTemplate: PdfFormTemplateDto? = null
        templateDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedTemplate = templates[position]
        }
        // Auto-select first template
        if (templates.isNotEmpty()) {
            selectedTemplate = templates[0]
            templateDropdown.setText(templates[0].name ?: "Untitled", false)
        }

        // Prefill client info from project claims (await async load)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.awaitClaimsLoaded()
            clientNameInput.setText(viewModel.getClientNamePrefill())
            clientEmailInput.setText(viewModel.getClientEmailPrefill())
            clientPhoneInput.setText(viewModel.getClientPhonePrefill())
        }

        fun submitWithDelivery(deliveryMethod: DeliveryMethod) {
            val template = selectedTemplate
            if (template?.id == null) {
                Toast.makeText(requireContext(), R.string.esignature_select_template, Toast.LENGTH_SHORT).show()
                return
            }

            val email = clientEmailInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            val phone = clientPhoneInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

            // Clear previous errors
            clientEmailLayout.error = null
            clientPhoneLayout.error = null

            // Validate required fields based on delivery method
            val needsEmail = deliveryMethod == DeliveryMethod.EMAIL || deliveryMethod == DeliveryMethod.EMAIL_AND_SMS
            val needsPhone = deliveryMethod == DeliveryMethod.SMS || deliveryMethod == DeliveryMethod.EMAIL_AND_SMS

            if (needsEmail && email.isNullOrBlank()) {
                clientEmailLayout.error = getString(R.string.esignature_email_required)
                return
            }
            if (needsPhone && phone.isNullOrBlank()) {
                clientPhoneLayout.error = getString(R.string.esignature_phone_required)
                return
            }

            viewModel.createSubmission(
                templateId = template.id,
                clientName = clientNameInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                clientEmail = email,
                clientPhone = phone,
                deliveryMethod = deliveryMethod
            )
            dialog.dismiss()
        }

        btnEmailAndSms.setOnClickListener { submitWithDelivery(DeliveryMethod.EMAIL_AND_SMS) }
        btnEmail.setOnClickListener { submitWithDelivery(DeliveryMethod.EMAIL) }
        btnSms.setOnClickListener { submitWithDelivery(DeliveryMethod.SMS) }
        btnOpenOnDevice.setOnClickListener { submitWithDelivery(DeliveryMethod.OPEN_ON_DEVICE) }

        dialog.setContentView(dialogView)
        dialog.show()
    }
}
