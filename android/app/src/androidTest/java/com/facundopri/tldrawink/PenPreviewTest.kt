package com.facundopri.tldrawink

import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.*
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native preview only, isolated from any shared board. JS acknowledges strokes without saving them. */
@RunWith(AndroidJUnit4::class)
class PenPreviewTest {
 @Test fun solidPreviewDoesNotVaryWithPressure() {
  val ins=InstrumentationRegistry.getInstrumentation()
  val activity=ins.startActivitySync(Intent(ins.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  fun find(v:View):WebView?=when(v){is WebView->v;is ViewGroup->(0 until v.childCount).firstNotNullOfOrNull{find(v.getChildAt(it))};else->null}
  lateinit var web:WebView;ins.runOnMainSync{web=find(activity.window.decorView)!!}
  fun js(code:String):String{val latch=CountDownLatch(1);var value="null";ins.runOnMainSync{web.evaluateJavascript(code){value=it;latch.countDown()}};check(latch.await(5,TimeUnit.SECONDS));return value}
  repeat(100){if(js("!!window.AndroidInk")=="true")return@repeat;SystemClock.sleep(50)}
  js("window.__savedCommit=window.hybridInkCommit;window.hybridInkCommit=json=>{AndroidInk.strokeCommitted(JSON.parse(json).token);return true};AndroidInk.setHitRegions('[]');AndroidInk.setViewport(1,0,0,0,0);true")
  val screen=ins.uiAutomation.takeScreenshot();val width=screen.width;val y=screen.height*.12f;screen.recycle()
  fun measure(profile:String,dash:String):Pair<Int,Int>{
   js("AndroidInk.setInkStyle('#000000','#000000',5,1,'$profile','$dash');AndroidInk.setInkEnabled(true);true")
   SystemClock.sleep(100)
   val down=SystemClock.uptimeMillis()
   fun send(action:Int,i:Int){val p=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS};val c=MotionEvent.PointerCoords().apply{x=width*(.1f+.8f*i/80);this.y=y;pressure=if(i<40).1f else .9f;size=.1f};val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,arrayOf(p),arrayOf(c),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0);ins.sendPointerSync(e);e.recycle()}
   send(MotionEvent.ACTION_DOWN,0)
   try {
    for(i in 1..80){send(MotionEvent.ACTION_MOVE,i);SystemClock.sleep(8)}
    SystemClock.sleep(250)
    val bitmap=ins.uiAutomation.takeScreenshot()
    fun thickness(x:Int)=(y.toInt()-80..y.toInt()+80).count{row->Color.red(bitmap.getPixel(x,row))<100}
    val result=thickness((width*.3).toInt()) to thickness((width*.7).toInt());bitmap.recycle();return result
   }finally{send(MotionEvent.ACTION_UP,80);SystemClock.sleep(300)}
  }
  try {
   val solid=measure("monoline","solid");assertTrue(solid.first>0);assertTrue("Solid widths: $solid",kotlin.math.abs(solid.first-solid.second)<=2)
   val pressure=measure("pressure","draw");assertTrue("Pressure widths: $pressure",pressure.second>pressure.first+5)
   java.io.File(ins.targetContext.filesDir,"pen-preview-result.json").writeText("{\"solid\":[${solid.first},${solid.second}],\"pressure\":[${pressure.first},${pressure.second}]}")
  }finally{js("AndroidInk.setInkEnabled(false);window.hybridInkCommit=window.__savedCommit;delete window.__savedCommit;true")}
 }
}
