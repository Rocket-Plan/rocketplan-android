package com.example.rocketplan_android.data.model.offline

import com.example.rocketplan_android.data.model.SingleDataResponse
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Test

/**
 * RP-FR-019 — golden-fixture deserialization tests for the serialized equipment
 * DTOs. Fixtures under test/resources/fixtures/equipment_assets/ are derived from
 * the mongoose EquipmentAssetResource / EquipmentAssetPlacementResource shapes
 * and tests/Schemas/equipment-asset*.json (API Contract Discipline).
 */
class EquipmentAssetDtoParseTest {

    private val gson = Gson()

    private fun fixture(name: String): String =
        javaClass.classLoader!!
            .getResourceAsStream("fixtures/equipment_assets/$name")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `asset show parses with nested current placement and placements`() {
        val resp = gson.fromJson(fixture("asset_show.json"), EquipmentAssetResponse::class.java)
        val asset = resp.data
        assertThat(asset.id).isEqualTo(3)
        assertThat(asset.uuid).isEqualTo("9d7f2c1a-3b44-4e2a-9c11-000000000003")
        assertThat(asset.companyId).isEqualTo(7)
        assertThat(asset.catalogUuid).isEqualTo("c1a11111-1111-4111-8111-111111111111")
        assertThat(asset.status).isEqualTo("deployed")
        // Decimals arrive as strings, not numbers.
        assertThat(asset.purchasePrice).isEqualTo("1899.00")
        assertThat(asset.rentalDayRate).isEqualTo("35.50")
        assertThat(asset.currentPlacementId).isEqualTo(12)
        assertThat(asset.currentPlacement?.isOpen).isTrue()
        assertThat(asset.placements).hasSize(1)
        assertThat(resp.idempotent).isNull()
    }

    @Test
    fun `register replay carries idempotent flag and null optionals`() {
        val resp = gson.fromJson(fixture("asset_register_replay.json"), EquipmentAssetResponse::class.java)
        assertThat(resp.idempotent).isTrue()
        assertThat(resp.data.status).isEqualTo("available")
        assertThat(resp.data.manufacturer).isNull()
        assertThat(resp.data.purchasePrice).isNull()
        assertThat(resp.data.currentPlacementId).isNull()
    }

    @Test
    fun `company assets page parses data and pagination meta`() {
        val resp = gson.fromJson(fixture("company_assets_page.json"), EquipmentAssetPageResponse::class.java)
        assertThat(resp.data).hasSize(2)
        assertThat(resp.meta?.total).isEqualTo(2)
        assertThat(resp.data[1].name).isEqualTo("Dehumidifier")
    }

    @Test
    fun `check-in placement carries idempotency flag`() {
        val resp = gson.fromJson(fixture("placement_checkin.json"), EquipmentAssetPlacementResponse::class.java)
        // check-in uses `idempotency` (not `idempotent`).
        assertThat(resp.idempotency).isTrue()
        assertThat(resp.data.equipmentAssetId).isEqualTo(3)
        assertThat(resp.data.roomId).isEqualTo(6)
        assertThat(resp.data.isOpen).isTrue()
        assertThat(resp.data.dateOut).isNull()
    }

    @Test
    fun `placements list parses hand-wrapped data array`() {
        val type = TypeToken.getParameterized(
            SingleDataResponse::class.java,
            TypeToken.getParameterized(List::class.java, EquipmentAssetPlacementDto::class.java).type
        ).type
        val resp: SingleDataResponse<List<EquipmentAssetPlacementDto>> = gson.fromJson(fixture("placements_list.json"), type)
        assertThat(resp.data).hasSize(2)
        assertThat(resp.data[0].isOpen).isTrue()
        assertThat(resp.data[1].isOpen).isFalse()
        assertThat(resp.data[1].dateOut).isNotNull()
    }

    @Test
    fun `timeline parses project bars with meta but no links`() {
        val resp = gson.fromJson(fixture("timeline.json"), EquipmentAssetTimelineResponse::class.java)
        assertThat(resp.data).hasSize(1)
        val entry = resp.data[0]
        assertThat(entry.project?.uid).isEqualTo("RP-25-1184")
        assertThat(entry.bars).hasSize(1)
        assertThat(entry.bars!![0].asset?.serialNumber).isEqualTo("AM-0001")
        assertThat(entry.bars!![0].room?.name).isEqualTo("Basement")
        assertThat(resp.meta?.total).isEqualTo(1)
    }

    @Test
    fun `room assets parses hand-wrapped asset array`() {
        val type = TypeToken.getParameterized(
            SingleDataResponse::class.java,
            TypeToken.getParameterized(List::class.java, EquipmentAssetDto::class.java).type
        ).type
        val resp: SingleDataResponse<List<EquipmentAssetDto>> = gson.fromJson(fixture("room_assets.json"), type)
        assertThat(resp.data).hasSize(1)
        assertThat(resp.data[0].status).isEqualTo("deployed")
    }
}
