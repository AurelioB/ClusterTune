package com.aure.clustertune.overlay

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.Settings

data class PerformanceHudVendorState(
    val performanceProfile: String? = null,
    val fanProfile: String? = null,
)

internal data class VendorDeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
)

/**
 * AYN and Retroid expose these user-facing modes through Settings.System.
 * This reader is intentionally observational: the HUD never changes vendor state.
 */
internal class PerformanceHudVendorStateReader(
    context: Context,
    private val identity: VendorDeviceIdentity = VendorDeviceIdentity(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        brand = Build.BRAND.orEmpty(),
        model = Build.MODEL.orEmpty(),
    ),
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    fun read(): PerformanceHudVendorState {
        if (!isAynOrRetroidDevice(identity)) return PerformanceHudVendorState()
        val performanceMode = readSystemSetting("performance_mode")
        val fanMode = readSystemSetting("fan_mode")
        return PerformanceHudVendorState(
            performanceProfile = performanceMode?.let(::vendorPerformanceModeLabel),
            fanProfile = fanMode?.let(::vendorFanModeLabel),
        )
    }

    private fun readSystemSetting(key: String): Int? = runCatching {
        Settings.System.getString(resolver, key)?.trim()?.toIntOrNull()
    }.getOrNull()
}

internal fun isAynOrRetroidDevice(identity: VendorDeviceIdentity): Boolean {
    val signature = listOf(identity.manufacturer, identity.brand, identity.model)
        .joinToString(" ")
        .lowercase()
    return listOf("ayn", "odin", "thor", "retroid", "moorechip").any(signature::contains)
}

internal fun vendorPerformanceModeLabel(raw: Int): String = when (raw) {
    0 -> "Standard"
    1 -> "Medium"
    2 -> "High"
    else -> "Mode $raw"
}

internal fun vendorFanModeLabel(raw: Int): String = when (raw) {
    0 -> "Off"
    1 -> "Quiet"
    4 -> "Smart"
    5 -> "Sports"
    6 -> "Custom"
    else -> "Mode $raw"
}
