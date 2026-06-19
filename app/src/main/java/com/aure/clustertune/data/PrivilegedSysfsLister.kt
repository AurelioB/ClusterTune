package com.aure.clustertune.data

/**
 * Lists directory entries via a privileged channel (e.g. PServer / root).
 *
 * Used as a fallback when an unprivileged [SysfsFileSystem] cannot enumerate
 * a sysfs directory because of SELinux restrictions. On some devices
 * (notably the Odin 2 Mini running Android 13 with the standard
 * untrusted_app sepolicy) `File.listFiles()` on
 * `/sys/devices/system/cpu/cpufreq` returns `null` even though the
 * individual policy files are readable through PServer.
 */
fun interface PrivilegedSysfsLister {
    /**
     * Returns absolute paths of immediate children of [directoryPath] whose
     * names start with [prefix], or `null` if the listing could not be
     * performed (privileged channel unavailable, transport error, etc.).
     *
     * An empty list means "listing succeeded but the directory had no
     * matching entries". `null` means "do not trust this result; fall back
     * to the unprivileged listing."
     */
    fun listChildrenWithPrefix(directoryPath: String, prefix: String): List<String>?
}
