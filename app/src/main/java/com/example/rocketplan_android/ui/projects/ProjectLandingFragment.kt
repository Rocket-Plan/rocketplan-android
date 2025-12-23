package com.example.rocketplan_android.ui.projects

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.example.rocketplan_android.config.AppConfig
import com.example.rocketplan_android.data.model.ProjectStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Lightweight landing screen that presents the primary project actions before
 * drilling into deeper sections of a project.
 */
class ProjectLandingFragment : Fragment() {

    private val args: ProjectLandingFragmentArgs by navArgs()

    private val viewModel: ProjectLandingViewModel by viewModels {
        ProjectLandingViewModel.provideFactory(requireActivity().application, args.projectId)
    }

    private lateinit var menuButton: ImageButton
    private lateinit var projectTitle: TextView
    private lateinit var aliasAction: TextView
    private lateinit var projectCode: TextView
    private lateinit var statusBadge: TextView
    private lateinit var statusContainer: View
    private lateinit var projectInfoCard: View
    private lateinit var projectActionsContainer: View
    private lateinit var emptyState: TextView

    private lateinit var addProjectInfoCard: View
    private lateinit var addProjectInfoIcon: ImageView
    private lateinit var rocketDryCard: View
    private lateinit var rocketScanCard: View
    private lateinit var rocketScanIcon: ImageView
    private lateinit var allNotesCard: View
    private lateinit var allNotesIcon: ImageView
    private lateinit var allNotesSubtitle: TextView

