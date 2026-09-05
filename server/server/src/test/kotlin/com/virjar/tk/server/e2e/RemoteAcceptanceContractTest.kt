package com.virjar.tk.server.e2e

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteAcceptanceContractTest {
    @Test
    fun `every remote acceptance case has a JUnit compatible void signature`() {
        val acceptanceCases = RemoteAcceptanceTest::class.java.declaredMethods
            .filter { method -> method.isAnnotationPresent(Test::class.java) }
        assertTrue(acceptanceCases.isNotEmpty(), "Remote acceptance must retain executable cases")

        val invalidCases = acceptanceCases
            .filterNot { method -> method.returnType == java.lang.Void.TYPE }
            .map { method -> "${method.name}: ${method.returnType.typeName}" }
            .sorted()
        assertEquals(emptyList(), invalidCases)
    }
}
