package com.example.rocketplan_android.ui.rocketdry

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class MaterialDryingSelectFragment : Fragment() {

    private val args: MaterialDryingSelectFragmentArgs by navArgs()
    private var selectedMaterial: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_material_drying_select, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val title = view.findViewById<TextView>(R.id.selectTitle)
        title.text = getString(R.string.drying_select_materials_title, args.areaName)

        view.findViewById<ImageView>(R.id.backButton).setOnClickListener {
            findNavController().popBackStack()
        }

        val allMaterials = DryingAreaData.materialsByArea[args.areaName].orEmpty()
        val recyclerView = view.findViewById<RecyclerView>(R.id.materialList)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val adapter = MaterialListAdapter(allMaterials.toMutableList()) { material ->
            selectedMaterial = material
            view.findViewById<MaterialButton>(R.id.selectMaterialButton).isEnabled = true
        }
        recyclerView.adapter = adapter

        val searchInput = view.findViewById<TextInputEditText>(R.id.searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                val filtered = if (query.isBlank()) {
                    allMaterials
                } else {
                    allMaterials.filter { it.contains(query, ignoreCase = true) }
                }
                adapter.updateList(filtered)
            }
        })

        val selectButton = view.findViewById<MaterialButton>(R.id.selectMaterialButton)
        selectButton.setOnClickListener {
            val material = selectedMaterial ?: return@setOnClickListener
            findNavController().navigate(
                MaterialDryingSelectFragmentDirections
                    .actionMaterialDryingSelectFragmentToMaterialDryingGoalFragment(
                        projectId = args.projectId,
                        roomId = args.roomId,
                        materialName = material,
                        areaName = args.areaName
                    )
            )
        }
    }

    private class MaterialListAdapter(
        private var materials: List<String>,
        private val onItemSelected: (String) -> Unit
    ) : RecyclerView.Adapter<MaterialListAdapter.ViewHolder>() {

        private var selectedPosition = -1

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(R.id.materialName)
            val indicator: View = itemView.findViewById(R.id.materialSelectedIndicator)
        }

        fun updateList(newMaterials: List<String>) {
            materials = newMaterials
            selectedPosition = -1
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_drying_material, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val material = materials[position]
            holder.name.text = material
            val isSelected = position == selectedPosition
            holder.indicator.isVisible = isSelected
            holder.name.setTextColor(
                ContextCompat.getColor(
                    holder.itemView.context,
                    if (isSelected) R.color.main_purple else R.color.dark_text_rp
                )
            )
            holder.itemView.setOnClickListener {
                val oldPos = selectedPosition
                selectedPosition = holder.bindingAdapterPosition
                if (oldPos >= 0) notifyItemChanged(oldPos)
                notifyItemChanged(selectedPosition)
                onItemSelected(material)
            }
        }

        override fun getItemCount(): Int = materials.size
    }
}
