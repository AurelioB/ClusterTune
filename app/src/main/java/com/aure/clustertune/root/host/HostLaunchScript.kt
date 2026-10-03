package com.aure.clustertune.root.host

/** Keep the host (and its cleanup watchdog) alive when the launching process group exits. */
internal fun hostLaunchScript(arguments: List<String>, classpath: String): String {
    fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
    return buildString {
        appendLine("#!/system/bin/sh")
        // Android's toybox provides both commands. Fail the handoff if detachment cannot start;
        // an attached host could die before restoring the session's frequency ceilings.
        append("CT_HOST_LOG='./host-startup.log' CLASSPATH=${quote(classpath)} ")
        append("nohup setsid /system/bin/app_process /system/bin ")
        append(arguments.joinToString(" ", transform = ::quote))
        appendLine(" >'./host-startup.log' 2>&1 </dev/null &")
    }
}
