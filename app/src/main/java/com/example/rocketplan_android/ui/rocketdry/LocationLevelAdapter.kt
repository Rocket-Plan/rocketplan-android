package com.example.rocketplan_android.ui.rocketdry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

class LocationLevelAdapter : RecyclerView.Adapter<LocationLevelAdapter.ViewHolder>() {

    private val levels: MutableList<LocationLevel> = mutableListOf()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_location_level, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(levels[position])
    }

    override fun getItemCount(): Int = levels.size

    fun submitLevels(items: List<LocationLevel>) {
        levels.clear()
        levels.addAll(items)
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val levelName: TextView = itemView.findViewById(R.id.levelName)
        private val locationsInLevelRecyclerView: RecyclerView =
            itemView.findViewById(R.id.locationsInLevelRecyclerView)
        private val locationAdapter = LocationCardAdapter()
        private val gridLayoutManager = GridLayoutManager(itemView.context, 2)

        init {
            locationsInLevelRecyclerView.layoutManager = gridLayoutManager
            locationsInLevelRecyclerView.adapter = locationAdapter
        }

        fun bind(level: LocationLevel) {
            levelName.text = level.levelName
            locationAdapter.submitLocations(level.locations)
        }
    }
}

class LocationCardAdapter : RecyclerView.Adapter<LocationCardAdapter.ViewHolder>() {

    private val locations: MutableList<LocationItem> = mutableListOf()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_location_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(locations[position])
    }

    override fun getItemCount(): Int = locations.size

    fun submitLocations(items: List<LocationItem>) {
        locations.clear()
        locations.addAll(items)
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val locationName: TextView = itemView.findViewById(R.id.locationName)
        private val materialCount: TextView = itemView.findViewById(R.id.materialCount)
        private val locationIcon: ImageView = itemView.findViewById(R.id.locationIcon)

        fun bind(location: LocationItem) {
            locationName.text = location.name
            locationIcon.setImageResource(location.iconRes)
            locationIcon.contentDescription = location.name
            materialCount.text = if (location.materialCount == 1) {
                itemView.context.getString(R.string.material_count, location.materialCount)
            } else {
                itemView.context.getString(R.string.materials_count, location.materialCount)
            }
            itemView.doOnLayout { view ->
                val params = view.layoutParams ?: return@doOnLayout
                val targetSize = view.width
                if (targetSize > 0 && params.height != targetSize) {
                    params.height = targetSize
                    view.layoutParams = params
                }
            }
        }
    }
}
