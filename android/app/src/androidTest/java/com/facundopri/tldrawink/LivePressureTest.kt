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

/** Run only after positioning the connected editor over an empty test area, in draw mode. */
@RunWith(AndroidJUnit4::class)
class LivePressureTest {
    @Test fun previewVariesBeforePenUp() {
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
        js("window.__pressureBefore = Array.from(window.editor.getCurrentPageShapeIds()); window.__pressureCamera = window.editor.getCamera(); window.editor.setCamera({x:-30000,y:-30000,z:1}); window.editor.setCurrentTool('draw'); window.editor.updateInstanceState({stylesForNextShape:{...window.editor.getInstanceState().stylesForNextShape,'tldraw:color':'black','tldraw:size':'xl'}}); true")
        SystemClock.sleep(700)
        val initial = instrumentation.uiAutomation.takeScreenshot()
        val width = initial.width
        val height = initial.height
        initial.recycle()
        val y = height * 0.52f
        val start = width * 0.24f
        val end = width * 0.65f
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, pressure: Float) {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure; size = 0.1f }
            val input = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            instrumentation.sendPointerSync(input)
            input.recycle()
        }
        event(MotionEvent.ACTION_DOWN,start,0.08f)
        try {
            for(i in 1..100) {
                val pressure = if(i < 48) 0.08f else 0.95f
                event(MotionEvent.ACTION_MOVE,start + (end-start)*i/100f,pressure)
                SystemClock.sleep(12)
            }
            SystemClock.sleep(400)
            val preview = instrumentation.uiAutomation.takeScreenshot()
            fun thickness(x: Int): Int {
                return (y.toInt()-100..y.toInt()+100).count { row ->
                    val p = preview.getPixel(x,row)
                    android.graphics.Color.red(p)<100 && android.graphics.Color.green(p)<100 && android.graphics.Color.blue(p)<100
                }
            }
            val low = thickness((start+(end-start)*0.25f).toInt())
            val high = thickness((start+(end-start)*0.82f).toInt())
            File(instrumentation.targetContext.filesDir,"pressure-preview.png").outputStream().use {preview.compress(Bitmap.CompressFormat.PNG,100,it)}
            File(instrumentation.targetContext.filesDir,"pressure-result.json").writeText("{\"lowPixels\":$low,\"highPixels\":$high,\"penStillDown\":true}")
            preview.recycle()
            assertTrue("Thin section must be visible: $low",low>0)
            assertTrue("Pressure must visibly change native preview before pen up: $low -> $high",high>low*1.7)
        } finally {
            event(MotionEvent.ACTION_UP,end,0.95f)
            SystemClock.sleep(700)
            js("window.editor.deleteShapes(window.editor.getCurrentPageShapes().filter(s=>s.id.startsWith('shape:ink-')&&!window.__pressureBefore.includes(s.id)).map(s=>s.id));window.editor.setCamera(window.__pressureCamera);window.editor.setCurrentTool('select');true")
        }
    }
}
