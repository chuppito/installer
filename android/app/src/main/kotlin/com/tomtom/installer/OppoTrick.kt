package com.tomtom.installer

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class OppoTrick : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.data?.let {
            startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = it
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending")
            })
        }
        finish()
    }
}
