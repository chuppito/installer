package com.tomtom.installer

/** Clear inherited groups before permanently dropping privileges in the launcher process. */
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
}
