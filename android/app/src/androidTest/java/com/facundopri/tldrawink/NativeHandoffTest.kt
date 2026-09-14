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
class NativeHandoffTest {
    @Test fun leftEdgeAndPreviewMatchFinal() {
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

        js("window.__handoffBefore=Array.from(window.editor.getCurrentPageShapeIds());window.__handoffCamera=window.editor.getCamera();window.__handoffStyles=window.editor.getInstanceState().stylesForNextShape;window.editor.setCurrentTool('draw');window.editor.updateInstanceState({stylesForNextShape:{...window.__handoffStyles,'tldraw:color':'black','tldraw:size':'m'}});true")
        val screen=instrumentation.uiAutomation.takeScreenshot()
        val width=screen.width;val height=screen.height;screen.recycle()
        val measurements=org.json.JSONArray()
        try {
            for ((index,config) in listOf(1f to 0.12f,1f to 0.9f,0.2f to 0.12f,0.2f to 0.9f).withIndex()) {
                val (zoom,pressure)=config
                js("window.editor.setCamera({x:-40000,y:-40000,z:$zoom});true")
                SystemClock.sleep(500)
                val y=height*0.5f
                val start=width*0.07f
                val end=width*0.48f
                val down=SystemClock.uptimeMillis()
                fun send(action:Int,x:Float) {
                    val props=MotionEvent.PointerProperties().apply {id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS}
                    val coords=MotionEvent.PointerCoords().apply {this.x=x;this.y=y;this.pressure=pressure;size=0.1f}
                    val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,arrayOf(props),arrayOf(coords),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
                    instrumentation.sendPointerSync(event);event.recycle()
                }
                fun thickness(bitmap:Bitmap):Int {
                    val x=(start+(end-start)*0.6f).toInt()
                    return (y.toInt()-100..y.toInt()+100).count { row ->
                        val color=bitmap.getPixel(x,row)
                        android.graphics.Color.red(color)<100 && android.graphics.Color.green(color)<100 && android.graphics.Color.blue(color)<100
                    }
                }
                send(MotionEvent.ACTION_DOWN,start)
                var live=0
                try {
                    for(i in 1..100){send(MotionEvent.ACTION_MOVE,start+(end-start)*i/100);SystemClock.sleep(10)}
                    SystemClock.sleep(400)
                    val preview=instrumentation.uiAutomation.takeScreenshot();live=thickness(preview)
                    File(instrumentation.targetContext.filesDir,"handoff-$index-preview.png").outputStream().use{preview.compress(Bitmap.CompressFormat.PNG,100,it)};preview.recycle()
                } finally {send(MotionEvent.ACTION_UP,end)}
                SystemClock.sleep(900)
                check(js("window.editor.getCurrentPageShapes().some(s=>s.id.startsWith('shape:ink-')&&!window.__handoffBefore.includes(s.id))") == "true") {"Stroke starting on left side bypassed native Ink"}
                val rendered=instrumentation.uiAutomation.takeScreenshot();val finalWidth=thickness(rendered)
                File(instrumentation.targetContext.filesDir,"handoff-$index-final.png").outputStream().use{rendered.compress(Bitmap.CompressFormat.PNG,100,it)};rendered.recycle()
                measurements.put(org.json.JSONObject().put("zoom",zoom).put("pressure",pressure).put("preview",live).put("final",finalWidth))
                File(instrumentation.targetContext.filesDir,"handoff-results.json").writeText(measurements.toString())
                assertTrue("Native preview must be visible",live>0)
                assertTrue("Preview mismatch at zoom=$zoom pressure=$pressure: $live versus $finalWidth",kotlin.math.abs(live-finalWidth)<=maxOf(2f,finalWidth*0.15f))
                js("window.editor.deleteShapes(window.editor.getCurrentPageShapes().filter(s=>s.id.startsWith('shape:ink-')&&!window.__handoffBefore.includes(s.id)).map(s=>s.id));true")
            }
        } finally {
            js("window.editor.deleteShapes(window.editor.getCurrentPageShapes().filter(s=>s.id.startsWith('shape:ink-')&&!window.__handoffBefore.includes(s.id)).map(s=>s.id));window.editor.setCamera(window.__handoffCamera);window.editor.updateInstanceState({stylesForNextShape:window.__handoffStyles});window.editor.setCurrentTool('select');true")
        }
    }
}
