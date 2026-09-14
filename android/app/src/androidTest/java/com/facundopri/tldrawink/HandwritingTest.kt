package com.facundopri.tldrawink

import android.content.Intent
import android.os.SystemClock
import android.view.*
import android.webkit.WebView
import android.widget.*
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the real bundled-page bridge, pen capture, downloaded model, and result/cancel callbacks.
 * Uses only an isolated native dialog, never a personal board or shared shape.
 */
@RunWith(AndroidJUnit4::class)
class HandwritingTest {
 @Test fun recognizesPenAndReturnsEditableText() {
  val ins = InstrumentationRegistry.getInstrumentation()
  val activity = ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  fun descendants(v:View):List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
  lateinit var web:WebView
  ins.runOnMainSync {web=descendants(activity.window.decorView).filterIsInstance<WebView>().first()}
  fun js(code:String):String {val latch=CountDownLatch(1);var result="null";ins.runOnMainSync {web.evaluateJavascript(code){result=it;latch.countDown()}};check(latch.await(5,TimeUnit.SECONDS));return result}
  fun all():List<View> {var views=emptyList<View>();ins.runOnMainSync {views=WindowInspector.getGlobalWindowViews().flatMap {descendants(it)}};return views}
  fun waitFor(timeout:Int=15000, condition:()->Boolean) {val end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(100)};error("Timed out waiting for handwriting UI")}
  waitFor {js("!!window.TextEntry")=="true"}
  js("window.__textResult=null;window.TextEntry.onmessage=e=>window.__textResult=JSON.parse(e.data);window.TextEntry.postMessage(JSON.stringify({id:901,text:'',dark:false}));true")
  waitFor {all().any{it is HandwritingPad}}
  val pad=all().filterIsInstance<HandwritingPad>().first()
  val language=all().filterIsInstance<Spinner>().first()
  val requested=InstrumentationRegistry.getArguments().getString("language") ?: "en"
  ins.runOnMainSync {language.setSelection(if(requested=="es")1 else 0)}
  val loc=IntArray(2);var scale=1f
  ins.runOnMainSync {pad.getLocationOnScreen(loc);scale=pad.resources.displayMetrics.density}
  fun stroke(points:List<Pair<Float,Float>>) {
   val down=SystemClock.uptimeMillis()
   points.forEachIndexed { i,p ->
    val property=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS}
    val coords=MotionEvent.PointerCoords().apply{x=loc[0]+p.first*scale;y=loc[1]+p.second*scale;pressure=.6f;size=.1f}
    val action=if(i==0)MotionEvent.ACTION_DOWN else if(i==points.lastIndex)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
    val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,arrayOf(property),arrayOf(coords),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
    ins.sendPointerSync(event);event.recycle();SystemClock.sleep(12)
   }
  }
  fun line(x1:Float,y1:Float,x2:Float,y2:Float)=stroke((0..16).map{val t=it/16f;(x1+(x2-x1)*t) to (y1+(y2-y1)*t)})
  try {
   // H O L A, in natural letter order, deliberately generated test input.
   line(60f,35f,60f,130f);line(110f,35f,110f,130f);line(60f,80f,110f,80f)
   stroke((0..48).map {val t=-Math.PI/2+it*2*Math.PI/48;(170+30*kotlin.math.cos(t)).toFloat() to (82+48*kotlin.math.sin(t)).toFloat()})
   stroke((0..16).map{235f to (35+95*it/16f)}+(1..12).map{(235+45*it/12f) to 130f})
   stroke((0..16).map{(310+30*it/16f) to (130-95*it/16f)}+(1..16).map{(340+30*it/16f) to (35+95*it/16f)})
   line(325f,85f,355f,85f)
   var selectionJson="[]"
   ins.runOnMainSync {
    val array=org.json.JSONArray()
    for(stroke in pad.ink().strokes) {
     val points=org.json.JSONArray()
     for(point in stroke.pointsInGlobalCoordinates) points.put(org.json.JSONObject().put("x",point.x.toDouble()).put("y",point.y.toDouble()))
     array.put(points)
    }
    selectionJson=array.toString()
   }
   var hasInk=false;ins.runOnMainSync{hasInk=pad.hasInk()};assertTrue(hasInk)
   val convert=all().filterIsInstance<Button>().first{it.text.toString()=="Convert"}
   ins.runOnMainSync{convert.performClick()}
   val output=all().filterIsInstance<EditText>().first{it.contentDescription=="Recognized text"}
   var recognized=""
   waitFor(240000) {ins.runOnMainSync{recognized=output.text.toString()};recognized.isNotBlank()}
   assertEquals("HOLA",recognized.uppercase().replace(" ",""))
   // Test editing the recognized candidate before inserting; no shared board mutation.
   ins.runOnMainSync{output.setText("Hola, tablet!\nEditable text.")}
   val insert=all().filterIsInstance<Button>().first{it.text.toString()=="Insert"};ins.runOnMainSync{insert.performClick()}
   waitFor{js("window.__textResult?.text === 'Hola, tablet!\\nEditable text.'")=="true"}
   js("window.__textResult=null;window.TextEntry.postMessage(JSON.stringify({id:902,text:'',dark:false}));true")
   waitFor{all().any{it is HandwritingPad}}
   val cancel=all().filterIsInstance<Button>().first{it.text.toString()=="Cancel"};ins.runOnMainSync{cancel.performClick()}
   waitFor{js("window.__textResult?.id===902 && window.__textResult.text===null")=="true"}
   js("window.__textResult=null;window.TextEntry.postMessage(JSON.stringify({id:903,text:'',dark:true,strokes:$selectionJson}));true")
   waitFor{all().any{it is HandwritingPad}}
   val selectedOutput=all().filterIsInstance<EditText>().first{it.contentDescription=="Recognized text"}
   var selectedText=""
   waitFor(60000){ins.runOnMainSync{selectedText=selectedOutput.text.toString()};selectedText.isNotBlank()}
   assertEquals("HOLA",selectedText.uppercase().replace(" ",""))
   val replace=all().filterIsInstance<Button>().first{it.text.toString()=="Replace ink"}
   ins.runOnMainSync{replace.performClick()}
   waitFor{js("window.__textResult?.id===903 && typeof window.__textResult.text==='string'")=="true"}
  } finally {
   all().filterIsInstance<Button>().firstOrNull{it.text.toString()=="Cancel"}?.let{ins.runOnMainSync{it.performClick()}}
   js("delete window.__textResult;true")
  }
 }
}
