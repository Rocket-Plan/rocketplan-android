package com.example.rocketplan_android.util

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class InviteLinkTest {

    @Test
    fun `parse https invite with company uuid only`() {
        val uri = Uri.parse("https://app.rocketplan.com/invite-redirect/abc123")

        val result = InviteLink.parse(uri)

        assertThat(result).isNotNull()
        assertThat(result!!.companyUuid).isEqualTo("abc123")
        assertThat(result.invitationUuid).isNull()
    }

    @Test
    fun `parse https invite with company uuid and invitation uuid`() {
        val uri = Uri.parse("https://app.rocketplan.com/invite-redirect/abc123/invitation-uuid")

        val result = InviteLink.parse(uri)

        assertThat(result).isNotNull()
        assertThat(result!!.companyUuid).isEqualTo("abc123")
        assertThat(result.invitationUuid).isEqualTo("invitation-uuid")
    }

    @Test
    fun `parse returns null for empty path segments`() {
        val uri = Uri.parse("invite-redirect://")

        val result = InviteLink.parse(uri)

        assertThat(result).isNull()
    }

    @Test
    fun `parse returns null for blank company uuid`() {
        val uri = Uri.parse("https://app.rocketplan.com/invite-redirect/  ")

        val result = InviteLink.parse(uri)

        assertThat(result).isNull()
    }

    @Test
    fun `parse returns null when invite-redirect not in path`() {
        val uri = Uri.parse("https://app.rocketplan.com/other-path/abc123")

        val result = InviteLink.parse(uri)

        assertThat(result).isNull()
    }

    @Test
    fun `parse returns null when host is invite-redirect but no path segments`() {
        val uri = Uri.parse("invite-redirect://")

        val result = InviteLink.parse(uri)

        assertThat(result).isNull()
    }

    @Test
    fun `parseOrThrow throws IllegalArgumentException for invalid uri`() {
        val uri = Uri.parse("https://app.rocketplan.com/other-path/abc123")

        val exception = runCatching { InviteLink.parseOrThrow(uri) }.exceptionOrNull()

        assertThat(exception).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(exception!!.message).contains("Invalid invite link")
    }

    @Test
    fun `parseOrThrow returns invite link for valid uri`() {
        val uri = Uri.parse("https://app.rocketplan.com/invite-redirect/abc123")

        val result = InviteLink.parseOrThrow(uri)

        assertThat(result.companyUuid).isEqualTo("abc123")
        assertThat(result.invitationUuid).isNull()
    }
}