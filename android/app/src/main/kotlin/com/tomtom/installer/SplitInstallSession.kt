package com.tomtom.installer

/** One atomic session for the base APK and all splits; failed sessions are abandoned. */
class SplitInstallSession(private val command: (List<String>, Int?) -> String) {
    fun install(sizes: LongArray, user: Int, supportsPackageSource: Boolean): String {
        require(sizes.isNotEmpty() && sizes.size <= 256 && sizes.all { it > 0 })
        val args = mutableListOf("install-create", "--user", user.toString(), "-i", "com.android.vending", "-S", sizes.sum().toString())
        if (supportsPackageSource) args.addAll(listOf("--package-source", "1"))
        val created = command(args, null)
        val session = Regex("\\[(\\d+)\\]").find(created)?.groupValues?.get(1)
            ?: error("Session d’installation absente : $created")
        var committed = false
        try {
            sizes.forEachIndexed { index, size ->
                command(listOf("install-write", "-S", size.toString(), session, "split$index.apk", "-"), index)
            }
            command(listOf("install-commit", session), null)
            committed = true
            return "Success"
        } finally {
            if (!committed) try { command(listOf("install-abandon", session), null) } catch (_: Exception) {}
        }
    }
}
