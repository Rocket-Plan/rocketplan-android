package com.example.rocketplan_android.ui.rocketdry

import com.example.rocketplan_android.R
import java.util.Locale

data class EquipmentTypeMeta(
    val key: String,
    val label: String,
    val iconRes: Int
)

object EquipmentTypeMapper {

    private val canonicalTypes: Map<String, EquipmentTypeMeta> = listOf(
        EquipmentTypeMeta("dehumidifier", "Dehumidifier", R.drawable.dehumidifier),
        EquipmentTypeMeta("air_mover", "Air Mover", R.drawable.air_mover),
        EquipmentTypeMeta("air_scrubber", "Air Scrubber", R.drawable.air_scrubber),
        EquipmentTypeMeta("inject_drier", "Injectidrier", R.drawable.inject_drier),
        EquipmentTypeMeta("drying_mat", "Drying Mat", R.drawable.drying_mat)
    ).associateBy { it.key }

    private val aliases: Map<String, String> = mapOf(
        "dehumidifiers" to "dehumidifier",
        "air_movers" to "air_mover",
        "air_scrubbers" to "air_scrubber",
        "injectidrier" to "inject_drier",
        "inject_dryers" to "inject_drier",
        "drying_mats" to "drying_mat"
    )

    fun allOptions(): List<EquipmentTypeMeta> = canonicalTypes.values.toList()

    fun normalize(type: String?): String =
        type
            ?.trim()
            ?.lowercase(Locale.getDefault())
            ?.replace(" ", "_")
            ?.replace("-", "_")
            ?.replace("__", "_")
            .orEmpty()
            .ifBlank { "equipment" }

    fun metaFor(rawType: String?): EquipmentTypeMeta {
        val normalized = normalize(rawType)
        val canonicalKey = aliases[normalized] ?: normalized
        val canonical = canonicalTypes[canonicalKey]
        if (canonical != null) return canonical

        val label = canonicalKey.replace("_", " ")
            .replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
            }
            .ifBlank { "Equipment" }

        return EquipmentTypeMeta(
            key = canonicalKey,
            label = label,
            iconRes = R.drawable.image_equipment
        )
    }
}
