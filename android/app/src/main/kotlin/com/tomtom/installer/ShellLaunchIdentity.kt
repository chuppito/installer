package com.tomtom.installer

/** Clear inherited groups before changing the launcher main thread to Shell. */
internal object ShellLaunchIdentity {
    interface Credentials {
        fun uid(): Int
        fun clearGroups()
        fun gid(value: Int)
        fun uid(value: Int)
    }
    fun prepare(credentials: Credentials) {
        when (credentials.uid()) {
            0 -> {
                credentials.clearGroups()
                credentials.gid(2000)
                credentials.uid(2000)
            }
            2000 -> Unit
            else -> error("Identité du processus de lancement non prise en charge")
        }
        check(credentials.uid() == 2000) { "Impossible d’appliquer l’identité Shell" }
    }

    fun verifyLeader(status: String, pid: Int, tid: Int) {
        check(pid == tid) { "Le changement d’identité doit être effectué sur le thread principal" }
        val ids = status.lineSequence().firstOrNull { it.startsWith("Uid:") }
            ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() }
        check(ids != null && ids.size == 4 && ids.all { it == 2000 }) {
            "Le processus de lancement est encore root ou son identité est indéterminée"
        }
    }
}
