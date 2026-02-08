package com.example.rocketplan_android.ui.rocketdry

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

class SetGoalAverageFragment : BottomSheetDialogFragment() {

    private val args: SetGoalAverageFragmentArgs by navArgs()

    @Suppress("DEPRECATION")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_set_goal_average, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val title = view.findViewById<TextView>(R.id.goalTitle)
        title.text = getString(R.string.drying_set_goal_title)

        val description = view.findViewById<TextView>(R.id.goalDescription)
        val areaName = args.areaName.takeIf { it.isNotBlank() } ?: ""
        description.text = getString(R.string.drying_set_goal_description, args.materialName, areaName)

        val goalInput = view.findViewById<EditText>(R.id.goalInput)
        goalInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                goalInput.clearFocus()
                true
            } else false
        }

        val setGoalButton = view.findViewById<MaterialButton>(R.id.setGoalButton)
        setGoalButton.setOnClickListener {
            val raw = goalInput.text?.toString()?.trim().orEmpty()
            val value = raw.toDoubleOrNull()
            if (value == null || value <= 0) {
                Toast.makeText(requireContext(), R.string.rocketdry_goal_target_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            findNavController().navigate(
                SetGoalAverageFragmentDirections
                    .actionMaterialDryingGoalFragmentToMaterialDryingReadingFragment(
                        projectId = args.projectId,
                        roomId = args.roomId,
                        materialName = args.materialName,
                        materialId = args.materialId,
                        goalValue = value.toFloat(),
                        areaName = args.areaName
                    )
            )
        }

        goalInput.requestFocus()
    }
}
