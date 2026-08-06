package com.aure.clustertune.data

fun interface PrivilegedSysfsReader {
    fun readText(path: String): String?
}
