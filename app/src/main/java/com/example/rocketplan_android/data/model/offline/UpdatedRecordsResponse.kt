package com.example.rocketplan_android.data.model.offline

import com.google.gson.annotations.SerializedName

/**
 * Response from /api/sync/updated endpoint.
 * Returns IDs and updated_at timestamps for records modified since a given time.
 */
data class UpdatedRecordsResponse(
    val projects: List<UpdatedRecord> = emptyList(),
    val properties: List<UpdatedRecord> = emptyList(),
    val photos: List<UpdatedRecord> = emptyList(),
    val notes: List<UpdatedRecord> = emptyList(),
    val rooms: List<UpdatedRecord> = emptyList(),
    val locations: List<UpdatedRecord> = emptyList(),
    val equipment: List<UpdatedRecord> = emptyList(),
    @SerializedName("damage_materials")
    val damageMaterials: List<UpdatedRecord> = emptyList(),
    @SerializedName("damage_material_room_logs")
    val damageMaterialRoomLogs: List<UpdatedRecord> = emptyList(),
    @SerializedName("atmospheric_logs")
    val atmosphericLogs: List<UpdatedRecord> = emptyList(),
    @SerializedName("work_scope_actions")
    val workScopeActions: List<UpdatedRecord> = emptyList(),
    val claims: List<UpdatedRecord> = emptyList(),
    val timecards: List<UpdatedRecord> = emptyList(),
    @SerializedName("support_conversations")
    val supportConversations: List<UpdatedRecord> = emptyList(),
    @SerializedName("support_messages")
    val supportMessages: List<UpdatedRecord> = emptyList()
) {
    /**
     * Returns true if any record type has updates.
     */
    fun hasAnyUpdates(): Boolean =
        projects.isNotEmpty() ||
            properties.isNotEmpty() ||
            photos.isNotEmpty() ||
            notes.isNotEmpty() ||
            rooms.isNotEmpty() ||
            locations.isNotEmpty() ||
            equipment.isNotEmpty() ||
            damageMaterials.isNotEmpty() ||
            damageMaterialRoomLogs.isNotEmpty() ||
            atmosphericLogs.isNotEmpty() ||
            workScopeActions.isNotEmpty() ||
            claims.isNotEmpty() ||
            timecards.isNotEmpty() ||
            supportConversations.isNotEmpty() ||
            supportMessages.isNotEmpty()
}

data class UpdatedRecord(
    val id: Long,
    @SerializedName("updated_at")
    val updatedAt: String
)
