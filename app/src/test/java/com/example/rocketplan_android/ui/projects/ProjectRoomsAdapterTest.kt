package com.example.rocketplan_android.ui.projects

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProjectRoomsAdapterTest {

    @Test
    fun `getItemViewType returns default when position is out of bounds`() {
        val adapter = ProjectRoomsAdapter(onRoomClick = {})
        adapter.submitList(
            listOf(
                RoomListItem.Header("Level 1"),
                RoomListItem.Room(RoomCard(roomId = 1L, title = "Room", thumbnailUrl = null, iconRes = 0, photoCount = 0, noteCount = 0, scopeTotal = null))
            )
        )

        val safeType = adapter.getItemViewType(10)

        assertThat(safeType).isEqualTo(ProjectRoomsAdapter.VIEW_TYPE_ROOM)
    }
}
