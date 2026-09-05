package com.virjar.tk.server.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class OrganizationReconcileResponseTest {
    @Test
    fun `reconcile response serializes heterogeneous fields through an explicit contract`() {
        assertEquals(
            "{\"ok\":false,\"failedUnitIds\":[\"unit-a\",\"unit-b\"]}",
            Json.encodeToString(
                OrganizationReconcileResponse(
                    ok = false,
                    failedUnitIds = listOf("unit-a", "unit-b"),
                ),
            ),
        )
    }
}
