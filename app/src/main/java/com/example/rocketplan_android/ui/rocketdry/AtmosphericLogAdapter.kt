package com.example.rocketplan_android.ui.rocketdry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import kotlin.math.roundToInt

class AtmosphericLogAdapter(
    private val onAddLogClicked: () -> Unit,
    private val onItemClicked: ((AtmosphericLogItem) -> Unit)? = null
) : RecyclerView.Adapter<AtmosphericLogAdapter.ViewHolder>() {

    private val logs: MutableList<AtmosphericLogItem> = mutableListOf()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_atmospheric_log, parent, false)
        return ViewHolder(view, onAddLogClicked, onItemClicked)
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
        private val onAddLogClicked: () -> Unit,
        private val onItemClicked: ((AtmosphericLogItem) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {
        private val logDateTime: TextView = itemView.findViewById(R.id.logDateTime)
        private val humidityValue: TextView = itemView.findViewById(R.id.humidityValue)
        private val temperatureValue: TextView = itemView.findViewById(R.id.temperatureValue)
        private val pressureValue: TextView = itemView.findViewById(R.id.pressureValue)
        private val windSpeedValue: TextView = itemView.findViewById(R.id.windSpeedValue)
        private val addLogButton: ImageButton = itemView.findViewById(R.id.addLogButton)

        fun bind(log: AtmosphericLogItem) {
            logDateTime.text = log.dateTime
            humidityValue.text = log.humidity.roundToInt().toString()
            temperatureValue.text = log.temperature.roundToInt().toString()
            pressureValue.text = log.pressure.roundToInt().toString()
            windSpeedValue.text = log.windSpeed.roundToInt().toString()

            addLogButton.setOnClickListener { onAddLogClicked() }

            // Make the entire card clickable
            itemView.setOnClickListener {
                onItemClicked?.invoke(log)
            }
        }
    }
}
