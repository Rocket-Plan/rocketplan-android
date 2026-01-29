package com.example.rocketplan_android.data.model.offline

import com.google.gson.annotations.SerializedName

data class DeletedRecordsResponse(
    val projects: List<Long> = emptyList(),
    val properties: List<Long> = emptyList(),
    val photos: List<Long> = emptyList(),
    val notes: List<Long> = emptyList(),
    val rooms: List<Long> = emptyList(),
    val locations: List<Long> = emptyList(),
    val equipment: List<Long> = emptyList(),
    @SerializedName("damage_materials")
    val damageMaterials: List<Long> = emptyList(),
    @SerializedName("damage_material_room_logs")
    val damageMaterialRoomLogs: List<Long> = emptyList(),
    @SerializedName("atmospheric_logs")
    val atmosphericLogs: List<Long> = emptyList(),
    @SerializedName("work_scope_actions")
    val workScopeActions: List<Long> = emptyList(),
    @SerializedName("moisture_logs")
    val moistureLogs: List<Long> = emptyList(),
    val claims: List<Long> = emptyList(),
    val timecards: List<Long> = emptyList(),
    @SerializedName("support_conversations")
    val supportConversations: List<Long> = emptyList(),
    @SerializedName("support_messages")
    val supportMessages: List<Long> = emptyList()
)
