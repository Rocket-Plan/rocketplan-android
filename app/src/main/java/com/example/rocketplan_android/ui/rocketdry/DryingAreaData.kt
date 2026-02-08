package com.example.rocketplan_android.ui.rocketdry

import com.example.rocketplan_android.R

data class DryingArea(val name: String, val iconRes: Int)

object DryingAreaData {
    val areas = listOf(
        DryingArea("Carpentry", R.drawable.ic_material),
        DryingArea("Ceiling", R.drawable.ic_material),
        DryingArea("Flooring", R.drawable.ic_material),
        DryingArea("Plumbing", R.drawable.ic_material),
        DryingArea("Walls", R.drawable.ic_material)
    )

    val materialsByArea: Map<String, List<String>> = mapOf(
        "Carpentry" to listOf(
            "Baseboard",
            "Baseboard - North Wall",
            "Baseboard - East Wall",
            "Baseboard - South Wall",
            "Baseboard - West Wall",
            "Cabinets",
            "Door Frame",
            "Door Jamb",
            "Trim",
            "Window Frame",
            "Window Sill"
        ),
        "Ceiling" to listOf(
            "Ceiling Tile",
            "Concrete",
            "Flat Drywall",
            "Plaster",
            "Popcorn Ceiling",
            "Textured Drywall",
            "Wood Panel"
        ),
        "Flooring" to listOf(
            "Carpet",
            "Carpet Pad",
            "Concrete",
            "Hardwood",
            "Laminate",
            "Linoleum",
            "OSB",
            "Plywood",
            "Subfloor",
            "Tile",
            "Vinyl"
        ),
        "Plumbing" to listOf(
            "Countertop",
            "Vanity Unit"
        ),
        "Walls" to listOf(
            "1/2\" Drywall",
            "5/8\" Drywall",
            "Brick",
            "Concrete",
            "Drywall",
            "FRP Panel",
            "Insulation",
            "OSB",
            "Paneling",
            "Plaster",
            "Plywood",
            "Stucco"
        )
    )
}
