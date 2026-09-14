package com.facundopri.tldrawink

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PortraitLayoutTest {
    @Test fun controlsFitRealPortrait() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun findWeb(view: View): WebView? = when(view) {
            is WebView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWeb(view.getChildAt(it)) }
            else -> null
        }
        var web: WebView? = null
        instrumentation.runOnMainSync { web = findWeb(activity.window.decorView) }
        fun js(code: String): String {
            val latch = CountDownLatch(1)
            var result = "null"
            instrumentation.runOnMainSync { web!!.evaluateJavascript(code) {result=it;latch.countDown()} }
            check(latch.await(5,TimeUnit.SECONDS))
            return result
        }
        for(i in 0..100) {if(js("!!document.querySelector('.recent-row')") == "true") break;SystemClock.sleep(100)}
        // Require an explicitly selected test board before connecting or drawing.
        val boardId = requireNotNull(InstrumentationRegistry.getArguments().getString("boardId")) { "Pass -e boardId with a disposable test board saved in Recents" }
        val encodedBoard = org.json.JSONObject.quote(boardId)
        check(js("Array.from(document.querySelectorAll('.recent-row')).some(row=>row.dataset.board===$encodedBoard)") == "true")
        js("Array.from(document.querySelectorAll('.recent-row')).find(row=>row.dataset.board===$encodedBoard).querySelector('button').click()")
        for(i in 0..150) {if(js("!!window.editor") == "true") break;SystemClock.sleep(100)}
        check(js("!!window.editor") == "true")

        instrumentation.runOnMainSync { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        try {
            for (i in 0..50) {if(js("innerHeight > innerWidth") == "true") break;SystemClock.sleep(100)}
            SystemClock.sleep(800)
            org.junit.Assume.assumeTrue("Android ignored the portrait orientation request on this large-screen device", js("innerHeight > innerWidth") == "true")
            check(js("(() => {const r=document.querySelector('.board-controls').getBoundingClientRect();return r.left>=0 && r.right<=innerWidth+1 && r.top>=0 && r.bottom<100})()") == "true")
            js("document.querySelector('.boards-button').click()")
            SystemClock.sleep(300)
            check(js("(() => {const r=document.querySelector('.hub-card').getBoundingClientRect();return r.left>=0 && r.right<=innerWidth+1 && r.top>=0 && r.bottom<=innerHeight+1})()") == "true")
            js("document.querySelector('.hub-heading button').click()")
            SystemClock.sleep(300)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.filesDir,"portrait-layout.png").outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
            screenshot.recycle()
        } finally {instrumentation.runOnMainSync {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}}
    }
}
