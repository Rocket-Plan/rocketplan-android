package com.example.rocketplan_android.ui.rocketdry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView

class RocketDryFragment : Fragment() {

    companion object {
        private const val TAG = "RocketDryFragment"
    }

    private val args: RocketDryFragmentArgs by navArgs()

    private lateinit var backButton: ImageButton
    private lateinit var menuButton: ImageButton
    private lateinit var projectAddress: TextView
    private lateinit var editAddressButton: ImageButton
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var equipmentButton: MaterialButton
    private lateinit var moistureButton: MaterialButton
    private lateinit var roomCard: MaterialCardView
    private lateinit var exteriorSpaceCard: MaterialCardView
    private lateinit var atmosphericLogsRecyclerView: RecyclerView
    private lateinit var locationsRecyclerView: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_rocket_dry, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupClickListeners()
        setupRecyclerViews()
        loadProjectData()
    }

    private fun initializeViews(view: View) {
        backButton = view.findViewById(R.id.backButton)
        menuButton = view.findViewById(R.id.menuButton)
        projectAddress = view.findViewById(R.id.projectAddress)
        editAddressButton = view.findViewById(R.id.editAddressButton)
        toggleGroup = view.findViewById(R.id.toggleGroup)
        equipmentButton = view.findViewById(R.id.equipmentButton)
        moistureButton = view.findViewById(R.id.moistureButton)
        roomCard = view.findViewById(R.id.roomCard)
        exteriorSpaceCard = view.findViewById(R.id.exteriorSpaceCard)
        atmosphericLogsRecyclerView = view.findViewById(R.id.atmosphericLogsRecyclerView)
        locationsRecyclerView = view.findViewById(R.id.locationsRecyclerView)
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        menuButton.setOnClickListener {
            Toast.makeText(context, "Menu", Toast.LENGTH_SHORT).show()
        }

        editAddressButton.setOnClickListener {
            Toast.makeText(context, "Edit Address", Toast.LENGTH_SHORT).show()
        }

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.equipmentButton -> {
                        Toast.makeText(context, "Equipment view", Toast.LENGTH_SHORT).show()
                    }
                    R.id.moistureButton -> {
                        Toast.makeText(context, "Moisture view", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        roomCard.setOnClickListener {
            Toast.makeText(context, "Add Room", Toast.LENGTH_SHORT).show()
        }

        exteriorSpaceCard.setOnClickListener {
            Toast.makeText(context, "Add Exterior Space", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupRecyclerViews() {
        // Atmospheric Logs RecyclerView
        atmosphericLogsRecyclerView.layoutManager = LinearLayoutManager(context)
        atmosphericLogsRecyclerView.adapter = AtmosphericLogAdapter(getSampleAtmosphericLogs())

        // Locations RecyclerView
        locationsRecyclerView.layoutManager = LinearLayoutManager(context)
        locationsRecyclerView.adapter = LocationLevelAdapter(getSampleLocationLevels())
    }

    private fun loadProjectData() {
        // TODO: Load actual project data using the projectId from args
        projectAddress.text = "201 Faker Road"
    }

    // Sample data for demonstration
    private fun getSampleAtmosphericLogs(): List<AtmosphericLogItem> {
        return listOf(
            AtmosphericLogItem(
                dateTime = "Oct 21, 4:19pm",
                humidity = 6.0,
                temperature = 6.0,
                pressure = 6.0,
                windSpeed = 6.0
            )
        )
    }

    private fun getSampleLocationLevels(): List<LocationLevel> {
        return listOf(
            LocationLevel(
                levelName = "Main Level",
                locations = listOf(
                    LocationItem(
                        name = "Basement",
                        materialCount = 1
                    )
                )
            ),
            LocationLevel(
                levelName = "Main Level",
                locations = emptyList()
            )
        )
    }
}

// Data classes for display
data class AtmosphericLogItem(
    val dateTime: String,
    val humidity: Double,
    val temperature: Double,
    val pressure: Double,
    val windSpeed: Double
)

data class LocationLevel(
    val levelName: String,
    val locations: List<LocationItem>
)

data class LocationItem(
    val name: String,
    val materialCount: Int
)
