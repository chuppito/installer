package com.tomtom.installer

import org.junit.Assert.*
import org.junit.Test

class SplitInstallSessionTest {
    @Test fun writesAllComponentsBeforeCommittingOneSession() {
        val calls = mutableListOf<Pair<List<String>, Int?>>()
        val install = SplitInstallSession { args, index ->
            calls.add(args to index)
            if (args[0] == "install-create") "Success: created install session [42]" else "Success"
        }
        assertEquals("Success", install.install(longArrayOf(10, 20, 30), 10, true))
        assertEquals(listOf("install-create", "install-write", "install-write", "install-write", "install-commit"), calls.map { it.first[0] })
        assertTrue(calls[0].first.containsAll(listOf("com.android.vending", "60", "--package-source")))
        assertEquals(listOf(0, 1, 2), calls.subList(1, 4).map { it.second })
        assertTrue(calls.drop(1).all { it.first.contains("42") })
    }

    @Test fun abandonsFailedWritesAndCommitsWithoutMaskingTheError() {
        for (failure in listOf("install-write", "install-commit")) {
            val calls = mutableListOf<String>()
            val install = SplitInstallSession { args, _ ->
                calls.add(args[0])
                if (args[0] == failure) error("Original failure")
                if (args[0] == "install-abandon") error("Cleanup failure")
                "Success: created install session [42]"
            }
            try { install.install(longArrayOf(10, 20), 0, false); fail("Failure ignored") }
            catch (e: IllegalStateException) { assertEquals("Original failure", e.message) }
            assertEquals("install-abandon", calls.last())
            if (failure == "install-write") assertFalse(calls.contains("install-commit"))
        }
    }

    @Test fun rejectsInvalidComponentsBeforeCreatingASession() {
        val install = SplitInstallSession { _, _ -> fail("Unexpected command"); "" }
        for (sizes in listOf(longArrayOf(), longArrayOf(0), longArrayOf(-1), LongArray(257) { 1 })) {
            try { install.install(sizes, 0, false); fail("Invalid components accepted") }
            catch (_: IllegalArgumentException) {}
        }
    }
}
