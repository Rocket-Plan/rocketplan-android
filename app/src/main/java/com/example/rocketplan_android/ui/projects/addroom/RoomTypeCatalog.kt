package com.example.rocketplan_android.ui.projects.addroom

import android.content.Context
import com.example.rocketplan_android.R
import java.util.Locale

enum class RoomTypeCategory(val displayName: String, val order: Int) {
    RESIDENTIAL("Residential", 0),
    UTILITY("Utility", 1),
    OFFICE("Office", 2),
    RECREATION("Recreation", 3),
    COMMON_AREAS("Common Areas", 4),
    COMMERCIAL("Commercial", 5),
    OUTDOOR_STRUCTURAL("Outdoor & Structural", 6),
    DIRECTIONAL("Directional", 7),
    OTHER("Other", 8)
}

data class RoomTypeOption(
    val id: String,
    val displayName: String,
    val iconName: String,
    val category: RoomTypeCategory,
    val isExterior: Boolean
) {
    fun resolveIconRes(context: Context): Int {
        return RoomTypeCatalog.resolveIconRes(context, typeId = null, iconName = iconName)
    }
}

object RoomTypeCatalog {

    private val displayNameOverrides = mapOf(
        "bathroom_commercial" to "Bathroom (Commercial)",
        "en_suit" to "En Suite",
        "floor_common_area" to "Floor Common Area",
        "north_facing" to "North-Facing",
        "south_facing" to "South-Facing",
        "east_facing" to "East-Facing",
        "west_facing" to "West-Facing",
        "office_den" to "Office / Den",
        "walk_in_wardrobe" to "Walk-in Wardrobe",
        "under_stair_cupboard" to "Under-stair Cupboard"
    )

    private val aliasOverrides = mapOf(
        "ensuite" to "en_suit",
        "en_suite" to "en_suit",
        "barbeque_area" to "lunch_room",
        "bbq_area" to "lunch_room",
        "roof_top" to "balcony",
        "rooftop" to "balcony",
        "mailroom" to "office",
        "mail_room" to "office",
        "entire_unit" to "storefront"
    )

    private val idIconMappings = mapOf(
        1L to "bedroom",
        2L to "bathroom",
        3L to "dining_room",
        4L to "kitchen",
        5L to "laundry",
        6L to "living_room",
        7L to "reading_room",
        8L to "en_suit",
        9L to "hallway",
        10L to "stairway",
        11L to "elevator",
        12L to "lounge_1",
        13L to "closet",
        14L to "gym",
        15L to "boiler_room",
        16L to "entryway",
        17L to "hallway",
        18L to "garage",
        19L to "basement",
        20L to "master_bedroom",
        21L to "basement",
        22L to "den"
    )

    private val iosNameMappings = mapOf(
        "Bedroom" to "bedroom",
        "Bathroom" to "bathroom",
        "Dining Room" to "dining_room",
        "Kitchen" to "kitchen",
        "Laundry" to "laundry",
        "Living Room" to "living_room",
        "Den" to "reading_room",
        "Ensuite" to "en_suit",
        "Hallway" to "hallway",
        "Stairway" to "stairway",
        "Elevator" to "elevator",
        "Lobby" to "lounge_1",
        "Storage" to "closet",
        "Closet" to "closet",
        "Gym" to "gym",
        "Electrical Room" to "boiler_room",
        "Reception" to "lounge_1",
        "Office" to "study_room",
        "Meeting Room" to "meeting_room",
        "Private Office" to "private_office",
        "Maintenance Room" to "boiler_room",
        "Lunch Room" to "lunch_room",
        "Balcony" to "balcony",
        "Deck" to "lounge_2",
        "East Facing" to "east_facing",
        "Garden" to "walkway",
        "North Facing" to "north_facing",
        "Roof" to "attic",
        "Shed" to "garage",
        "South Facing" to "south_facing",
        "Terrace" to "balcony",
        "Walkway" to "walkway",
        "West Facing" to "west_facing",
        "Barbeque Area" to "lunch_room",
        "Courtyard" to "plaza",
        "Patio" to "balcony",
        "Plaza" to "plaza",
        "Rooftop" to "balcony",
        "Entryway" to "entryway",
        "Garage" to "garage",
        "Master Bedroom" to "bedroom",
        "Basement" to "basement",
        "Bay" to "bay",
        "Mechanical Room" to "mechanical_room",
        "Parking Garage" to "parking_garage",
        "Storefront" to "storefront",
        "Entire Unit" to "storefront",
        "Utility Room" to "utility_room",
        "Pool" to "pool",
        "Machine Room" to "boiler_room",
        "Shop" to "storefront",
        "Leisure Room" to "living_room",
        "Mailroom" to "office",
        "Mail room" to "office",
        "Multi Purpose Room" to "meeting_room"
    )

