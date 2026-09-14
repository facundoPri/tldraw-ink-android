package com.facundopri.tldrawink

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ContextThemeWrapper
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import org.json.JSONArray
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.*

/** Captures ordered pen samples; finger/palm input is consumed without adding ink. */
internal class HandwritingPad(context: Context) : View(context) {
    private val strokes = mutableListOf<List<Ink.Point>>()
    private var current: MutableList<Ink.Point>? = null
    private var pointer = -1
    var onChanged: () -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 40, 48); strokeWidth = 2.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    init {
        setBackgroundColor(Color.rgb(249, 250, 251))
        contentDescription = "Handwriting area"
        if (Build.VERSION.SDK_INT >= 34) isAutoHandwritingEnabled = false
    }
    fun ink(): Ink = Ink.builder().apply { strokes.forEach { points ->
        addStroke(Ink.Stroke.builder().apply { points.forEach { addPoint(it) } }.build())
    } }.build()
    fun loadInk(data: JSONArray) {
        require(data.length() <= 2000)
        val raw = (0 until data.length()).map { i -> val stroke = data.getJSONArray(i)
            (0 until stroke.length()).map { j -> val p = stroke.getJSONObject(j);p.getDouble("x").toFloat() to p.getDouble("y").toFloat() }
        }.filter { it.isNotEmpty() }
        require(raw.sumOf { it.size } <= 30000)
        require(raw.flatten().all { it.first.isFinite() && it.second.isFinite() })
        val points = raw.flatten(); if (points.isEmpty()) return
        val minX=points.minOf { it.first };val minY=points.minOf { it.second }
        val scale=minOf((width-32f)/(points.maxOf { it.first }-minX).coerceAtLeast(1f),(height-32f)/(points.maxOf { it.second }-minY).coerceAtLeast(1f))
        var time=0L
        strokes.clear()
        raw.forEach { stroke -> strokes.add(stroke.map { (x,y) -> time+=10;Ink.Point.create(16+(x-minX)*scale,16+(y-minY)*scale,time) });time+=100 }
        invalidate();onChanged()
    }
    fun hasInk() = strokes.isNotEmpty()
    fun clear() { strokes.clear(); current = null; pointer = -1; invalidate(); onChanged() }
    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex); invalidate(); onChanged() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointer != -1 || event.getToolType(event.actionIndex) != MotionEvent.TOOL_TYPE_STYLUS) return true
                parent.requestDisallowInterceptTouchEvent(true)
                pointer = event.getPointerId(event.actionIndex); current = mutableListOf()
            }
            MotionEvent.ACTION_CANCEL -> { current = null; pointer = -1; invalidate(); return true }
        }
        val points = current ?: return true
        val index = event.findPointerIndex(pointer)
        if (index < 0) return true
        val penEnds = (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP) && event.getPointerId(event.actionIndex) == pointer
        val penStarts = (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) && event.getPointerId(event.actionIndex) == pointer
        if (event.actionMasked == MotionEvent.ACTION_MOVE || penStarts || penEnds) {
            // Bound a single recognition batch to avoid unbounded memory growth.
            if (strokes.sumOf { it.size } + points.size + event.historySize < 30000) {
                for (h in 0 until event.historySize) points.add(Ink.Point.create(event.getHistoricalX(index,h), event.getHistoricalY(index,h), event.getHistoricalEventTime(h)))
                points.add(Ink.Point.create(event.getX(index), event.getY(index), event.eventTime))
            }
            if (penEnds) {
                if (points.isNotEmpty()) strokes.add(points.toList())
                current = null; pointer = -1; onChanged()
            }
            invalidate()
        }
        return true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        (strokes + listOfNotNull(current)).forEach { points ->
            if (points.isNotEmpty()) {
                val path = Path(); path.moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { path.lineTo(it.x, it.y) }
                if (points.size == 1) canvas.drawPoint(points[0].x, points[0].y, paint) else canvas.drawPath(path, paint)
            }
        }
    }
}

