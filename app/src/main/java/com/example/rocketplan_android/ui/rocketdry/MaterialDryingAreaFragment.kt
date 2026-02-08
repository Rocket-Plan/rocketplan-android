package com.example.rocketplan_android.ui.rocketdry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

class MaterialDryingAreaFragment : Fragment() {

    private val args: MaterialDryingAreaFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_material_drying_area, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageView>(R.id.backButton).setOnClickListener {
            findNavController().popBackStack()
        }

        val recyclerView = view.findViewById<RecyclerView>(R.id.areaGrid)
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        recyclerView.adapter = AreaAdapter(DryingAreaData.areas) { area ->
            findNavController().navigate(
                MaterialDryingAreaFragmentDirections
                    .actionMaterialDryingAreaFragmentToMaterialDryingSelectFragment(
                        projectId = args.projectId,
                        roomId = args.roomId,
                        areaName = area.name
                    )
            )
        }
    }

    private class AreaAdapter(
        private val areas: List<DryingArea>,
        private val onAreaClick: (DryingArea) -> Unit
    ) : RecyclerView.Adapter<AreaAdapter.ViewHolder>() {

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.areaIcon)
            val name: TextView = itemView.findViewById(R.id.areaName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_damage_area, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val area = areas[position]
            holder.icon.setImageResource(area.iconRes)
            holder.name.text = area.name
            holder.itemView.setOnClickListener { onAreaClick(area) }
        }

        override fun getItemCount(): Int = areas.size
    }
}
