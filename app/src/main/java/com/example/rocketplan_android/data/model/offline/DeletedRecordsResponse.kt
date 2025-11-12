package com.example.rocketplan_android.data.model.offline

import com.google.gson.annotations.SerializedName

data class DeletedRecordsResponse(
    val projects: List<Long> = emptyList(),
    val photos: List<Long> = emptyList(),
    val notes: List<Long> = emptyList(),
    val rooms: List<Long> = emptyList(),
    val locations: List<Long> = emptyList(),
    val equipment: List<Long> = emptyList(),
    @SerializedName("damage_materials")
    val damageMaterials: List<Long> = emptyList(),
    @SerializedName("atmospheric_logs")
    val atmosphericLogs: List<Long> = emptyList(),
    @SerializedName("work_scope_actions")
    val workScopeActions: List<Long> = emptyList()
)
