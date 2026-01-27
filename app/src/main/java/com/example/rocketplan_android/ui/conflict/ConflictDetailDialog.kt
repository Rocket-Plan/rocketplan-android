package com.example.rocketplan_android.ui.conflict

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.repository.ConflictItem
import com.example.rocketplan_android.data.repository.ConflictResolution
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Bottom sheet dialog showing conflict details and resolution options.
 */
class ConflictDetailDialog : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ConflictDetailDialog"
        private const val ARG_CONFLICT_ID = "conflict_id"
        private val HIDDEN_FIELDS = setOf("updatedAt", "lastSyncedAt", "isDirty", "syncStatus")

        fun newInstance(conflictId: String): ConflictDetailDialog {
            return ConflictDetailDialog().apply {
                arguments = bundleOf(ARG_CONFLICT_ID to conflictId)
            }
        }
    }

    private val viewModel: ConflictListViewModel by viewModels({ requireParentFragment() })

    private lateinit var conflictEntityName: TextView
    private lateinit var conflictEntityType: TextView
    private lateinit var localVersionContainer: LinearLayout
    private lateinit var remoteVersionContainer: LinearLayout
    private lateinit var changedFieldsInfo: TextView
    private lateinit var keepLocalButton: MaterialButton
    private lateinit var keepServerButton: MaterialButton
    private lateinit var dismissButton: MaterialButton

    private var conflictId: String? = null
    private var currentConflict: ConflictItem? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_conflict_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        conflictId = arguments?.getString(ARG_CONFLICT_ID)

        conflictEntityName = view.findViewById(R.id.conflictEntityName)
        conflictEntityType = view.findViewById(R.id.conflictEntityType)
        localVersionContainer = view.findViewById(R.id.localVersionContainer)
        remoteVersionContainer = view.findViewById(R.id.remoteVersionContainer)
        changedFieldsInfo = view.findViewById(R.id.changedFieldsInfo)
        keepLocalButton = view.findViewById(R.id.keepLocalButton)
        keepServerButton = view.findViewById(R.id.keepServerButton)
        dismissButton = view.findViewById(R.id.dismissButton)

        setupButtons()
        loadConflictDetails()
    }

    private fun setupButtons() {
        keepLocalButton.setOnClickListener {
            resolveConflict(ConflictResolution.KEEP_LOCAL)
        }

        keepServerButton.setOnClickListener {
            resolveConflict(ConflictResolution.KEEP_SERVER)
        }

        dismissButton.setOnClickListener {
            resolveConflict(ConflictResolution.DISMISS)
        }
    }

    private fun loadConflictDetails() {
        val id = conflictId ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val conflict = viewModel.getConflict(id)
            if (conflict != null) {
                currentConflict = conflict
                displayConflict(conflict)
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.conflict_not_found,
                    Toast.LENGTH_SHORT
                ).show()
                dismiss()
            }
        }
    }

    private fun displayConflict(conflict: ConflictItem) {
        conflictEntityName.text = conflict.entityName
        conflictEntityType.text = getString(
            R.string.conflict_type_format,
            conflict.entityType.replaceFirstChar { it.uppercase() }
        )

        // Display local version fields
        localVersionContainer.removeAllViews()
        conflict.localVersion.forEach { (key, value) ->
            if (key !in HIDDEN_FIELDS) {
                addFieldView(localVersionContainer, key, value, conflict.changedFields.contains(key))
            }
        }

        // Display remote version fields
        remoteVersionContainer.removeAllViews()
        conflict.remoteVersion.forEach { (key, value) ->
            if (key !in HIDDEN_FIELDS) {
                addFieldView(remoteVersionContainer, key, value, conflict.changedFields.contains(key))
            }
        }

        // Show changed fields info
        if (conflict.changedFields.isNotEmpty()) {
            changedFieldsInfo.visibility = View.VISIBLE
            changedFieldsInfo.text = getString(
                R.string.conflict_changed_fields,
                conflict.changedFields.joinToString(", ")
            )
        } else {
            changedFieldsInfo.visibility = View.GONE
        }
    }

    private fun addFieldView(container: LinearLayout, key: String, value: Any?, isChanged: Boolean) {
        val inflater = LayoutInflater.from(requireContext())
        val fieldView = inflater.inflate(R.layout.item_conflict_field, container, false)

        val fieldName = fieldView.findViewById<TextView>(R.id.fieldName)
        val fieldValue = fieldView.findViewById<TextView>(R.id.fieldValue)

        fieldName.text = formatFieldName(key)
        fieldValue.text = formatFieldValue(value)

        if (isChanged) {
            fieldName.setTextColor(requireContext().getColor(R.color.warning_red))
        }

        container.addView(fieldView)
    }

    private fun formatFieldName(key: String): String {
        return key
            .replace(Regex("([A-Z])"), " $1")
            .trim()
            .replaceFirstChar { it.uppercase() }
    }

    private fun formatFieldValue(value: Any?): String {
        return when (value) {
            null -> "-"
            is Boolean -> if (value) "Yes" else "No"
            is Number -> value.toString()
            else -> value.toString()
        }
    }

    private fun resolveConflict(resolution: ConflictResolution) {
        val id = conflictId ?: return

        keepLocalButton.isEnabled = false
        keepServerButton.isEnabled = false
        dismissButton.isEnabled = false

        viewModel.resolveConflict(id, resolution)

        val message = when (resolution) {
            ConflictResolution.KEEP_LOCAL -> R.string.conflict_resolved_keep_local
            ConflictResolution.KEEP_SERVER -> R.string.conflict_resolved_keep_server
            ConflictResolution.DISMISS -> R.string.conflict_resolved_dismissed
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        dismiss()
    }
}
