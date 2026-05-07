package com.aure.clustertune.root

import com.aure.clustertune.data.PrivilegedSysfsLister

/**
 * [PrivilegedSysfsLister] backed by the PServer root channel.
 *
 * `untrusted_app` cannot list `/sys/devices/system/cpu/cpufreq` on some
 * devices (e.g. Odin 2 Mini), so `File.listFiles()` returns null. This
 * implementation runs a shell glob through PServer (which executes as
 * root, like the diagnostic `report-cpufreq.sh` script does) and returns
 * the matching child directory paths.
 *
 * The single `RootExec.executeAsRoot` round trip avoids the per-cat
 * binder transactions that the OdinSettings `RunScriptActivity` fans out
 * across — those are what trigger the `BinderServiceP::onTransact`
 * SIGSEGV seen on the Odin 2 Mini.
 */
class PServerSysfsLister : PrivilegedSysfsLister {

    override fun listChildrenWithPrefix(directoryPath: String, prefix: String): List<String>? {
        // Single-quote the path components so glob expansion happens in the
        // shell but the directory and prefix themselves are not subject to
        // word splitting or further globbing on our side. The trailing `[ -d
        // "$f" ] && printf '%s\n'` ensures we only return real directory
        // entries (and that we print nothing if the glob did not match
        // anything, instead of the literal pattern).
        val escapedDir = shellSingleQuote(directoryPath.trimEnd('/'))
        val escapedPrefix = shellSingleQuote(prefix)
        val command = buildString {
            append("for f in ")
            append(escapedDir)
            append('/')
            append(escapedPrefix)
            // The glob lives outside the single quotes so the shell expands it.
            append("*; do [ -d \"\$f\" ] && printf '%s\\n' \"\$f\"; done")
        }
        val output = RootSupport.runRootCommand(command) ?: return null
        return output
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun shellSingleQuote(value: String): String {
        // Wrap in single quotes; replace any embedded single quote with
        // '\''  (close, escaped quote, reopen).
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
