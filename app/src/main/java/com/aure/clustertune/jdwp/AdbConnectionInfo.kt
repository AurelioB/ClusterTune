package com.aure.clustertune.jdwp

/** Host/port of the on-device wireless-debugging adbd, once paired+connected. */
data class AdbConnectionInfo(
    val host: String,
    val port: Int,
)
