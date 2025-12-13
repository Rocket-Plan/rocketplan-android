package com.example.rocketplan_android.util

private val TARGET_MOISTURE_REGEX = Regex("([0-9]+(?:\\.[0-9]+)?)")

fun parseTargetMoisture(description: String?): Double? {
    if (description.isNullOrBlank()) return null
    val match = TARGET_MOISTURE_REGEX.find(description)
    return match?.value?.toDoubleOrNull()
}
