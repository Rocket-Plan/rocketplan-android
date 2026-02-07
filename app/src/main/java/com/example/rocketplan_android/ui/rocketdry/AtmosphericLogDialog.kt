package com.example.rocketplan_android.ui.rocketdry

import android.net.Uri
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import coil.load
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.text.DecimalFormat

/**
 * Callback interface for photo capture in the dialog.
 */
interface AtmosphericLogPhotoCallback {
    fun onTakePhotoRequested(callback: (Uri?) -> Unit)
}

/**
 * Displays the multi-step atmospheric log input wizard and invokes [onSave] when finished.
 * Now includes an optional photo capture step (step 5).
 */
@Suppress("DEPRECATION") // SOFT_INPUT_ADJUST_RESIZE still needed for dialog keyboard behavior
fun Fragment.showAtmosphericLogDialog(
    title: String,
    areaLabel: String? = null,
    onAreaClicked: ((updateLabel: (String) -> Unit) -> Unit)? = null,
    onRenameAreaClicked: ((updateLabel: (String) -> Unit) -> Unit)? = null,
    photoCallback: AtmosphericLogPhotoCallback? = null,
    onSave: (humidity: Double, temperature: Double, pressure: Double, windSpeed: Double, photoLocalPath: String?) -> Unit
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
    val areaContainer = dialogView.findViewById<View>(R.id.areaContainer)
    val areaName = dialogView.findViewById<TextView>(R.id.areaName)
    val changeAreaButton = dialogView.findViewById<MaterialButton>(R.id.changeAreaButton)
    val renameAreaButton = dialogView.findViewById<MaterialButton>(R.id.renameAreaButton)
    val inputStepContainer = dialogView.findViewById<LinearLayout>(R.id.inputStepContainer)
    val stepInputLayout = dialogView.findViewById<TextInputLayout>(R.id.stepInputLayout)
    val stepInput = dialogView.findViewById<TextInputEditText>(R.id.stepInput)
    val previousStepButton = dialogView.findViewById<MaterialButton>(R.id.previousStepButton)
    val cancelWizardButton = dialogView.findViewById<MaterialButton>(R.id.cancelWizardButton)
    val nextStepButton = dialogView.findViewById<MaterialButton>(R.id.nextStepButton)

    // Photo step views
    val photoCaptureStep = dialogView.findViewById<View>(R.id.photoCaptureStep)
    val photoPreview = dialogView.findViewById<ImageView>(R.id.photoPreview)
    val photoPlaceholder = dialogView.findViewById<ImageView>(R.id.photoPlaceholder)
    val preCaptureButtons = dialogView.findViewById<LinearLayout>(R.id.preCaptureButtons)
    val postCaptureButtons = dialogView.findViewById<LinearLayout>(R.id.postCaptureButtons)
    val saveWithoutPhotoButton = dialogView.findViewById<MaterialButton>(R.id.saveWithoutPhotoButton)
    val takePhotoButton = dialogView.findViewById<MaterialButton>(R.id.takePhotoButton)
    val recapturePhotoButton = dialogView.findViewById<MaterialButton>(R.id.recapturePhotoButton)
    val saveWithPhotoButton = dialogView.findViewById<MaterialButton>(R.id.saveWithPhotoButton)
    val photoStepHumidity = dialogView.findViewById<TextView>(R.id.photoStepHumidity)
    val photoStepTemperature = dialogView.findViewById<TextView>(R.id.photoStepTemperature)
    val photoStepPressure = dialogView.findViewById<TextView>(R.id.photoStepPressure)
    val photoStepWindSpeed = dialogView.findViewById<TextView>(R.id.photoStepWindSpeed)

    val dialog = MaterialAlertDialogBuilder(requireContext())
        .setView(dialogView)
        .setCancelable(true)
        .create()

    dialog.window?.setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    )

    wizardTitle.text = title

    val hasAreaUi = areaLabel != null || onAreaClicked != null || onRenameAreaClicked != null
    if (hasAreaUi) {
        areaContainer.isVisible = true
        areaName.text = areaLabel ?: getString(R.string.rocketdry_atmos_room_unknown)
        changeAreaButton.isVisible = onAreaClicked != null
        changeAreaButton.setOnClickListener {
            onAreaClicked?.invoke { updatedLabel ->
                areaName.text = updatedLabel
            }
        }
        renameAreaButton.isVisible = onRenameAreaClicked != null
        renameAreaButton.setOnClickListener {
            onRenameAreaClicked?.invoke { updatedLabel ->
                areaName.text = updatedLabel
            }
        }
    } else {
        areaContainer.isVisible = false
    }

    var humidity: Double? = null
    var temperature: Double? = null
    var pressure: Double? = null
    var windSpeed: Double? = null
    var capturedPhotoPath: String? = null

    val numberFormatter = DecimalFormat("#.##")
    val inputSteps = listOf(
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

    // Total steps: 4 input steps + 1 photo step (if photoCallback provided)
    val hasPhotoStep = photoCallback != null
    val totalSteps = if (hasPhotoStep) inputSteps.size + 1 else inputSteps.size
    val photoStepIndex = inputSteps.size // Step 5 (index 4)

    var currentStep = 0

    fun formatPreview(value: Double?, unit: String): String {
        val formattedValue = value?.let { numberFormatter.format(it) } ?: "-"
        return if (unit.isBlank()) formattedValue else "$formattedValue $unit"
    }

    fun showPhotoStep() {
        inputStepContainer.isVisible = false
        stepLabel.isVisible = false
        stepPreview.isVisible = false
        photoCaptureStep.isVisible = true
        nextStepButton.isVisible = false
        cancelWizardButton.isVisible = false

        // Hide area container and navigation on photo step
        areaContainer.isVisible = false
        stepPosition.isVisible = false
        previousStepButton.isVisible = false

        // Hide keyboard
        stepInput.clearFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(stepInput.windowToken, 0)

        // Populate metrics summary
        val humidityVal = humidity?.let { numberFormatter.format(it) } ?: "0"
        val temperatureVal = temperature?.let { numberFormatter.format(it) } ?: "0"
        val pressureVal = pressure?.let { numberFormatter.format(it) } ?: "0"
        val windSpeedVal = windSpeed?.let { numberFormatter.format(it) } ?: "0"

        photoStepHumidity.text = getString(R.string.atmospheric_humidity_format, humidityVal)
        photoStepTemperature.text = getString(R.string.atmospheric_temperature_format, temperatureVal)
        photoStepPressure.text = getString(R.string.atmospheric_pressure_format, pressureVal)
        photoStepWindSpeed.text = getString(R.string.atmospheric_wind_speed_format, windSpeedVal)

        // Show appropriate buttons based on whether photo was captured
        if (capturedPhotoPath != null) {
            preCaptureButtons.isVisible = false
            postCaptureButtons.isVisible = true
            photoPreview.isVisible = true
            photoPlaceholder.isVisible = false
        } else {
            preCaptureButtons.isVisible = true
            postCaptureButtons.isVisible = false
            photoPreview.isVisible = false
            photoPlaceholder.isVisible = true
        }
    }

    fun showInputStep() {
        inputStepContainer.isVisible = true
        stepLabel.isVisible = true
        stepPreview.isVisible = true
        stepPosition.isVisible = true
        photoCaptureStep.isVisible = false
        nextStepButton.isVisible = true
        cancelWizardButton.isVisible = true
        previousStepButton.isVisible = true
        if (hasAreaUi) {
            areaContainer.isVisible = true
        }
    }

    fun bindStep() {
        if (currentStep == photoStepIndex && hasPhotoStep) {
            showPhotoStep()
            return
        }

        showInputStep()

        val step = inputSteps[currentStep]
        val currentValue = step.getter()
        stepLabel.text = getString(step.labelRes)
        stepInputLayout.hint = getString(step.labelRes)
        stepPreview.text = formatPreview(currentValue, step.unit)
        stepPosition.text = getString(
            R.string.rocketdry_step_indicator,
            currentStep + 1,
            totalSteps
        )
        stepInputLayout.error = null
        val textValue = currentValue?.let { numberFormatter.format(it) } ?: ""
        stepInput.setText(textValue)
        stepInput.setSelection(stepInput.text?.length ?: 0)

        // Determine if this is the last input step before photo step
        val isLastInputStep = if (hasPhotoStep) {
            currentStep == inputSteps.lastIndex
        } else {
            currentStep == inputSteps.lastIndex
        }

        stepInput.imeOptions = if (isLastInputStep && !hasPhotoStep) {
            EditorInfo.IME_ACTION_DONE
        } else {
            EditorInfo.IME_ACTION_NEXT
        }

        // On first step show "Cancel" instead of disabled "Back"
        if (currentStep == 0) {
            previousStepButton.isEnabled = true
            previousStepButton.text = getString(R.string.cancel)
            previousStepButton.icon = null
            cancelWizardButton.isVisible = false
        } else {
            previousStepButton.isEnabled = true
            previousStepButton.text = getString(R.string.rocketdry_back)
            previousStepButton.setIconResource(R.drawable.chevron_left)
            cancelWizardButton.isVisible = true
        }

        // Show "Next" for all input steps (photo step or save handled separately)
        nextStepButton.text = if (currentStep == inputSteps.lastIndex && !hasPhotoStep) {
            getString(R.string.save)
        } else {
            getString(R.string.rocketdry_next)
        }
    }

    fun persistCurrentValue(): Boolean {
        if (currentStep >= inputSteps.size) return true
        val value = stepInput.text?.toString()?.toDoubleOrNull()
        stepInputLayout.error = null
        inputSteps[currentStep].setter(value)
        return true
    }

    fun saveLog(photoPath: String?) {
        onSave(
            humidity ?: 0.0,
            temperature ?: 0.0,
            pressure ?: 0.0,
            windSpeed ?: 0.0,
            photoPath
        )
        dialog.dismiss()
    }

    fun handleTakePhoto() {
        // Hide dialog while camera is open
        dialog.hide()
        photoCallback?.onTakePhotoRequested { uri ->
            // Post to main thread to ensure we're back on fragment before showing dialog
            view?.post {
                if (!isAdded) return@post
                // Show dialog again when returning from camera
                dialog.show()
                if (uri != null) {
                    capturedPhotoPath = uri.path
                    photoPreview.load(uri)
                    photoPreview.isVisible = true
                    photoPlaceholder.isVisible = false
                    preCaptureButtons.isVisible = false
                    postCaptureButtons.isVisible = true
                }
            }
        }
    }

    stepInput.doAfterTextChanged {
        stepInputLayout.error = null
        inputSteps.getOrNull(currentStep)?.let { step ->
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
        if (currentStep == 0) {
            dialog.dismiss()
            return@setOnClickListener
        }
        stepInputLayout.error = null
        if (currentStep < inputSteps.size) {
            stepInput.text?.toString()?.toDoubleOrNull()?.let { value ->
                inputSteps[currentStep].setter(value)
            }
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

        if (currentStep == inputSteps.lastIndex) {
            if (hasPhotoStep) {
                // Go to photo step
                currentStep = photoStepIndex
                bindStep()
            } else {
                // No photo step, save directly
                saveLog(null)
            }
        } else {
            currentStep += 1
            bindStep()
        }
    }

    // Photo step button handlers
    saveWithoutPhotoButton.setOnClickListener {
        saveLog(null)
    }

    takePhotoButton.setOnClickListener {
        handleTakePhoto()
    }

    // Make photo placeholder clickable to launch camera
    photoPlaceholder.setOnClickListener {
        handleTakePhoto()
    }

    recapturePhotoButton.setOnClickListener {
        capturedPhotoPath = null
        handleTakePhoto()
    }

    saveWithPhotoButton.setOnClickListener {
        saveLog(capturedPhotoPath)
    }

    bindStep()
    dialog.show()
    stepInput.requestFocus()
}

/**
 * Legacy overload for backward compatibility (without photo support).
 */
@Suppress("DEPRECATION")
fun Fragment.showAtmosphericLogDialog(
    title: String,
    areaLabel: String? = null,
    onAreaClicked: ((updateLabel: (String) -> Unit) -> Unit)? = null,
    onRenameAreaClicked: ((updateLabel: (String) -> Unit) -> Unit)? = null,
    onSave: (humidity: Double, temperature: Double, pressure: Double, windSpeed: Double) -> Unit
) {
    showAtmosphericLogDialog(
        title = title,
        areaLabel = areaLabel,
        onAreaClicked = onAreaClicked,
        onRenameAreaClicked = onRenameAreaClicked,
        photoCallback = null,
        onSave = { humidity, temperature, pressure, windSpeed, _ ->
            onSave(humidity, temperature, pressure, windSpeed)
        }
    )
}

/**
 * Shows the atmospheric log dialog with value tracking callback.
 * The onValuesChanged callback is invoked whenever values change, allowing the caller
 * to track current values for restoration after navigation (e.g., to camera).
 */
@Suppress("DEPRECATION")
fun Fragment.showAtmosphericLogDialogWithValueTracking(
    title: String,
    areaLabel: String? = null,
    photoCallback: AtmosphericLogPhotoCallback? = null,
    onValuesChanged: (humidity: Double?, temperature: Double?, pressure: Double?, windSpeed: Double?) -> Unit,
    onSave: (humidity: Double, temperature: Double, pressure: Double, windSpeed: Double, photoLocalPath: String?) -> Unit
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
    val areaContainer = dialogView.findViewById<View>(R.id.areaContainer)
    val areaName = dialogView.findViewById<TextView>(R.id.areaName)
    val changeAreaButton = dialogView.findViewById<MaterialButton>(R.id.changeAreaButton)
    val renameAreaButton = dialogView.findViewById<MaterialButton>(R.id.renameAreaButton)
    val inputStepContainer = dialogView.findViewById<LinearLayout>(R.id.inputStepContainer)
    val stepInputLayout = dialogView.findViewById<TextInputLayout>(R.id.stepInputLayout)
    val stepInput = dialogView.findViewById<TextInputEditText>(R.id.stepInput)
    val previousStepButton = dialogView.findViewById<MaterialButton>(R.id.previousStepButton)
    val cancelWizardButton = dialogView.findViewById<MaterialButton>(R.id.cancelWizardButton)
    val nextStepButton = dialogView.findViewById<MaterialButton>(R.id.nextStepButton)

    // Photo step views
    val photoCaptureStep = dialogView.findViewById<View>(R.id.photoCaptureStep)
    val photoPreview = dialogView.findViewById<ImageView>(R.id.photoPreview)
    val photoPlaceholder = dialogView.findViewById<ImageView>(R.id.photoPlaceholder)
    val preCaptureButtons = dialogView.findViewById<LinearLayout>(R.id.preCaptureButtons)
    val postCaptureButtons = dialogView.findViewById<LinearLayout>(R.id.postCaptureButtons)
    val saveWithoutPhotoButton = dialogView.findViewById<MaterialButton>(R.id.saveWithoutPhotoButton)
    val takePhotoButton = dialogView.findViewById<MaterialButton>(R.id.takePhotoButton)
    val recapturePhotoButton = dialogView.findViewById<MaterialButton>(R.id.recapturePhotoButton)
    val saveWithPhotoButton = dialogView.findViewById<MaterialButton>(R.id.saveWithPhotoButton)
    val photoStepHumidity = dialogView.findViewById<TextView>(R.id.photoStepHumidity)
    val photoStepTemperature = dialogView.findViewById<TextView>(R.id.photoStepTemperature)
    val photoStepPressure = dialogView.findViewById<TextView>(R.id.photoStepPressure)
    val photoStepWindSpeed = dialogView.findViewById<TextView>(R.id.photoStepWindSpeed)

    val dialog = MaterialAlertDialogBuilder(requireContext())
        .setView(dialogView)
        .setCancelable(true)
        .create()

    dialog.window?.setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    )

    wizardTitle.text = title

    val hasAreaUi = areaLabel != null
    if (hasAreaUi) {
        areaContainer.isVisible = true
        areaName.text = areaLabel ?: getString(R.string.rocketdry_atmos_room_unknown)
        changeAreaButton.isVisible = false
        renameAreaButton.isVisible = false
    } else {
        areaContainer.isVisible = false
    }

    var humidity: Double? = null
    var temperature: Double? = null
    var pressure: Double? = null
    var windSpeed: Double? = null
    var capturedPhotoPath: String? = null

    fun notifyValuesChanged() {
        onValuesChanged(humidity, temperature, pressure, windSpeed)
    }

    val numberFormatter = DecimalFormat("#.##")
    val inputSteps = listOf(
        InputStep(
            labelRes = R.string.rocketdry_relative_humidity_label,
            unit = getString(R.string.percent),
            getter = { humidity },
            setter = { humidity = it; notifyValuesChanged() }
        ),
        InputStep(
            labelRes = R.string.rocketdry_temperature_label,
            unit = getString(R.string.fahrenheit),
            getter = { temperature },
            setter = { temperature = it; notifyValuesChanged() }
        ),
        InputStep(
            labelRes = R.string.rocketdry_pressure_label,
            unit = getString(R.string.kpa),
            getter = { pressure },
            setter = { pressure = it; notifyValuesChanged() }
        ),
        InputStep(
            labelRes = R.string.rocketdry_wind_speed_label,
            unit = getString(R.string.mph),
            getter = { windSpeed },
            setter = { windSpeed = it; notifyValuesChanged() }
        )
    )

    val hasPhotoStep = photoCallback != null
    val totalSteps = if (hasPhotoStep) inputSteps.size + 1 else inputSteps.size
    val photoStepIndex = inputSteps.size

    var currentStep = 0

    fun formatPreview(value: Double?, unit: String): String {
        val formattedValue = value?.let { numberFormatter.format(it) } ?: "-"
        return if (unit.isBlank()) formattedValue else "$formattedValue $unit"
    }

    fun showPhotoStep() {
        inputStepContainer.isVisible = false
        stepLabel.isVisible = false
        stepPreview.isVisible = false
        photoCaptureStep.isVisible = true
        nextStepButton.isVisible = false
        cancelWizardButton.isVisible = false

        areaContainer.isVisible = false
        stepPosition.isVisible = false
        previousStepButton.isVisible = false

        stepInput.clearFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(stepInput.windowToken, 0)

        val humidityVal = humidity?.let { numberFormatter.format(it) } ?: "0"
        val temperatureVal = temperature?.let { numberFormatter.format(it) } ?: "0"
        val pressureVal = pressure?.let { numberFormatter.format(it) } ?: "0"
        val windSpeedVal = windSpeed?.let { numberFormatter.format(it) } ?: "0"

        photoStepHumidity.text = getString(R.string.atmospheric_humidity_format, humidityVal)
        photoStepTemperature.text = getString(R.string.atmospheric_temperature_format, temperatureVal)
        photoStepPressure.text = getString(R.string.atmospheric_pressure_format, pressureVal)
        photoStepWindSpeed.text = getString(R.string.atmospheric_wind_speed_format, windSpeedVal)

        if (capturedPhotoPath != null) {
            preCaptureButtons.isVisible = false
            postCaptureButtons.isVisible = true
            photoPreview.isVisible = true
            photoPlaceholder.isVisible = false
        } else {
            preCaptureButtons.isVisible = true
            postCaptureButtons.isVisible = false
            photoPreview.isVisible = false
            photoPlaceholder.isVisible = true
        }
    }

    fun showInputStep() {
        inputStepContainer.isVisible = true
        stepLabel.isVisible = true
        stepPreview.isVisible = true
        stepPosition.isVisible = true
        photoCaptureStep.isVisible = false
        nextStepButton.isVisible = true
        cancelWizardButton.isVisible = true
        previousStepButton.isVisible = true
        if (hasAreaUi) {
            areaContainer.isVisible = true
        }
    }

    fun bindStep() {
        if (currentStep == photoStepIndex && hasPhotoStep) {
            showPhotoStep()
            return
        }

        showInputStep()

        val step = inputSteps[currentStep]
        val currentValue = step.getter()
        stepLabel.text = getString(step.labelRes)
        stepInputLayout.hint = getString(step.labelRes)
        stepPreview.text = formatPreview(currentValue, step.unit)
        stepPosition.text = getString(
            R.string.rocketdry_step_indicator,
            currentStep + 1,
            totalSteps
        )
        stepInputLayout.error = null
        val textValue = currentValue?.let { numberFormatter.format(it) } ?: ""
        stepInput.setText(textValue)
        stepInput.setSelection(stepInput.text?.length ?: 0)

        val isLastInputStep = currentStep == inputSteps.lastIndex

        stepInput.imeOptions = if (isLastInputStep && !hasPhotoStep) {
            EditorInfo.IME_ACTION_DONE
        } else {
            EditorInfo.IME_ACTION_NEXT
        }

        // On first step show "Cancel" instead of disabled "Back"
        if (currentStep == 0) {
            previousStepButton.isEnabled = true
            previousStepButton.text = getString(R.string.cancel)
            previousStepButton.icon = null
            cancelWizardButton.isVisible = false
        } else {
            previousStepButton.isEnabled = true
            previousStepButton.text = getString(R.string.rocketdry_back)
            previousStepButton.setIconResource(R.drawable.chevron_left)
            cancelWizardButton.isVisible = true
        }

        nextStepButton.text = if (currentStep == inputSteps.lastIndex && !hasPhotoStep) {
            getString(R.string.save)
        } else {
            getString(R.string.rocketdry_next)
        }
    }

    fun persistCurrentValue(): Boolean {
        if (currentStep >= inputSteps.size) return true
        val value = stepInput.text?.toString()?.toDoubleOrNull()
        stepInputLayout.error = null
        inputSteps[currentStep].setter(value)
        return true
    }

    fun saveLog(photoPath: String?) {
        onSave(
            humidity ?: 0.0,
            temperature ?: 0.0,
            pressure ?: 0.0,
            windSpeed ?: 0.0,
            photoPath
        )
        dialog.dismiss()
    }

    fun handleTakePhoto() {
        // Dismiss dialog - it will be recreated when returning from camera
        dialog.dismiss()
        photoCallback?.onTakePhotoRequested { /* unused */ }
    }

    stepInput.doAfterTextChanged {
        stepInputLayout.error = null
        inputSteps.getOrNull(currentStep)?.let { step ->
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
        if (currentStep == 0) {
            dialog.dismiss()
            return@setOnClickListener
        }
        stepInputLayout.error = null
        if (currentStep < inputSteps.size) {
            stepInput.text?.toString()?.toDoubleOrNull()?.let { value ->
                inputSteps[currentStep].setter(value)
            }
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

        if (currentStep == inputSteps.lastIndex) {
            if (hasPhotoStep) {
                currentStep = photoStepIndex
                bindStep()
            } else {
                saveLog(null)
            }
        } else {
            currentStep += 1
            bindStep()
        }
    }

    saveWithoutPhotoButton.setOnClickListener {
        saveLog(null)
    }

    takePhotoButton.setOnClickListener {
        handleTakePhoto()
    }

    photoPlaceholder.setOnClickListener {
        handleTakePhoto()
    }

    recapturePhotoButton.setOnClickListener {
        capturedPhotoPath = null
        handleTakePhoto()
    }

    saveWithPhotoButton.setOnClickListener {
        saveLog(capturedPhotoPath)
    }

    bindStep()
    dialog.show()
    stepInput.requestFocus()
}

/**
 * Shows the atmospheric log dialog with pre-filled values and optional photo.
 * Used when returning from camera capture to restore dialog state.
 */
@Suppress("DEPRECATION")
fun Fragment.showAtmosphericLogDialogWithValues(
    title: String,
    areaLabel: String? = null,
    initialHumidity: Double?,
    initialTemperature: Double?,
    initialPressure: Double?,
    initialWindSpeed: Double?,
    initialPhotoPath: String?,
    photoCallback: AtmosphericLogPhotoCallback? = null,
    onSave: (humidity: Double, temperature: Double, pressure: Double, windSpeed: Double, photoLocalPath: String?) -> Unit
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
    val areaContainer = dialogView.findViewById<View>(R.id.areaContainer)
    val areaName = dialogView.findViewById<TextView>(R.id.areaName)
    val changeAreaButton = dialogView.findViewById<MaterialButton>(R.id.changeAreaButton)
    val renameAreaButton = dialogView.findViewById<MaterialButton>(R.id.renameAreaButton)
    val inputStepContainer = dialogView.findViewById<LinearLayout>(R.id.inputStepContainer)
    val stepInputLayout = dialogView.findViewById<TextInputLayout>(R.id.stepInputLayout)
    val stepInput = dialogView.findViewById<TextInputEditText>(R.id.stepInput)
    val previousStepButton = dialogView.findViewById<MaterialButton>(R.id.previousStepButton)
    val cancelWizardButton = dialogView.findViewById<MaterialButton>(R.id.cancelWizardButton)
    val nextStepButton = dialogView.findViewById<MaterialButton>(R.id.nextStepButton)

    // Photo step views
    val photoCaptureStep = dialogView.findViewById<View>(R.id.photoCaptureStep)
    val photoPreview = dialogView.findViewById<ImageView>(R.id.photoPreview)
    val photoPlaceholder = dialogView.findViewById<ImageView>(R.id.photoPlaceholder)
    val preCaptureButtons = dialogView.findViewById<LinearLayout>(R.id.preCaptureButtons)
    val postCaptureButtons = dialogView.findViewById<LinearLayout>(R.id.postCaptureButtons)
    val saveWithoutPhotoButton = dialogView.findViewById<MaterialButton>(R.id.saveWithoutPhotoButton)
    val takePhotoButton = dialogView.findViewById<MaterialButton>(R.id.takePhotoButton)
    val recapturePhotoButton = dialogView.findViewById<MaterialButton>(R.id.recapturePhotoButton)
    val saveWithPhotoButton = dialogView.findViewById<MaterialButton>(R.id.saveWithPhotoButton)
    val photoStepHumidity = dialogView.findViewById<TextView>(R.id.photoStepHumidity)
    val photoStepTemperature = dialogView.findViewById<TextView>(R.id.photoStepTemperature)
    val photoStepPressure = dialogView.findViewById<TextView>(R.id.photoStepPressure)
    val photoStepWindSpeed = dialogView.findViewById<TextView>(R.id.photoStepWindSpeed)

    val dialog = MaterialAlertDialogBuilder(requireContext())
        .setView(dialogView)
        .setCancelable(true)
        .create()

    wizardTitle.text = title

    val hasAreaUi = areaLabel != null
    if (hasAreaUi) {
        areaContainer.isVisible = true
        areaName.text = areaLabel ?: getString(R.string.rocketdry_atmos_room_unknown)
        changeAreaButton.isVisible = false
        renameAreaButton.isVisible = false
    } else {
        areaContainer.isVisible = false
    }

    // Initialize with provided values
    var humidity: Double? = initialHumidity
    var temperature: Double? = initialTemperature
    var pressure: Double? = initialPressure
    var windSpeed: Double? = initialWindSpeed
    var capturedPhotoPath: String? = initialPhotoPath

    val numberFormatter = DecimalFormat("#.##")
    val inputSteps = listOf(
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

    val hasPhotoStep = photoCallback != null
    val totalSteps = if (hasPhotoStep) inputSteps.size + 1 else inputSteps.size
    val photoStepIndex = inputSteps.size

    // Start at photo step since we already have values and possibly a photo
    var currentStep = photoStepIndex

    fun formatPreview(value: Double?, unit: String): String {
        val formattedValue = value?.let { numberFormatter.format(it) } ?: "-"
        return if (unit.isBlank()) formattedValue else "$formattedValue $unit"
    }

    fun showPhotoStep() {
        inputStepContainer.isVisible = false
        stepLabel.isVisible = false
        stepPreview.isVisible = false
        photoCaptureStep.isVisible = true
        nextStepButton.isVisible = false
        cancelWizardButton.isVisible = false

        areaContainer.isVisible = false
        stepPosition.isVisible = false
        previousStepButton.isVisible = false

        val humidityVal = humidity?.let { numberFormatter.format(it) } ?: "0"
        val temperatureVal = temperature?.let { numberFormatter.format(it) } ?: "0"
        val pressureVal = pressure?.let { numberFormatter.format(it) } ?: "0"
        val windSpeedVal = windSpeed?.let { numberFormatter.format(it) } ?: "0"

        photoStepHumidity.text = getString(R.string.atmospheric_humidity_format, humidityVal)
        photoStepTemperature.text = getString(R.string.atmospheric_temperature_format, temperatureVal)
        photoStepPressure.text = getString(R.string.atmospheric_pressure_format, pressureVal)
        photoStepWindSpeed.text = getString(R.string.atmospheric_wind_speed_format, windSpeedVal)

        val photoPath = capturedPhotoPath
        if (photoPath != null) {
            preCaptureButtons.isVisible = false
            postCaptureButtons.isVisible = true
            photoPreview.load(File(photoPath))
            photoPreview.isVisible = true
            photoPlaceholder.isVisible = false
        } else {
            preCaptureButtons.isVisible = true
            postCaptureButtons.isVisible = false
            photoPreview.isVisible = false
            photoPlaceholder.isVisible = true
        }
    }

    fun saveLog(photoPath: String?) {
        onSave(
            humidity ?: 0.0,
            temperature ?: 0.0,
            pressure ?: 0.0,
            windSpeed ?: 0.0,
            photoPath
        )
        dialog.dismiss()
    }

    fun handleTakePhoto() {
        dialog.dismiss()
        photoCallback?.onTakePhotoRequested { /* unused */ }
    }

    saveWithoutPhotoButton.setOnClickListener {
        saveLog(null)
    }

    takePhotoButton.setOnClickListener {
        handleTakePhoto()
    }

    photoPlaceholder.setOnClickListener {
        handleTakePhoto()
    }

    recapturePhotoButton.setOnClickListener {
        capturedPhotoPath = null
        handleTakePhoto()
    }

    saveWithPhotoButton.setOnClickListener {
        saveLog(capturedPhotoPath)
    }

    cancelWizardButton.setOnClickListener {
        dialog.dismiss()
    }

    // Show photo step directly
    showPhotoStep()
    dialog.show()
}
