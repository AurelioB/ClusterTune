package com.wuyr.jdwp_injector

import android.annotation.SuppressLint
import android.os.Process
import com.wuyr.jdwp_injector.adb.AdbClient
import com.wuyr.jdwp_injector.debugger.Debugger
import com.wuyr.jdwp_injector.debugger.getMethodSignature
import com.wuyr.jdwp_injector.debugger.signature
import com.wuyr.jdwp_injector.exception.ProcessNotFoundException
import com.wuyr.jdwp_injector.exception.RootNotDetectedException
import com.wuyr.jdwp_injector.exception.UidSwitchingException
import java.io.File


/**
 * @author wuyr
 * @github https://github.com/wuyr/jdwp-injector-for-android
 * @since 2024-01-25 下午6:19
 */
object JdwpInjector {

    val AdbClient.deviceRooted: Boolean
        get() = sendShellCommand(System.getenv("PATH")?.split(File.pathSeparatorChar)
            ?.joinToString(" || ") { "ls $it/su 2>/dev/null" } ?: "").split("\n").getOrNull(1)?.contains("/su") ?: false

    /**
     * 向目标进程注入代码
     *
     * [adbHost] adb ip
     * [adbPort] adb 端口
     * [useRoot] 是否使用Root权限（对于系统app和release版apk，必须使用Root）
     * [targetPackageName] 目标包名（要注入的apk包名）
     * [targetProcessName] 目标进程名（如果没有在Manifest里指定，默认就是apk包名）
     * [dexPath] apk或dex文件的路径
     * [mainClassName] 注入成功后将要调用的类名（必须在[dexPath]里存在）
     * [mainMethodName] 将要调用的静态无参方法名（必须在[mainClassName]里存在。默认是main，对应public static void main() {} ）
     */
    @JvmStatic
    fun start(
        adbHost: String, adbPort: Int, useRoot: Boolean, targetPackageName: String, targetProcessName: String,
        dexPath: String, mainClassName: String, mainMethodName: String = "main",
    ) {
        AdbClient.openShell(adbHost, adbPort).use { adb ->
            val targetPid = runCatching {
                // 根据进程名查找对应的pid
                adb.sendShellCommand("ps -A -o PID,NAME | grep -w $targetProcessName | awk 'NR==1{print \$1}'").split("\n").getOrNull(1)?.trim()?.toInt() ?: 0
            }.getOrDefault(0)
            if (targetPid == 0) {
                // 进程未运行
                throw ProcessNotFoundException()
            }
            @SuppressLint("SdCardPath")
            val targetDataDir = "/data/user/${Process.myUid() / 100000}/$targetPackageName"
            // 如果使用root权限，则使用su+cd命令，否则使用run-as命令来复制可执行文件
            if (useRoot) {
                if (adb.deviceRooted) {
                    var response = adb.sendShellCommand("su").split("\n").getOrNull(1)?.trim() ?: ""
                    // 检查身份是否切换成功
                    if (adb.sendShellCommand("id").contains("uid=0(")) {
                        adb.sendShellCommand("cd $targetDataDir")
                        response = adb.sendShellCommand("pwd").split("\n").getOrNull(1)?.trim() ?: ""
                        if (!response.contains(targetPackageName)) {
                            throw RuntimeException(response)
                        }
                    } else {
                        throw UidSwitchingException(response)
                    }
                } else {
                    throw RootNotDetectedException()
                }
            } else {
                val response = adb.sendShellCommand("run-as $targetPackageName").split("\n").getOrNull(1)?.trim() ?: ""
                // 检查身份是否切换成功
                if (adb.sendShellCommand("id").contains("uid=2000(")) {
                    throw UidSwitchingException(response)
                }
            }
            val injectorFolder = "jdwp-injector"
            // 创建目录
            adb.sendShellCommand("rm -rf $injectorFolder;mkdir $injectorFolder")
            // 复制可执行文件
            adb.sendShellCommand("cp $dexPath $injectorFolder/drug && chmod 444 $injectorFolder/drug;exit")
            // 尝试连接到目标进程的jdwp线程
            Debugger(AdbClient.connect2jdwp(adbHost, adbPort, targetPid)).use { debugger ->
                // 成功attach之后，设置监视MessageQueue的mMessages变量，设置成功后，通过am attach-agent命令来给目标进程发一条无任何副作用的消息，主动触发断点
                val threadId = debugger.setAndWaitForModificationEventArrive("android.os.MessageQueue", "mMessages", "android.os.Message") {
                    adb.sendShellCommand("am attach-agent $targetProcessName /")
                }
                // 断点命中，开始加载刚刚复制到目标进程外部储存路径的dex或apk文件
                try {
                    val classLoaderClassId = debugger.findClassId(ClassLoader::class.signature)
                    val getSystemClassLoaderMethodId = debugger.findMethodId(classLoaderClassId, "getSystemClassLoader", getMethodSignature(returnTypeName = ClassLoader::class.java.name))
                    val systemClassLoaderObjectId = debugger.invokeStaticMethod(classLoaderClassId, getSystemClassLoaderMethodId, threadId).second as Long
                    val dexClassLoaderObjectId = debugger.newInstance(
                        "dalvik.system.DexClassLoader", threadId, String::class.java.name to "$targetDataDir/$injectorFolder/drug",
                        String::class.java.name to "", String::class.java.name to "", ClassLoader::class.java.name to systemClassLoaderObjectId
                    ).second
                    val loadClassMethodId = debugger.findMethodId(classLoaderClassId, "loadClass", getMethodSignature(String::class.java.name, returnTypeName = Class::class.java.name))
                    val mainClassId = debugger.invokeInstanceMethod(dexClassLoaderObjectId, classLoaderClassId, loadClassMethodId, threadId, String::class.java.name to mainClassName).second as Long
                    val mainMethodId = debugger.findMethodId(mainClassId, mainMethodName, getMethodSignature())
                    // 调用入参指定的类的静态方法
                    debugger.invokeStaticMethod(mainClassId, mainMethodId, threadId)
                } finally {
                    // 恢复运行
                    debugger.resumeVM()
                    // 退出debug
                    debugger.dispose()
                }
            }
        }
    }

}