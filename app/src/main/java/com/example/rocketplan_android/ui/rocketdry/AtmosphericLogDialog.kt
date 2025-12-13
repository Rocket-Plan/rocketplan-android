package com.example.rocketplan_android.ui.rocketdry

import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.DecimalFormat

/**
 * Displays the multi-step atmospheric log input wizard and invokes [onSave] when finished.
 */
@Suppress("DEPRECATION") // SOFT_INPUT_ADJUST_RESIZE still needed for dialog keyboard behavior
fun Fragment.showAtmosphericLogDialog(
    title: String,
    onSave: (humidity: Double, temperature: Double, pressure: Double, windSpeed: Double) -> Unit
) {
    data class InputStep(
        val labelRes: Int,
        val unit: String,
        val getter: () -> Double?,
        val setter: (Double?) -> Unit
    )

    val dialogView = layoutInflater.inflate(R.layout.dialog_add_external_log, null)
    val wizardTitle = dialogView.findViewById<TextView>(R.id.wizardTitle)
    val stepLabel = dialogView.findViewById<TextView>(R.id.stepLabel)
    val stepPreview = dialogView.findViewById<TextView>(R.id.stepPreview)
    val stepPosition = dialogView.findViewById<TextView>(R.id.stepPosition)
    val stepInputLayout = dialogView.findViewById<TextInputLayout>(R.id.stepInputLayout)
    val stepInput = dialogView.findViewById<TextInputEditText>(R.id.stepInput)
    val previousStepButton = dialogView.findViewById<MaterialButton>(R.id.previousStepButton)
    val cancelWizardButton = dialogView.findViewById<MaterialButton>(R.id.cancelWizardButton)
    val nextStepButton = dialogView.findViewById<MaterialButton>(R.id.nextStepButton)

    val dialog = MaterialAlertDialogBuilder(requireContext())
        .setView(dialogView)
        .setCancelable(true)
        .create()

    dialog.window?.setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    )

    wizardTitle.text = title

    var humidity: Double? = null
    var temperature: Double? = null
    var pressure: Double? = null
    var windSpeed: Double? = null

    val numberFormatter = DecimalFormat("#.##")
    val steps = listOf(
        InputStep(
            labelRes = R.string.rocketdry_relative_humidity_label,
            unit = getString(R.string.percent),
            getter = { humidity },
            setter = { humidity = it }
        ),
        InputStep(
            labelRes = R.string.rocketdry_temperature_label,
            unit = getString(R.string.fahrenheit),
            getter = { temperature },
            setter = { temperature = it }
        ),
        InputStep(
            labelRes = R.string.rocketdry_pressure_label,
            unit = getString(R.string.kpa),
            getter = { pressure },
            setter = { pressure = it }
        ),
        InputStep(
            labelRes = R.string.rocketdry_wind_speed_label,
            unit = getString(R.string.mph),
            getter = { windSpeed },
            setter = { windSpeed = it }
        )
    )

    var currentStep = 0

    fun formatPreview(value: Double?, unit: String): String {
        val formattedValue = value?.let { numberFormatter.format(it) } ?: "-"
        return if (unit.isBlank()) formattedValue else "$formattedValue $unit"
    }

    fun bindStep() {
        val step = steps[currentStep]
        val currentValue = step.getter()
        stepLabel.text = getString(step.labelRes)
        stepInputLayout.hint = getString(step.labelRes)
        stepPreview.text = formatPreview(currentValue, step.unit)
        stepPosition.text = getString(
            R.string.rocketdry_step_indicator,
            currentStep + 1,
            steps.size
        )
        stepInputLayout.error = null
        val textValue = currentValue?.let { numberFormatter.format(it) } ?: ""
        stepInput.setText(textValue)
        stepInput.setSelection(stepInput.text?.length ?: 0)
        stepInput.imeOptions = if (currentStep == steps.lastIndex) {
            EditorInfo.IME_ACTION_DONE
        } else {
            EditorInfo.IME_ACTION_NEXT
        }
        previousStepButton.isEnabled = currentStep > 0
        nextStepButton.text = if (currentStep == steps.lastIndex) {
            getString(R.string.save)
        } else {
            getString(R.string.rocketdry_next)
        }
    }

    fun persistCurrentValue(): Boolean {
        val value = stepInput.text?.toString()?.toDoubleOrNull()
        stepInputLayout.error = null
        steps[currentStep].setter(value)
        return true
    }

    stepInput.doAfterTextChanged {
        stepInputLayout.error = null
        steps.getOrNull(currentStep)?.let { step ->
            stepPreview.text = formatPreview(it?.toString()?.toDoubleOrNull(), step.unit)
        }
    }

    stepInput.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE) {
            nextStepButton.performClick()
            true
        } else {
            false
        }
    }

    previousStepButton.setOnClickListener {
        if (currentStep == 0) return@setOnClickListener
        stepInputLayout.error = null
        stepInput.text?.toString()?.toDoubleOrNull()?.let { value ->
            steps[currentStep].setter(value)
        }
        currentStep -= 1
        bindStep()
    }

    cancelWizardButton.setOnClickListener {
        dialog.dismiss()
    }

    nextStepButton.setOnClickListener {
        val isValid = persistCurrentValue()
        if (!isValid) return@setOnClickListener

        if (currentStep == steps.lastIndex) {
            onSave(
                humidity ?: 0.0,
                temperature ?: 0.0,
                pressure ?: 0.0,
                windSpeed ?: 0.0
            )
            dialog.dismiss()
        } else {
            currentStep += 1
            bindStep()
        }
    }

    bindStep()
    dialog.show()
    stepInput.requestFocus()
}
