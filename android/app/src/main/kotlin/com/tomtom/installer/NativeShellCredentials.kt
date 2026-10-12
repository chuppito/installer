package com.tomtom.installer

internal object NativeShellCredentials {
    fun load(path: String) { System.load(path) }
    external fun clearGroups()
}
