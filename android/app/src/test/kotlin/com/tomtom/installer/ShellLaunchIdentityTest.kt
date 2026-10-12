package com.tomtom.installer

import org.junit.Assert.*
import org.junit.Test

class ShellLaunchIdentityTest {
    private class FakeCredentials(var identity: Int = 0) : ShellLaunchIdentity.Credentials {
        val calls = mutableListOf<String>()
        var failGroups = false
        var ignoreUidChange = false
        override fun uid(): Int = identity
        override fun clearGroups() { calls.add("groups"); if (failGroups) error("Groups denied") }
        override fun gid(value: Int) { calls.add("gid=$value") }
        override fun uid(value: Int) { calls.add("uid=$value"); if (!ignoreUidChange) identity = value }
    }

    @Test fun rootDropsGroupsBeforeGidAndUid() {
        val credentials = FakeCredentials()
        ShellLaunchIdentity.prepare(credentials)
        assertEquals(listOf("groups", "gid=2000", "uid=2000"), credentials.calls)
        assertEquals(2000, credentials.identity)
    }
    @Test fun failedGroupCleanupDoesNotContinueChangingCredentials() {
        val credentials = FakeCredentials().apply { failGroups = true }
        try { ShellLaunchIdentity.prepare(credentials); fail("Failure ignored") }
        catch (e: IllegalStateException) { assertEquals("Groups denied", e.message) }
        assertEquals(listOf("groups"), credentials.calls)
        assertEquals(0, credentials.identity)
    }
    @Test fun refusesToLaunchWhenUidChangeDidNotTakeEffect() {
        val credentials = FakeCredentials().apply { ignoreUidChange = true }
        try { ShellLaunchIdentity.prepare(credentials); fail("Root identity accepted") }
        catch (e: IllegalStateException) { assertEquals("Impossible d’appliquer l’identité Shell", e.message) }
    }
    @Test fun existingShellIdentityNeedsNoCredentialChanges() {
        val credentials = FakeCredentials(2000)
        ShellLaunchIdentity.prepare(credentials)
        assertTrue(credentials.calls.isEmpty())
    }

    @Test fun rejectsBinderThreadEvenWhenItsLocalUidIsShell() {
        try {
            ShellLaunchIdentity.verifyLeader("Uid:\t2000\t2000\t2000\t2000\n", 100, 101)
            fail("Binder worker accepted as process leader")
        } catch (_: IllegalStateException) {}
    }
    @Test fun rejectsRootLeaderAfterWorkerIdentityChanged() {
        try {
            ShellLaunchIdentity.verifyLeader("Uid:\t0\t0\t0\t0\n", 100, 100)
            fail("Root leader accepted")
        } catch (_: IllegalStateException) {}
    }
    @Test fun acceptsOnlyFullyVerifiedShellLeader() {
        ShellLaunchIdentity.verifyLeader("Name:\tlauncher\nUid:\t2000\t2000\t2000\t2000\n", 100, 100)
        for (status in listOf("", "Uid: 2000 2000", "Uid: 2000 0 2000 2000")) {
            try { ShellLaunchIdentity.verifyLeader(status, 100, 100); fail("Invalid leader accepted") }
            catch (_: IllegalStateException) {}
        }
    }
}
