package com.example.rocketplan_android.ui.projects

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProjectRoomsAdapterTest {

    @Test
    fun `getItemViewType returns default when position is out of bounds`() {
        val adapter = ProjectRoomsAdapter(onRoomClick = {})

        val safeType = adapter.getItemViewType(10)

        assertThat(safeType).isEqualTo(ProjectRoomsAdapter.VIEW_TYPE_ROOM)
    }
}
