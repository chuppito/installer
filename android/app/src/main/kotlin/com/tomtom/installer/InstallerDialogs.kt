package com.tomtom.installer

import android.content.Context
import android.view.ContextThemeWrapper
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Theme only Installer's dialogs; Flutter keeps its own activity theme. */
internal object InstallerDialogs {
    fun builder(context: Context): MaterialAlertDialogBuilder {
        val themed = ContextThemeWrapper(context, R.style.InstallerDialogTheme)
        return MaterialAlertDialogBuilder(DynamicColors.wrapContextIfAvailable(themed))
    }
}
