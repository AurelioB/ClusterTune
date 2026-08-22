package com.wuyr.jdwp_injector.utils

import android.annotation.SuppressLint
import android.util.Log
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import java.lang.reflect.Method

/**
 * @author wuyr
 * @github https://github.com/wuyr/jdwp-injector-for-android
 * @since 2024-04-15 上午10:41
 */
@SuppressLint("DiscouragedPrivateApi")
object HiddenApiExemptions {

    @JvmStatic
    fun doExemptions() {
        val pathList = BaseDexClassLoader::class.java.getDeclaredField("pathList").run {
            isAccessible = true
            get(HiddenApiExemptions::class.java.classLoader)
        } ?: return
        val dexElements = pathList::class.java.getDeclaredField("dexElements").run {
            isAccessible = true
            get(pathList) as? Array<*>
        } ?: return
        if (dexElements.isEmpty()) {
            return
        }
        val pathField = (dexElements[0] ?: return)::class.java.getDeclaredField("path").apply { isAccessible = true }
        dexElements.mapNotNull { if (it == null) null else pathField.get(it) }.forEach { path ->
            try {
                @Suppress("DEPRECATION")
                DexFile(path.toString()).apply {
                    loadClass(HiddenApiExemptions::class.java.canonicalName, null).getDeclaredMethod("exemptionsAll").apply { isAccessible = true }.invoke(null)
                    close()
                }
            } catch (e: Exception) {
                Log.e("HiddenApiExemptions", e.message, e)
            }
        }
    }

    /**
     * Copy from me.weishu.reflection.BootstrapClass（me.weishu:free_reflection）
     */
    @JvmStatic
    private fun exemptionsAll() {
        val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
        (Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf(this::class.java)::class.java)
            .invoke(vmRuntimeClass, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)) as? Method)?.invoke(
            vmRuntimeClass.getDeclaredMethod("getRuntime").invoke(null), *arrayOf<Any>(arrayOf("L"))
        )
    }
}