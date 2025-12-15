package com.example.rocketplan_android.data.model

/**
 * Centralized list of category albums that should not be treated as user-created albums.
 * These are provided by the backend for classification (e.g., batch capture categories).
 */
object CategoryAlbums {
    private val orderedNames = listOf(
        "Damage Assessment",
        "Daily Progress",
        "Pre-existing Damages",
        "Betterments",
        "Contents"
    )

    val names: Set<String> = orderedNames.toSet()

    fun isCategory(name: String?): Boolean {
        val normalized = name?.trim()
        return normalized != null && names.contains(normalized)
    }

    fun orderIndex(name: String?): Int {
        val normalized = name?.trim()
        val idx = orderedNames.indexOf(normalized)
        return if (idx >= 0) idx else Int.MAX_VALUE
    }
}
