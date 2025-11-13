package com.example.rocketplan_android.ui.projects.addroom

import androidx.annotation.StringRes
import com.example.rocketplan_android.R

enum class RoomTypePickerMode(@StringRes val titleRes: Int) {
    ROOM(R.string.select_room_type_title),
    EXTERIOR(R.string.select_exterior_type_title);

    companion object {
        fun fromName(value: String?): RoomTypePickerMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ROOM
    }
}
