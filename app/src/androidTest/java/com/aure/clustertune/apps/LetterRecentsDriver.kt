package com.aure.clustertune.apps

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject

/** Opens Android's actual Overview and touches its cards; never starts the selected app. */
internal class LetterRecentsDriver {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ui = inst.uiAutomation
    @Suppress("DEPRECATION")
    private val metrics = DisplayMetrics().also {
        inst.targetContext.getSystemService(DisplayManager::class.java).getDisplay(0).getRealMetrics(it)
    }
    private val width get() = metrics.widthPixels
    private val height get() = metrics.heightPixels

    init {
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    fun open(): JSONObject {
        // The first gesture may only reveal an immersive app's hidden navigation bar.
        // Keeping one downTime and a stationary pause distinguishes Overview from Home.
        repeat(2) { attempt ->
            val systemUi=shell("dumpsys activity service com.android.systemui/.SystemUIService")
            val flags=Regex("mSysUiStateFlags=(\\d+)").find(systemUi)?.groupValues?.get(1)?.toLong()
                ?: error("Cannot determine whether the navigation bar is hidden")
            if(flags and 2L != 0L) {
                shell("input touchscreen -d 0 swipe ${width/2} ${height-5} ${width/2} ${height-150} 250")
                SystemClock.sleep(200)
            }
            swipeAndHold()
            if (awaitCards(1_200)) return JSONObject().put("gestureAttempts",attempt+1)
                .put("fixtureCards",fixtureCards())
        }
        error("Overview did not expose any tappable cards after two swipe-and-hold gestures")
    }

    private fun shell(command: String): String {
        val streams=ui.executeShellCommandRwe(command)
        streams[1].close()
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(streams[0]).bufferedReader().use { it.readText() } +
            android.os.ParcelFileDescriptor.AutoCloseInputStream(streams[2]).bufferedReader().use { it.readText() }
    }

    fun select(letter: Char): JSONObject {
        val label = "ClusterTune Test ${letter.uppercaseChar()}"
        // Recent fixtures are seeded before the scenario. Scroll only the Overview list
        // if the desired card is outside its visible page; do not relaunch the package.
        for (direction in listOf(1,-1)) repeat(if(direction==1) 4 else 8) {
            val card=cards().firstOrNull { it.contentDescription?.contains(label)==true }
            if(card!=null) {
                val rect=Rect().also(card::getBoundsInScreen)
                val evidence=JSONObject().put("letter",letter.toString()).put("description",card.contentDescription)
                    .put("cardBounds",rect.toShortString()).put("selection","touch")
                val down=SystemClock.uptimeMillis()
                touch(down,MotionEvent.ACTION_DOWN,rect.exactCenterX(),rect.exactCenterY())
                SystemClock.sleep(60)
                touch(down,MotionEvent.ACTION_UP,rect.exactCenterX(),rect.exactCenterY())
                return evidence
            }
            horizontalSwipe(direction)
            SystemClock.sleep(350)
        }
        error("No visible Overview card for $label; visible fixtures=${fixtureCards()}")
    }

    fun fixtureCards() = JSONArray(cards().mapNotNull { node ->
        node.contentDescription?.toString()?.takeIf { it.contains("ClusterTune Test") }
    }.distinct())

    private fun cards(): List<AccessibilityNodeInfo> {
        val nodes=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if(node==null) return
            val bounds=Rect().also(node::getBoundsInScreen)
            if(node.isVisibleToUser && node.isClickable && node.packageName?.toString()=="com.android.launcher3" &&
                !node.contentDescription.isNullOrBlank() && bounds.height()>height/3 && bounds.width()>64
            ) nodes+=node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        ui.windows.filter { it.displayId==0 }.forEach { visit(it.root) }
        return nodes
    }

    private fun awaitCards(timeout: Long): Boolean {
        val deadline=SystemClock.uptimeMillis()+timeout
        do {
            if(cards().isNotEmpty()) return true
            SystemClock.sleep(80)
        } while(SystemClock.uptimeMillis()<deadline)
        return false
    }

    private fun swipeAndHold() {
        val down=SystemClock.uptimeMillis()
        val x=width/2f
        val start=height-5f
        val end=height*0.58f
        touch(down,MotionEvent.ACTION_DOWN,x,start)
        repeat(20) { n ->
            SystemClock.sleep(16)
            touch(down,MotionEvent.ACTION_MOVE,x,start+(end-start)*(n+1)/20)
        }
        repeat(12) { SystemClock.sleep(50); touch(down,MotionEvent.ACTION_MOVE,x,end) }
        touch(down,MotionEvent.ACTION_UP,x,end)
    }

    private fun horizontalSwipe(direction: Int) {
        val down=SystemClock.uptimeMillis()
        val start=width*(if(direction>0) 0.2f else 0.8f)
        val end=width*(if(direction>0) 0.8f else 0.2f)
        touch(down,MotionEvent.ACTION_DOWN,start,height/2f)
        repeat(20) { n ->
            SystemClock.sleep(16)
            touch(down,MotionEvent.ACTION_MOVE,start+(end-start)*(n+1)/20,height/2f)
        }
        touch(down,MotionEvent.ACTION_UP,end,height/2f)
    }

    private fun touch(down: Long, action: Int, x: Float, y: Float) {
        val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
        event.source=InputDevice.SOURCE_TOUCHSCREEN
        try { check(ui.injectInputEvent(event,true)) { "Overview touch injection failed" } }
        finally { event.recycle() }
    }
}
