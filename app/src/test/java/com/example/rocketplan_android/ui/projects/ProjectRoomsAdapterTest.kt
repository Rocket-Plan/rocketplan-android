package com.example.rocketplan_android.ui.projects

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProjectRoomsAdapterTest {

    @Test
    fun `getItemViewType returns ROOM as default for empty list`() {
        val adapter = ProjectRoomsAdapter(onRoomClick = {})

        // Out-of-bounds position falls through to default VIEW_TYPE_ROOM
        val safeType = adapter.getItemViewType(10)

        assertThat(safeType).isEqualTo(ProjectRoomsAdapter.VIEW_TYPE_ROOM)
    }

    @Test
    fun `VIEW_TYPE constants are distinct`() {
        assertThat(ProjectRoomsAdapter.VIEW_TYPE_HEADER)
            .isNotEqualTo(ProjectRoomsAdapter.VIEW_TYPE_ROOM)
    }

    @Test
    fun `RoomListItem Header and Room are distinct sealed subtypes`() {
        val header: RoomListItem = RoomListItem.Header("Main Level")
        val room: RoomListItem = RoomListItem.Room(
            RoomCard(
                roomId = 1L,
                title = "Kitchen",
                level = "Main Level",
                photoCount = 3,
                damageCount = 0,
                scopeTotal = 0.0,
                thumbnailUrl = null,
                isLoadingPhotos = false,
                iconRes = 0
            )
        )

        assertThat(header).isInstanceOf(RoomListItem.Header::class.java)
        assertThat(room).isInstanceOf(RoomListItem.Room::class.java)
        assertThat((header as RoomListItem.Header).title).isEqualTo("Main Level")
    }
}