    private var latestSummary: ProjectLandingSummary? = null
    private var statusDialog: AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_project_landing, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        bindListeners()
        observeViewModel()
    }

    private fun bindViews(root: View) {
        menuButton = root.findViewById(R.id.menuButton)
        projectTitle = root.findViewById(R.id.projectTitle)
        aliasAction = root.findViewById(R.id.projectAliasAction)
        projectCode = root.findViewById(R.id.projectCode)
        statusBadge = root.findViewById(R.id.projectStatusBadge)
        statusContainer = root.findViewById(R.id.projectStatusContainer)
        projectInfoCard = root.findViewById(R.id.projectInfoCard)
        projectActionsContainer = root.findViewById(R.id.projectActionsContainer)
        emptyState = root.findViewById(R.id.projectEmptyState)

        addProjectInfoCard = root.findViewById(R.id.addProjectInfoCard)
        addProjectInfoIcon = root.findViewById(R.id.addProjectInfoIcon)
        rocketDryCard = root.findViewById(R.id.rocketDryCard)
        rocketScanCard = root.findViewById(R.id.rocketScanCard)
        rocketScanIcon = root.findViewById(R.id.rocketScanIcon)
        allNotesCard = root.findViewById(R.id.allNotesCard)
        allNotesIcon = root.findViewById(R.id.allNotesIcon)
        allNotesSubtitle = root.findViewById(R.id.allNotesSubtitle)
    }

    private fun bindListeners() {
        menuButton.setOnClickListener {
            showProjectMenu(it)
        }
        aliasAction.setOnClickListener {
            val summary = latestSummary ?: return@setOnClickListener
            if (!summary.aliasIsActionable || summary.aliasIsUpdating) {
                return@setOnClickListener
            }
            showAliasInputDialog()
        }
        addProjectInfoCard.setOnClickListener {
            val action = ProjectLandingFragmentDirections
                .actionProjectLandingFragmentToProjectLossInfoFragment(args.projectId)
            findNavController().navigate(action)
        }
        rocketDryCard.setOnClickListener {
            if (!AppConfig.isRocketDryEnabled) {
                return@setOnClickListener
            }
            val action = ProjectLandingFragmentDirections
                .actionProjectLandingFragmentToRocketDryFragment(args.projectId)
            findNavController().navigate(action)
        }
        rocketScanCard.setOnClickListener {
            val summary = latestSummary ?: return@setOnClickListener
            Log.d(
                "ProjectLanding",
                "RocketScan click: hasProperty=${summary.hasProperty}, hasLevels=${summary.hasLevels}, " +
                    "isSyncing=${summary.isSyncing}, hasRooms=${summary.hasRooms}"
            )

            when {
                // No property at all - need to create one via type selection
                !summary.hasProperty -> {
                    val action = ProjectLandingFragmentDirections
                        .actionProjectLandingFragmentToProjectTypeSelectionFragment(args.projectId)
                    findNavController().navigate(action)
                }
                // Property exists but no levels yet AND sync is not in progress
                // If sync is in progress, go to detail and let it show loading state
                !summary.hasLevels && !summary.isSyncing -> {
                    val action = ProjectLandingFragmentDirections
                        .actionProjectLandingFragmentToProjectTypeSelectionFragment(args.projectId)
                    findNavController().navigate(action)
                }
                else -> {
                    // Has property and either has levels or sync is in progress
                    val action = ProjectLandingFragmentDirections
                        .actionProjectLandingFragmentToProjectDetailFragment(args.projectId)
                    findNavController().navigate(action)
                }
            }
        }
        allNotesCard.setOnClickListener {
            val action = ProjectLandingFragmentDirections
                .actionProjectLandingFragmentToProjectNotesFragment(args.projectId)
            findNavController().navigate(action)
        }
        statusContainer.setOnClickListener {
            showStatusSelectionDialog()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        ProjectLandingUiState.Loading -> showLoadingState()
                        is ProjectLandingUiState.Ready -> renderState(state.summary)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        ProjectLandingEvent.ProjectDeleted -> {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.project_deleted),
                                Toast.LENGTH_SHORT
                            ).show()
                            findNavController().navigateUp()
                        }
                        is ProjectLandingEvent.DeleteFailed -> {
                            val errorMessage = "${getString(R.string.project_delete_failed)}: ${event.error}"
                            Toast.makeText(
                                requireContext(),
                                errorMessage,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is ProjectLandingEvent.AliasUpdated -> {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.project_alias_saved),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is ProjectLandingEvent.AliasUpdateFailed -> {
                            Toast.makeText(
                                requireContext(),
                                event.error,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun showLoadingState() {
        latestSummary = null
        projectInfoCard.isVisible = false
        projectActionsContainer.isVisible = false
        emptyState.isVisible = true
        emptyState.text = getString(R.string.loading_project)
    }

    private fun renderState(summary: ProjectLandingSummary) {
        latestSummary = summary
        projectInfoCard.isVisible = true
        projectActionsContainer.isVisible = true
        emptyState.isVisible = false

        projectTitle.text = summary.projectTitle
        projectCode.isVisible = summary.projectCode.isNotBlank()
        projectCode.text = summary.projectCode

        val aliasText = when {
            summary.aliasIsUpdating -> getString(R.string.saving_alias)
            summary.aliasText != null -> summary.aliasText
            else -> getString(R.string.add_alias)
        }
        aliasAction.text = aliasText
        val aliasClickable = summary.aliasIsActionable && !summary.aliasIsUpdating
        aliasAction.isEnabled = aliasClickable
        aliasAction.isClickable = aliasClickable
        val aliasColor = when {
            summary.aliasIsUpdating -> requireContext().getColor(R.color.light_text_rp)
            summary.aliasIsActionable -> requireContext().getColor(R.color.main_purple)
            else -> requireContext().getColor(R.color.light_text_rp)
        }
        aliasAction.setTextColor(aliasColor)

        statusContainer.isVisible = true
        statusContainer.isEnabled = true
        statusBadge.text = summary.statusLabel ?: getString(R.string.project_status_set_placeholder)

        allNotesSubtitle.text = getString(R.string.project_notes_subtitle)

        rocketDryCard.isVisible = AppConfig.isRocketDryEnabled
    }

    private fun showAliasInputDialog() {
        val context = requireContext()
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.activity_horizontal_margin)
        val container = FrameLayout(context).apply {
            setPadding(
                horizontalPadding,
                resources.getDimensionPixelSize(R.dimen.activity_vertical_margin),
                horizontalPadding,
                0
            )
        }

        val inputLayout = TextInputLayout(
            context,
            /* attrs = */ null,
            com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox
        ).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.project_alias_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = context.getColor(R.color.white)
            setBoxCornerRadii(
                resources.getDimension(R.dimen.activity_horizontal_margin),
                resources.getDimension(R.dimen.activity_horizontal_margin),
                resources.getDimension(R.dimen.activity_horizontal_margin),
                resources.getDimension(R.dimen.activity_horizontal_margin)
            )
            setBoxStrokeColorStateList(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_focused),
                        intArrayOf(-android.R.attr.state_focused)
                    ),
                    intArrayOf(
                        context.getColor(R.color.main_purple),
                        context.getColor(R.color.light_border)
                    )
                )
            )
        }

        val aliasInput = TextInputEditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
            setTextColor(context.getColor(R.color.dark_text_rp))
            setHintTextColor(context.getColor(R.color.placeholder))
        }
        inputLayout.addView(aliasInput)
        container.addView(inputLayout)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.add_alias)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            aliasInput.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val alias = aliasInput.text?.toString()?.trim().orEmpty()
                if (alias.isBlank()) {
                    inputLayout.error = getString(R.string.project_alias_required)
                    return@setOnClickListener
                }
                inputLayout.error = null
                viewModel.addAlias(alias)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showProjectMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.project_landing_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            handleMenuItemClick(item)
        }
        popup.show()
    }

    private fun handleMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete_project -> {
                showDeleteProjectDialog()
                true
            }
            else -> false
        }
    }

    private fun showDeleteProjectDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_project_title)
            .setMessage(R.string.delete_project_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteProject()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showStatusSelectionDialog() {
        val summary = latestSummary ?: return
        val statuses = ProjectStatus.orderedStatuses
        val labels = statuses.map { getString(it.labelRes) }.toTypedArray()
        val checkedIndex = summary.status?.let { statuses.indexOf(it) } ?: -1

        statusDialog?.dismiss()
        statusDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.project_status_dialog_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selectedStatus = statuses[which]
                viewModel.updateProjectStatus(selectedStatus)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        statusDialog?.dismiss()
        statusDialog = null
        super.onDestroyView()
    }
}
