package com.example.rocketplan_android.data.model.offline

import com.google.gson.annotations.SerializedName

data class RestoreRecordsRequest(
    val type: String,
    val ids: List<Long>
)

data class RestoreRecordsResponse(
    val restored: List<Long> = emptyList(),
    @SerializedName("already_restored")
    val alreadyRestored: List<Long> = emptyList(),
    @SerializedName("not_found")
    val notFound: List<Long> = emptyList(),
    val unauthorized: List<Long> = emptyList()
)
