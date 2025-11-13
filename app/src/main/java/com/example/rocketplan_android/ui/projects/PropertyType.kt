package com.example.rocketplan_android.ui.projects

/**
 * Frontend representation of the allowable property types.
 *
 * Backend mapping:
 * 1 = Single Unit
 * 2 = Multi Unit
 * 3 = Exterior
 * 4 = Commercial
 * 5 = Single Location
 */
enum class PropertyType(
    val propertyTypeId: Int,
    val apiValue: String
) {
    SINGLE_UNIT(1, "single_unit"),
    MULTI_UNIT(2, "multi_unit"),
    EXTERIOR(3, "exterior"),
    COMMERCIAL(4, "commercial"),
    SINGLE_LOCATION(5, "single_location");

    companion object {
        fun fromApiValue(value: String?): PropertyType? =
            value?.let { api ->
                entries.firstOrNull { it.apiValue.equals(api, ignoreCase = true) }
            }
    }
}
