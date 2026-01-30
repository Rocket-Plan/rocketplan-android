package com.example.rocketplan_android.ui.rocketdry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import coil.load
import com.example.rocketplan_android.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlin.math.roundToInt

/**
 * Bottom sheet dialog showing atmospheric log details with edit/delete actions.
 */
class AtmosphericLogDetailBottomSheet : BottomSheetDialogFragment() {

    interface Callback {
        fun onEditRequested(logId: Long)
        fun onDeleteRequested(logId: Long)
    }

    companion object {
        const val TAG = "AtmosphericLogDetailBottomSheet"
        private const val ARG_LOG_ID = "log_id"
        private const val ARG_DATE_TIME = "date_time"
        private const val ARG_HUMIDITY = "humidity"
        private const val ARG_TEMPERATURE = "temperature"
        private const val ARG_PRESSURE = "pressure"
        private const val ARG_WIND_SPEED = "wind_speed"
        private const val ARG_PHOTO_URL = "photo_url"
        private const val ARG_PHOTO_LOCAL_PATH = "photo_local_path"
        private const val ARG_CREATED_AT = "created_at"
        private const val ARG_UPDATED_AT = "updated_at"

        fun newInstance(log: AtmosphericLogItem): AtmosphericLogDetailBottomSheet {
            return AtmosphericLogDetailBottomSheet().apply {
                arguments = bundleOf(
                    ARG_LOG_ID to log.logId,
                    ARG_DATE_TIME to log.dateTime,
                    ARG_HUMIDITY to log.humidity,
                    ARG_TEMPERATURE to log.temperature,
                    ARG_PRESSURE to log.pressure,
                    ARG_WIND_SPEED to log.windSpeed,
                    ARG_PHOTO_URL to log.photoUrl,
                    ARG_PHOTO_LOCAL_PATH to log.photoLocalPath,
                    ARG_CREATED_AT to log.createdAt,
                    ARG_UPDATED_AT to log.updatedAt
                )
            }
        }
    }

    var callback: Callback? = null

    private var logId: Long = 0
    private var dateTime: String = ""
    private var humidity: Double = 0.0
    private var temperature: Double = 0.0
    private var pressure: Double = 0.0
    private var windSpeed: Double = 0.0
    private var photoUrl: String? = null
    private var photoLocalPath: String? = null
    private var createdAt: String? = null
    private var updatedAt: String? = null

    private lateinit var photoThumbnail: ImageView
    private lateinit var photoPlaceholder: ImageView
    private lateinit var logDateTime: TextView
    private lateinit var humidityValue: TextView
    private lateinit var temperatureValue: TextView
    private lateinit var pressureValue: TextView
    private lateinit var windSpeedValue: TextView
    private lateinit var recordedOnValue: TextView
    private lateinit var editedOnContainer: LinearLayout
    private lateinit var editedOnValue: TextView
    private lateinit var editRecordButton: MaterialButton
    private lateinit var deleteRecordButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            logId = args.getLong(ARG_LOG_ID)
            dateTime = args.getString(ARG_DATE_TIME, "")
            humidity = args.getDouble(ARG_HUMIDITY)
            temperature = args.getDouble(ARG_TEMPERATURE)
            pressure = args.getDouble(ARG_PRESSURE)
            windSpeed = args.getDouble(ARG_WIND_SPEED)
            photoUrl = args.getString(ARG_PHOTO_URL)
            photoLocalPath = args.getString(ARG_PHOTO_LOCAL_PATH)
            createdAt = args.getString(ARG_CREATED_AT)
            updatedAt = args.getString(ARG_UPDATED_AT)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_atmospheric_log_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        photoThumbnail = view.findViewById(R.id.photoThumbnail)
        photoPlaceholder = view.findViewById(R.id.photoPlaceholder)
        logDateTime = view.findViewById(R.id.logDateTime)
        humidityValue = view.findViewById(R.id.humidityValue)
        temperatureValue = view.findViewById(R.id.temperatureValue)
        pressureValue = view.findViewById(R.id.pressureValue)
        windSpeedValue = view.findViewById(R.id.windSpeedValue)
        recordedOnValue = view.findViewById(R.id.recordedOnValue)
        editedOnContainer = view.findViewById(R.id.editedOnContainer)
        editedOnValue = view.findViewById(R.id.editedOnValue)
        editRecordButton = view.findViewById(R.id.editRecordButton)
        deleteRecordButton = view.findViewById(R.id.deleteRecordButton)

        bindData()
        setupButtons()
    }

    private fun bindData() {
        logDateTime.text = dateTime
        humidityValue.text = humidity.roundToInt().toString()
        temperatureValue.text = temperature.roundToInt().toString()
        pressureValue.text = pressure.roundToInt().toString()
        windSpeedValue.text = windSpeed.roundToInt().toString()

        recordedOnValue.text = createdAt ?: dateTime

        // Show edited on only if different from created
        if (!updatedAt.isNullOrBlank() && updatedAt != createdAt) {
            editedOnContainer.isVisible = true
            editedOnValue.text = updatedAt
        } else {
            editedOnContainer.isVisible = false
        }

        // Load photo
        loadPhoto()
    }

    private fun loadPhoto() {
        // Try local path first, then URL
        val localPath = photoLocalPath
        if (!localPath.isNullOrBlank()) {
            val file = File(localPath)
            if (file.exists()) {
                photoThumbnail.load(file) {
                    crossfade(true)
                }
                photoThumbnail.isVisible = true
                photoPlaceholder.isVisible = false
                return
            }
        }

        val url = photoUrl
        if (!url.isNullOrBlank()) {
            photoThumbnail.load(url) {
                crossfade(true)
            }
            photoThumbnail.isVisible = true
            photoPlaceholder.isVisible = false
        } else {
            photoThumbnail.isVisible = false
            photoPlaceholder.isVisible = true
        }
    }

    private fun setupButtons() {
        editRecordButton.setOnClickListener {
            callback?.onEditRequested(logId)
            dismiss()
        }

        deleteRecordButton.setOnClickListener {
            showDeleteConfirmation()
        }
    }

    private fun showDeleteConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.atmospheric_log_delete_title)
            .setMessage(R.string.atmospheric_log_delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                callback?.onDeleteRequested(logId)
                dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
