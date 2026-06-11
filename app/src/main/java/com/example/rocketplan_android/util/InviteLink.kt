package com.example.rocketplan_android.util

import android.net.Uri

data class InviteLink(
    val companyUuid: String,
    val invitationUuid: String? = null
) {
    companion object {
        private const val INVITE_HOST = "invite-redirect"

        fun parse(uri: Uri): InviteLink? {
            val pathSegments = uri.pathSegments

            if (pathSegments.isEmpty()) {
                return null
            }

            val companyUuid: String
            val invitationUuid: String?

            if (uri.host == INVITE_HOST) {
                val inviteSegments = pathSegments
                if (inviteSegments.size < 1) return null
                companyUuid = inviteSegments[0]
                invitationUuid = if (inviteSegments.size > 1) inviteSegments[1].takeIf { it.isNotBlank() } else null
            } else {
                val inviteIndex = pathSegments.indexOf(INVITE_HOST)
                if (inviteIndex < 0) return null
                val afterInvite = inviteIndex + 1
                if (afterInvite >= pathSegments.size) return null
                companyUuid = pathSegments[afterInvite]
                if (companyUuid.isBlank()) return null
                invitationUuid = if (afterInvite + 1 < pathSegments.size) {
                    pathSegments[afterInvite + 1].takeIf { it.isNotBlank() }
                } else null
            }

            return InviteLink(companyUuid, invitationUuid)
        }

        fun parseOrThrow(uri: Uri): InviteLink {
            return parse(uri) ?: throw IllegalArgumentException("Invalid invite link: $uri")
        }
    }
}
