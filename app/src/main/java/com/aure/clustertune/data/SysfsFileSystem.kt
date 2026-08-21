package com.aure.clustertune.data

import java.io.File

interface SysfsFileSystem {
    fun listPolicyDirectories(root: String): List<String>
    fun listDirectories(root: String): List<String> = listPolicyDirectories(root)
    fun readText(path: String): String?
}

class RealSysfsFileSystem : SysfsFileSystem {
    override fun listPolicyDirectories(root: String): List<String> {
        val directory = File(root)
        return directory.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("policy") }
            ?.map { it.absolutePath }
            .orEmpty()
    }
    override fun listDirectories(root: String): List<String> = File(root).listFiles()
        ?.filter { it.isDirectory }
        ?.map { it.absolutePath }
        .orEmpty()

    override fun readText(path: String): String? {
        return runCatching {
            File(path).readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
