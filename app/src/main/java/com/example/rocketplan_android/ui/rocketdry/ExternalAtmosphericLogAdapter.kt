package com.example.rocketplan_android.ui.rocketdry

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import java.io.File
import kotlin.math.roundToInt

class ExternalAtmosphericLogAdapter(
    private val onItemClicked: (AtmosphericLogItem) -> Unit
) : RecyclerView.Adapter<ExternalAtmosphericLogAdapter.ViewHolder>() {

    private val logs: MutableList<AtmosphericLogItem> = mutableListOf()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_external_atmospheric_log_list, parent, false)
        return ViewHolder(view, onItemClicked)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(logs[position])
    }

    override fun getItemCount(): Int = logs.size

    fun submitLogs(items: List<AtmosphericLogItem>) {
        logs.clear()
        logs.addAll(items)
        notifyDataSetChanged()
    }

    class ViewHolder(
        itemView: View,
        private val onItemClicked: (AtmosphericLogItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val photoThumbnail: ImageView = itemView.findViewById(R.id.photoThumbnail)
        private val photoPlaceholder: ImageView = itemView.findViewById(R.id.photoPlaceholder)
        private val logHeader: TextView = itemView.findViewById(R.id.logHeader)
        private val humidityValue: TextView = itemView.findViewById(R.id.humidityValue)
        private val temperatureValue: TextView = itemView.findViewById(R.id.temperatureValue)
        private val pressureValue: TextView = itemView.findViewById(R.id.pressureValue)
        private val windSpeedValue: TextView = itemView.findViewById(R.id.windSpeedValue)

        fun bind(log: AtmosphericLogItem) {
            logHeader.text = log.dateTime
            humidityValue.text = "${log.humidity.roundToInt()}%"
            temperatureValue.text = "${log.temperature.roundToInt()}°F"
            pressureValue.text = "${log.pressure.roundToInt()} kPa"
            windSpeedValue.text = "${log.windSpeed.roundToInt()} mph"

            // Load photo
            loadPhoto(log)

            itemView.setOnClickListener {
                onItemClicked(log)
            }
        }

        private fun loadPhoto(log: AtmosphericLogItem) {
            val localPath = log.photoLocalPath
            if (!localPath.isNullOrBlank()) {
                val file = File(localPath)
                if (file.exists()) {
                    photoThumbnail.setImageURI(Uri.fromFile(file))
                    photoThumbnail.isVisible = true
                    photoPlaceholder.isVisible = false
                    return
                }
            }

            // TODO: Load from URL using Glide/Coil if photoUrl is available

            photoThumbnail.isVisible = false
            photoPlaceholder.isVisible = true
        }
    }
}
