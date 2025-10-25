package com.example.rocketplan_android.ui.rocketdry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

class LocationLevelAdapter(
    private val levels: List<LocationLevel>
) : RecyclerView.Adapter<LocationLevelAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_location_level, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(levels[position])
    }

    override fun getItemCount(): Int = levels.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val levelName: TextView = itemView.findViewById(R.id.levelName)
        private val locationsInLevelRecyclerView: RecyclerView =
            itemView.findViewById(R.id.locationsInLevelRecyclerView)

        fun bind(level: LocationLevel) {
            levelName.text = level.levelName

            locationsInLevelRecyclerView.layoutManager = LinearLayoutManager(itemView.context)
            locationsInLevelRecyclerView.adapter = LocationCardAdapter(level.locations)
        }
    }
}

class LocationCardAdapter(
    private val locations: List<LocationItem>
) : RecyclerView.Adapter<LocationCardAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_location_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(locations[position])
    }

    override fun getItemCount(): Int = locations.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val locationName: TextView = itemView.findViewById(R.id.locationName)
        private val materialCount: TextView = itemView.findViewById(R.id.materialCount)

        fun bind(location: LocationItem) {
            locationName.text = location.name
            materialCount.text = if (location.materialCount == 1) {
                itemView.context.getString(R.string.material_count, location.materialCount)
            } else {
                itemView.context.getString(R.string.materials_count, location.materialCount)
            }
        }
    }
}
