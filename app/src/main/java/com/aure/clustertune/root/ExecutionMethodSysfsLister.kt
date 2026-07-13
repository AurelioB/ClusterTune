package com.aure.clustertune.root

import com.aure.clustertune.data.PrivilegedSysfsLister

class ExecutionMethodSysfsLister(
    private val executionResolver: PrivilegedExecutionResolver,
) : PrivilegedSysfsLister {

    override fun listChildrenWithPrefix(directoryPath: String, prefix: String): List<String>? {
        val trimmedDirectory = directoryPath.trimEnd('/')
        val script = buildString {
            append("for f in ${shellQuote(trimmedDirectory)}/${shellQuote(prefix)}*; do ")
            append("[ -d \"${'$'}f\" ] && printf '%s\\n' \"${'$'}f\"; ")
            append("done")
        }
        return executionResolver.executeScript(
            scriptName = "list-sysfs-children.sh",
            scriptContents = script,
            captureResult = true,
        ).getOrNull()
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
    }
}
