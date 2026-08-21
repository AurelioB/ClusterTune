package com.aure.clustertune.root.host

object HostDiscovery {
    fun parseTimeInState(text: String): List<Long> = text.lineSequence().mapNotNull { it.trim().split(Regex("\\s+"), limit = 2).firstOrNull()?.toLongOrNull() }.filter { it > 0 }.distinct().sorted().toList()
}
