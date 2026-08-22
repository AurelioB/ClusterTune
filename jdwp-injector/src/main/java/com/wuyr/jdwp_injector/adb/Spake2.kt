package com.wuyr.jdwp_injector.adb

import com.wuyr.jdwp_injector.exception.PairingException

/**
 * @author wuyr
 * @github https://github.com/wuyr/jdwp-injector-for-android
 * @since 2024-01-18 6:36 PM
 */
internal class Spake2(password: ByteArray) {
    companion object {
        init {
            System.loadLibrary("spake2")
        }
    }

    private val nativePtr: Long
    var message = ByteArray(32)

    init {
        nativePtr = nativeCreate(password, message)
        if (nativePtr == 0L) {
            throw PairingException("Unable to generate the SPAKE2 public key.")
        }
    }

    fun processMessage(theirMessage: ByteArray) = nativeProcessMessage(nativePtr, theirMessage)

    fun destroy() {
        message.fill(0)
        nativeDestroy(nativePtr)
    }

    private external fun nativeCreate(password: ByteArray, message: ByteArray): Long

    private external fun nativeProcessMessage(nativePtr: Long, theirMessage: ByteArray): ByteArray

    private external fun nativeDestroy(nativePtr: Long)
}