package com.example.rocketplan_android.ui.projects

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RP-FR-034 — the project-search match predicate (iOS parity: address / name / RP number).
 * [projectMatchesQuery] expects an already trimmed+lowercased query.
 */
class ProjectSearchTest {

    private fun item(title: String, code: String, alias: String? = null) =
        ProjectListItem(projectId = 1, title = title, projectCode = code, alias = alias, status = "wip")

    @Test
    fun matchesByAddress() {
        assertTrue(projectMatchesQuery(item("1066 West Hastings", "RP-26-1239"), "hastings"))
    }

    @Test
    fun matchesByRpNumber() {
        assertTrue(projectMatchesQuery(item("1066 West Hastings", "RP-26-1239"), "1239"))
    }

    @Test
    fun matchesByAliasName() {
        assertTrue(projectMatchesQuery(item("addr", "RP-26-0001", alias = "Stryker Job"), "stryker"))
    }

    @Test
    fun isCaseInsensitive_viaLowercasedQuery() {
        // Caller lowercases the query; the item side is lowercased in the predicate.
        assertTrue(projectMatchesQuery(item("1066 WEST HASTINGS", "RP-26-1239"), "west"))
    }

    @Test
    fun noMatchReturnsFalse() {
        assertFalse(projectMatchesQuery(item("1066 West Hastings", "RP-26-1239", alias = "Stryker"), "zzzz"))
    }

    @Test
    fun nullAliasDoesNotMatch() {
        assertFalse(projectMatchesQuery(item("addr", "RP-26-0001", alias = null), "stryker"))
    }
}
