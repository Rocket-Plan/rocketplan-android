package com.example.rocketplan_android.data.local.model

data class RoomPhotoSummary(
    val roomId: Long,
    val photoCount: Int,
    val latestThumbnailUrl: String?
)
