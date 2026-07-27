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
    fun `materials config (no isDirty) always adopts local identity on serverId match (RP-BUG-038)`() {
        // Materials have no isDirty column, so the pull passes isDirty = { false }: every existing
        // serverId match must adopt the local PK + uuid (update in place), never duplicate.
        val local = Row(pk = -700L, serverId = 900L, uuid = "client-uuid", content = "old")
        val server = Row(pk = 900L, serverId = 900L, uuid = "server-uuid", content = "new")

        val merged = mergePulledRowsByServerId(
            incoming = listOf(server),
            existingByServerId = mapOf(900L to local),
            serverIdOf = { it.serverId },
            isDirty = { false },
            adoptLocalIdentity = { s, l -> s.copy(pk = l.pk, uuid = l.uuid) },
        )

        assertThat(merged).hasSize(1)
        assertThat(merged[0].pk).isEqualTo(-700L)
        assertThat(merged[0].uuid).isEqualTo("client-uuid")
        assertThat(merged[0].content).isEqualTo("new")
    }

    @Test
    fun `server row with no local match is inserted as-is`() {
        val server = Row(pk = 501L, serverId = 501L, uuid = "server-uuid", content = "new")

        val merged = merge(incoming = listOf(server), existing = emptyList())

        assertThat(merged).containsExactly(server)
    }

    @Test
    fun `migrated row with null serverId matches by uuid and adopts local identity`() {
        val local = Row(pk = -100L, serverId = null, uuid = "migrated-uuid", content = "old")
        val server = Row(pk = 0L, serverId = null, uuid = "migrated-uuid", content = "new")

        val merged = mergePulledRowsByServerId(
            incoming = listOf(server),
            existingByServerId = emptyMap(),
            serverIdOf = { it.serverId },
            isDirty = { it.isDirty },
            adoptLocalIdentity = { s, l -> s.copy(pk = l.pk, uuid = l.uuid) },
            existingByUuid = mapOf("migrated-uuid" to local),
            uuidOf = { it.uuid },
        )

        assertThat(merged).hasSize(1)
        assertThat(merged[0].pk).isEqualTo(-100L)
        assertThat(merged[0].uuid).isEqualTo("migrated-uuid")
        assertThat(merged[0].content).isEqualTo("new")
    }

    @Test
    fun `mixed batch serverId match and uuid-only match reconcile correctly`() {
        val localByServerId = Row(pk = -200L, serverId = 500L, uuid = "local-uuid", content = "by-server")
        val localByUuid = Row(pk = -100L, serverId = null, uuid = "migrated-uuid", content = "by-uuid")
        val incomingByServerId = Row(pk = 500L, serverId = 500L, uuid = "server-uuid", content = "new-server")
        val incomingByUuid = Row(pk = 0L, serverId = null, uuid = "migrated-uuid", content = "new-migrated")

        val merged = mergePulledRowsByServerId(
            incoming = listOf(incomingByServerId, incomingByUuid),
            existingByServerId = mapOf(500L to localByServerId),
            serverIdOf = { it.serverId },
            isDirty = { it.isDirty },
            adoptLocalIdentity = { s, l -> s.copy(pk = l.pk, uuid = l.uuid) },
            existingByUuid = mapOf("migrated-uuid" to localByUuid),
            uuidOf = { it.uuid },
        )

        assertThat(merged).hasSize(2)
        val byServerId = merged.find { it.serverId == 500L }!!
        val byUuid = merged.find { it.uuid == "migrated-uuid" }!!
        assertThat(byServerId.pk).isEqualTo(-200L)
        assertThat(byServerId.content).isEqualTo("new-server")
        assertThat(byUuid.pk).isEqualTo(-100L)
        assertThat(byUuid.content).isEqualTo("new-migrated")
    }

    @Test
    fun `uuid match on migrated row preserves dirty local row and grafts server identity`() {
        val local = Row(pk = -100L, serverId = null, uuid = "migrated-uuid", content = "local-edit", isDirty = true)
        val server = Row(pk = 0L, serverId = 7001L, uuid = "migrated-uuid", content = "server")

        val merged = mergePulledRowsByServerId(
            incoming = listOf(server),
            existingByServerId = emptyMap(),
            serverIdOf = { it.serverId },
            isDirty = { it.isDirty },
            adoptLocalIdentity = { s, l -> s.copy(pk = l.pk, uuid = l.uuid) },
            existingByUuid = mapOf("migrated-uuid" to local),
            uuidOf = { it.uuid },
            adoptServerIdentity = { s, l -> l.copy(serverId = s.serverId) },
        )

        assertThat(merged).hasSize(1)
        val result = merged[0]
        assertThat(result.pk).isEqualTo(-100L)
        assertThat(result.uuid).isEqualTo("migrated-uuid")
        assertThat(result.content).isEqualTo("local-edit")
        assertThat(result.serverId).isEqualTo(7001L)
        assertThat(result.isDirty).isTrue()
    }
}
