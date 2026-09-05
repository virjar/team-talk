package com.virjar.tk.server.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PostgresSchemaLeaseTest {
    @Test
    fun `schema names are lowercase safe identifiers`() {
        val schemaName = newTestSchemaName("A1B2C3D4")

        assertEquals("tt_test_a1b2c3d4", schemaName)
        assertTrue(Regex("[a-z][a-z0-9_]+").matches(schemaName))
    }

    @Test
    fun `jdbc url adds schema without dropping existing parameters`() {
        assertEquals(
            "jdbc:postgresql://db:5432/teamtalk?currentSchema=tt_test_12345678",
            jdbcUrlWithCurrentSchema(
                "jdbc:postgresql://db:5432/teamtalk",
                "tt_test_12345678",
            ),
        )
        assertEquals(
            "jdbc:postgresql://db:5432/teamtalk?sslmode=require&currentSchema=tt_test_12345678",
            jdbcUrlWithCurrentSchema(
                "jdbc:postgresql://db:5432/teamtalk?sslmode=require",
                "tt_test_12345678",
            ),
        )
    }

    @Test
    fun `jdbc url replaces caller supplied current schema`() {
        assertEquals(
            "jdbc:postgresql://db/teamtalk?connectTimeout=5&currentSchema=tt_test_abcdefgh",
            jdbcUrlWithCurrentSchema(
                "jdbc:postgresql://db/teamtalk?currentSchema=public&connectTimeout=5",
                "tt_test_abcdefgh",
            ),
        )
    }

    @Test
    fun `unsafe identifiers are rejected before SQL construction`() {
        assertFailsWith<IllegalArgumentException> {
            jdbcUrlWithCurrentSchema("jdbc:postgresql://db/teamtalk", "public;drop schema public")
        }
        assertFailsWith<IllegalArgumentException> { newTestSchemaName("../public") }
    }
}