internal class HandwritingDialog(activity: Activity, initialText: String, dark: Boolean, private val selectionInk: JSONArray? = null, private val complete: (String?) -> Unit) {
    private val context = ContextThemeWrapper(activity, if (dark) R.style.InkShareHandwritingDark else R.style.InkShareHandwritingLight)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val pad = HandwritingPad(context)
    private val output = EditText(context).apply {
        contentDescription = "Recognized text"
        hint = "Review or type your text here"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        gravity = Gravity.TOP; minLines = 2; maxLines = 5
        filters = arrayOf(android.text.InputFilter.LengthFilter(20000))
        setText(initialText.take(20000))
        if (Build.VERSION.SDK_INT >= 34) isAutoHandwritingEnabled = true
    }
    private val message = TextView(context)
    private val language = Spinner(context).apply {
        contentDescription = "Handwriting language"
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("English", "Spanish"))
        setSelection(activity.getPreferences(Context.MODE_PRIVATE).getInt("handwritingLanguage", 0).coerceIn(0,1))
    }
    private val convert = Button(context).apply { text = "Convert"; isAllCaps = false }
    private val clear = Button(context).apply { text = "Clear ink"; isAllCaps = false }
    private val undo = Button(context).apply { text = "Undo stroke"; isAllCaps = false }
    private var recognizer: DigitalInkRecognizer? = null
    private var busy = false
    private var finished = false
    private val dialog: AlertDialog
    init {
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        body.addView(TextView(context).apply { text = if (selectionInk != null) "Review the recognized text. Original strokes stay on the canvas until you tap Replace ink." else "Write one line with your S Pen, then tap Convert. Each conversion adds a line to your text." })
        body.addView(language)
        body.addView(pad, LinearLayout.LayoutParams(-1, dp(180)))
        val actions = LinearLayout(context)
        for (button in if (selectionInk == null) listOf(undo,clear,convert) else listOf(convert)) actions.addView(button, LinearLayout.LayoutParams(0,-2,1f))
        body.addView(actions)
        message.text = "First conversion downloads a language model (~20 MB). Recognition then works offline."
        message.setPadding(0,dp(4),0,dp(8)); body.addView(message)
        body.addView(output, LinearLayout.LayoutParams(-1,-2))
        dialog = AlertDialog.Builder(context).setTitle(if (selectionInk == null) "Write text" else "Convert handwriting")
            .setView(ScrollView(context).apply { addView(body) })
            .setPositiveButton(if (selectionInk == null) "Insert" else "Replace ink", null).setNegativeButton("Cancel") { _, _ -> finish(null) }
            .create()
        dialog.setOnCancelListener { finish(null) }
        dialog.setOnDismissListener { finish(null) }
        pad.onChanged = { refresh() }
        undo.setOnClickListener { pad.undo() }; clear.setOnClickListener { pad.clear() }
        convert.setOnClickListener {
            activity.getPreferences(Context.MODE_PRIVATE).edit().putInt("handwritingLanguage",language.selectedItemPosition).apply()
            recognize()
        }
        output.doAfterTextChanged { refresh() }
    }
    fun show() {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        dialog.window?.setLayout(minOf(dp(760), context.resources.displayMetrics.widthPixels - dp(32)), -2)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = output.text.toString().trim()
            if (text.isNotEmpty() && !busy) { finish(text); dialog.dismiss() }
        }
        refresh()
        if (selectionInk != null) pad.post {
            if (!finished) {
                try { pad.loadInk(selectionInk); recognize() }
                catch (_: Exception) { message.text="Could not read selected handwriting. Select a smaller area and try again." }
            }
        }
    }
    fun dismiss() { dialog.dismiss() }
    private fun finish(text: String?) {
        if (finished) return
        finished = true; recognizer?.close(); recognizer = null; complete(text)
    }
    private fun refresh() {
        convert.isEnabled = !busy && pad.hasInk(); undo.isEnabled = !busy && pad.hasInk(); clear.isEnabled = !busy && pad.hasInk()
        pad.isEnabled = !busy && selectionInk == null; language.isEnabled = !busy; output.isEnabled = !busy
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy && output.text.isNotBlank() && (selectionInk != null || !pad.hasInk())
    }
    private fun recognize() {
        if (busy || !pad.hasInk() || finished) return
        busy = true; refresh()
        val model = DigitalInkRecognitionModel.builder(requireNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag(if (language.selectedItemPosition == 1) "es" else "en-US"))).build()
        val manager = RemoteModelManager.getInstance()
        val ink = pad.ink()
        fun fail(text: String) { if (!finished) { busy = false; message.text = text; refresh() } }
        fun runRecognition() {
            if (finished) return
            message.text = "Recognizing handwriting…"
            recognizer?.close()
            val client = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build()); recognizer = client
            (if (selectionInk != null) client.recognize(ink) else client.recognize(ink, RecognitionContext.builder().setPreContext("").setWritingArea(WritingArea(pad.width.toFloat(),pad.height.toFloat())).build()))
                .addOnSuccessListener { result ->
                    if (!finished) {
                        val text = result.candidates.firstOrNull()?.text?.trim().orEmpty()
                        busy = false
                        if (text.isEmpty()) message.text = "No text recognized. Try writing a little larger."
                        else if ((if (selectionInk != null) text.length else output.text.length + text.length + 1) > 20000) message.text = "Text limit reached. Insert this text before adding more."
                        else { output.setText(if (selectionInk != null) text else listOf(output.text.toString().trimEnd(),text).filter { it.isNotEmpty() }.joinToString("\n")); if (selectionInk == null) pad.clear(); message.text = if (selectionInk != null) "Review the text, then tap Replace ink. Cancel keeps the original strokes." else "Review the text below. Write another line or tap Insert." }
                        refresh()
                    }
                }.addOnFailureListener { fail("Recognition failed. Your ink is still here; try again.") }
        }
        message.text = "Checking language model…"
        manager.isModelDownloaded(model).addOnSuccessListener { downloaded ->
            if (!finished) {
                if (downloaded) runRecognition()
                else {
                    message.text = "Downloading language model… Internet is needed only for this download."
                    manager.download(model, DownloadConditions.Builder().build()).addOnSuccessListener { runRecognition() }
                        .addOnFailureListener { fail("Could not download the model. Check your connection and try Convert again.") }
                }
            }
        }.addOnFailureListener { fail("Could not check the language model. Try again.") }
    }
}
