package com.tomtom.installer

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeInstallerLaunchPolicyTest {
    @Test fun rootKeepsActivityBehaviorWithoutPropagatingUriGrants() {
        val activityFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        assertEquals(activityFlags, NativeInstallerLaunchPolicy.flags(0, activityFlags or grants))
    }

    @Test fun adbKeepsTheProvenUriGrantAndActivityFlags() {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        assertEquals(flags, NativeInstallerLaunchPolicy.flags(2000, flags))
    }
}
