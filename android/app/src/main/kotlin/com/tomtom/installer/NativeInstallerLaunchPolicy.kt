package com.tomtom.installer

import android.content.Intent

/** Root cannot propagate content URI grants; the app must grant access before launching. */
internal object NativeInstallerLaunchPolicy {
    private const val URI_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    fun flags(serverUid: Int, intentFlags: Int): Int =
        if (serverUid == 0) intentFlags and URI_GRANTS.inv() else intentFlags
}
