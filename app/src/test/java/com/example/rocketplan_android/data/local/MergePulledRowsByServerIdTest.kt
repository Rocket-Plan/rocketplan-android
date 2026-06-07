package com.example.rocketplan_android.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RP-BUG-037 (+ RP-FR-003): unit tests for the pull-merge policy used by saveNotes / saveEquipment /
 * saveMoistureLogs / saveAtmosphericLogs. Pure function, no Room — exercises the three branches.
 */
class MergePulledRowsByServerIdTest {

    private data class Row(
        val pk: Long,
        val serverId: Long?,
        val uuid: String,
        val content: String,
        val isDirty: Boolean = false,
    )

    private fun merge(incoming: List<Row>, existing: List<Row>): List<Row> =
        mergePulledRowsByServerId(
            incoming = incoming,
            existingByServerId = existing.associateBy { it.serverId },
            serverIdOf = { it.serverId },
            isDirty = { it.isDirty },
            adoptLocalIdentity = { server, local -> server.copy(pk = local.pk, uuid = local.uuid) },
        )

    @Test
    fun `clean local row is updated in place keeping local PK and uuid (RP-BUG-037)`() {
        // Local offline-created row, pushed → serverId 500, local PK -100, local uuid, clean.
        val local = Row(pk = -100L, serverId = 500L, uuid = "local-uuid", content = "old", isDirty = false)
        // Pulled server row: server-minted uuid, mapper set PK = server id, newer content.
        val server = Row(pk = 500L, serverId = 500L, uuid = "server-uuid", content = "new")

        val merged = merge(incoming = listOf(server), existing = listOf(local))

        assertThat(merged).hasSize(1)
        // Adopts the LOCAL identity (PK + uuid) so the upsert updates in place — no duplicate.
        assertThat(merged[0].pk).isEqualTo(-100L)
        assertThat(merged[0].uuid).isEqualTo("local-uuid")
        // ...while taking the server's content.
        assertThat(merged[0].content).isEqualTo("new")
    }

    @Test
    fun `dirty local row is preserved over server version (RP-FR-003)`() {
        val local = Row(pk = -100L, serverId = 500L, uuid = "local-uuid", content = "local-edit", isDirty = true)
        val server = Row(pk = 500L, serverId = 500L, uuid = "server-uuid", content = "server")
        var preservedServerId: Long? = null

        val merged = mergePulledRowsByServerId(
            incoming = listOf(server),
            existingByServerId = mapOf(500L to local),
            serverIdOf = { it.serverId },
            isDirty = { it.isDirty },
            onPreserveDirty = { preservedServerId = it },
            adoptLocalIdentity = { s, l -> s.copy(pk = l.pk, uuid = l.uuid) },
        )

        assertThat(merged).containsExactly(local)
        assertThat(preservedServerId).isEqualTo(500L)
    }

    @Test
    fun `server row with no local match is inserted as-is`() {
        val server = Row(pk = 501L, serverId = 501L, uuid = "server-uuid", content = "new")

        val merged = merge(incoming = listOf(server), existing = emptyList())

        assertThat(merged).containsExactly(server)
    }
}