    private val options = listOf(
        // Residential
        option("living_room", RoomTypeCategory.RESIDENTIAL),
        option("bedroom", RoomTypeCategory.RESIDENTIAL),
        option("master_bedroom", RoomTypeCategory.RESIDENTIAL),
        option("kitchen", RoomTypeCategory.RESIDENTIAL),
        option("dining_room", RoomTypeCategory.RESIDENTIAL),
        option("bathroom", RoomTypeCategory.RESIDENTIAL),
        option("en_suit", RoomTypeCategory.RESIDENTIAL),
        option("closet", RoomTypeCategory.RESIDENTIAL),
        option("laundry", RoomTypeCategory.RESIDENTIAL),

        // Utility
        option("garage", RoomTypeCategory.UTILITY),
        option("attic", RoomTypeCategory.UTILITY),
        option("basement", RoomTypeCategory.UTILITY),
        option("utility_room", RoomTypeCategory.UTILITY),
        option("boiler_room", RoomTypeCategory.UTILITY),
        option("mechanical_room", RoomTypeCategory.UTILITY),

        // Office
        option("office", RoomTypeCategory.OFFICE),
        option("office_den", RoomTypeCategory.OFFICE, iconName = "office_den"),
        option("den", RoomTypeCategory.OFFICE),
        option("study_room", RoomTypeCategory.OFFICE),
        option("library", RoomTypeCategory.OFFICE),
        option("reading_room", RoomTypeCategory.OFFICE),
        option("private_office", RoomTypeCategory.OFFICE),
        option("meeting_room", RoomTypeCategory.OFFICE),

        // Recreation
        option("gym", RoomTypeCategory.RECREATION),
        option("pool", RoomTypeCategory.RECREATION),
        option("games_room", RoomTypeCategory.RECREATION),
        option("play_room", RoomTypeCategory.RECREATION),
        option("cinema", RoomTypeCategory.RECREATION),

        // Common areas
        option("entryway", RoomTypeCategory.COMMON_AREAS),
        option("hallway", RoomTypeCategory.COMMON_AREAS),
        option("stairway", RoomTypeCategory.COMMON_AREAS, iconName = "stairwell"),
        option("elevator", RoomTypeCategory.COMMON_AREAS),
        option("floor_common_area", RoomTypeCategory.COMMON_AREAS),
        option("lounge_1", RoomTypeCategory.COMMON_AREAS),
        option("lounge_2", RoomTypeCategory.COMMON_AREAS),
        option("lobby", RoomTypeCategory.COMMON_AREAS),

        // Commercial
        option("commercial", RoomTypeCategory.COMMERCIAL, iconName = "commercial"),
        option("bathroom_commercial", RoomTypeCategory.COMMERCIAL, iconName = "bathroom"),
        option("storefront", RoomTypeCategory.COMMERCIAL),
        option("lunch_room", RoomTypeCategory.COMMERCIAL),

        // Outdoor / structural
        option("balcony", RoomTypeCategory.OUTDOOR_STRUCTURAL, isExterior = true),
        option("bay", RoomTypeCategory.OUTDOOR_STRUCTURAL, isExterior = true),
        option("plaza", RoomTypeCategory.OUTDOOR_STRUCTURAL, isExterior = true),
        option("walkway", RoomTypeCategory.OUTDOOR_STRUCTURAL, isExterior = true),
        option("parking_garage", RoomTypeCategory.OUTDOOR_STRUCTURAL, iconName = "parking_garage", isExterior = true),

        // Directional / exterior references
        option("north_facing", RoomTypeCategory.DIRECTIONAL, isExterior = true),
        option("south_facing", RoomTypeCategory.DIRECTIONAL, isExterior = true),
        option("east_facing", RoomTypeCategory.DIRECTIONAL, isExterior = true),
        option("west_facing", RoomTypeCategory.DIRECTIONAL, isExterior = true),

        // Other / misc
        option("custom", RoomTypeCategory.OTHER),
        option("bike_locker", RoomTypeCategory.OTHER),
        option("power_room", RoomTypeCategory.OTHER),
        option("prep_kitchen", RoomTypeCategory.OTHER),
        option("riser_cupboard", RoomTypeCategory.OTHER),
        option("under_stair_cupboard", RoomTypeCategory.OTHER),
        option("walk_in_wardrobe", RoomTypeCategory.OTHER),
        option("wet_room", RoomTypeCategory.OTHER)
    )

    private val optionsById = options.associateBy { it.id }
    private val aliasMap: Map<String, String> = buildMap {
        displayNameOverrides.forEach { (slug, display) ->
            put(slugify(display), slug)
        }
        aliasOverrides.forEach { (alias, slug) ->
            put(slugify(alias), slug)
        }
        iosNameMappings.forEach { (name, slug) ->
            put(slugify(name), slug)
        }
    }

    fun metadataForName(name: String?): RoomTypeOption? {
        if (name.isNullOrBlank()) return null
        val slug = slugify(name)
        return optionsById[slug] ?: aliasMap[slug]?.let { optionsById[it] }
    }

    fun resolveIconRes(context: Context, iconName: String?): Int =
        resolveIconRes(context, typeId = null, iconName = iconName)

    fun resolveIconRes(context: Context, typeId: Long?, iconName: String?): Int {
        val candidate = resolveIconName(typeId, iconName)
        val resolvedId = candidate?.let { context.resources.getIdentifier(it, "drawable", context.packageName) } ?: 0
        return if (resolvedId != 0) resolvedId else R.drawable.ic_door
    }

    private fun resolveIconName(typeId: Long?, iconName: String?): String? {
        idIconMappings[typeId]?.let { return it }
        val metadata = metadataForName(iconName)
        if (metadata != null) return metadata.iconName
        return iconName?.let { slugify(it) }?.takeIf { it.isNotBlank() }
    }

    fun isExteriorType(type: String?): Boolean {
        val normalized = type?.lowercase(Locale.US) ?: return false
        return normalized == "external" || normalized == "multi-external" || normalized == "single-external"
    }

    private fun option(
        id: String,
        category: RoomTypeCategory,
        iconName: String = id,
        isExterior: Boolean = false,
        displayNameOverride: String? = null
    ): RoomTypeOption {
        val displayName = displayNameOverride ?: displayNameOverrides[id] ?: formatDisplayName(id)
        return RoomTypeOption(
            id = id,
            displayName = displayName,
            iconName = iconName,
            category = category,
            isExterior = isExterior
        )
    }

    private fun slugify(value: String): String =
        value.lowercase(Locale.US)
            .replace("&", "and")
            .replace("[^a-z0-9]+".toRegex(), "_")
            .trim('_')

    private fun formatDisplayName(id: String): String = id.split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part ->
            part.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
            }
        }
}
