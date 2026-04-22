package com.aure.androidtuner.data

import java.io.File

interface SysfsFileSystem {
    fun listPolicyDirectories(root: String): List<String>
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

    override fun readText(path: String): String? {
        val file = File(path)
        return runCatching { file.readText().trim() }.getOrNull()
    }
}
