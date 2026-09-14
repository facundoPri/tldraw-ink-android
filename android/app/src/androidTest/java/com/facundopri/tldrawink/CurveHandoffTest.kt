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
class CurveHandoffTest {
 @Test fun captureShortCurveHandoff() {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
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


  js("window.__curveBefore=Array.from(window.editor.getCurrentPageShapeIds());window.editor.setCamera({x:-50000,y:-50000,z:1});window.editor.setCurrentTool('draw');window.editor.updateInstanceState({stylesForNextShape:{...window.editor.getInstanceState().stylesForNextShape,'tldraw:color':'black','tldraw:size':'s'}});true")
  SystemClock.sleep(500)
  val screen=instrumentation.uiAutomation.takeScreenshot();val cx=screen.width*0.5f;val cy=screen.height*0.5f;screen.recycle()
  val down=SystemClock.uptimeMillis()
  fun send(action:Int,i:Int) {
   val t=i/32.0;val angle=-Math.PI/2+t*Math.PI*1.7
   val props=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS}
   val coords=MotionEvent.PointerCoords().apply{x=cx+(45*kotlin.math.cos(angle)).toFloat();y=cy+(55*kotlin.math.sin(angle)).toFloat();pressure=0.55f;size=0.1f}
   val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,arrayOf(props),arrayOf(coords),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0);instrumentation.sendPointerSync(e);e.recycle()
  }
  fun capture(name:String):Bitmap {val b=instrumentation.uiAutomation.takeScreenshot();File(instrumentation.targetContext.filesDir,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};return b}
  var preview:Bitmap?=null
  try {
   send(MotionEvent.ACTION_DOWN,0)
   try {for(i in 1..32){send(MotionEvent.ACTION_MOVE,i);SystemClock.sleep(12)};SystemClock.sleep(350);preview=capture("curve-preview.png")}finally{send(MotionEvent.ACTION_UP,32)}
   SystemClock.sleep(700);val final=capture("curve-final.png")
   var intersection=0;var union=0
   for(y in cy.toInt()-100..cy.toInt()+100) for(x in cx.toInt()-100..cx.toInt()+100) {
    val a=android.graphics.Color.red(preview!!.getPixel(x,y))<128
    val b=android.graphics.Color.red(final.getPixel(x,y))<128
    if(a&&b)intersection++;if(a||b)union++
   }
   File(instrumentation.targetContext.filesDir,"curve-result.json").writeText("{\"intersection\":$intersection,\"union\":$union,\"iou\":${intersection.toDouble()/union}}")
   final.recycle()
  }finally{preview?.recycle();js("window.editor.deleteShapes(window.editor.getCurrentPageShapes().filter(s=>s.id.startsWith('shape:ink-')&&!window.__curveBefore.includes(s.id)).map(s=>s.id));true")}
 }
}
