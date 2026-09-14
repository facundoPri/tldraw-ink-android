package com.facundopri.tldrawink

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.behavior.ResponseNode
import androidx.ink.brush.behavior.EasingFunction
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

private const val TAG = "TldrawInk"
class MainActivity : Activity() {
    private lateinit var hybridCanvas: HybridCanvas
    private var fileCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    fun chooseFile(callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try { startActivityForResult(params.createIntent(), 41); true } catch (_: Exception) { fileCallback = null; false }
    }

    @Deprecated("Legacy activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        hybridCanvas = HybridCanvas(this)
        val root = FrameLayout(this)
        root.addView(hybridCanvas, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.captionBar() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        hybridCanvas.destroy()
        super.onDestroy()
    }
}

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
private class HybridCanvas(
    private val activity: Activity,
) : FrameLayout(activity) {
    private val webView = WebView(activity)
    private var handwritingDialog: HandwritingDialog? = null
    private val inkOverlay = StylusInkOverlay(activity, ::commitStroke, ::forwardEraserEvent)
    private val pendingEraserEvents = ArrayDeque<MotionEvent>()
    private var eraserActivation = 0
    private var webEraserReady = false
    private var webEraserStarting = false
    private var rendererGone = false
    private var boardEpoch = 0
    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
        .build()

    init {
        setBackgroundColor(Color.WHITE)

        webView.apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean =
                    (activity as MainActivity).chooseFile(callback, params)
            }
            webViewClient = HybridWebViewClient()
            addJavascriptInterface(AndroidInkBridge(), "AndroidInk")
        }

        addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            inkOverlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        // Unlike JavascriptInterface, this bridge is restricted to our bundled
        // main-frame origin. Embedded websites cannot read saved invitations.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "TextEntry", setOf("https://appassets.androidplatform.net")) { _, message, _, isMainFrame, reply ->
                if (isMainFrame) {
                    var requestId = -1
                    try {
                        val request = JSONObject(message.data ?: "{}")
                        requestId = request.getInt("id")
                        check(handwritingDialog == null) { "Text entry is already open" }
                        val id = requestId
                        handwritingDialog = HandwritingDialog(activity, request.optString("text").take(20000), request.optBoolean("dark"), request.optJSONArray("strokes")) { text ->
                            handwritingDialog = null
                            if (!rendererGone) reply.postMessage(JSONObject().put("id", id).put("text", text ?: JSONObject.NULL).toString())
                        }.also { it.show() }
                    } catch (_: Exception) {
                        reply.postMessage(JSONObject().put("id", requestId).put("error", "Could not open handwriting input.").toString())
                    }
                }
            }
            val recentBoards = RecentBoards(activity)
            WebViewCompat.addWebMessageListener(webView, "RecentBoards", setOf("https://appassets.androidplatform.net")) { _, message, _, isMainFrame, reply ->
                if (isMainFrame) {
                    var requestId = -1
                    val response = try {
                        val request = JSONObject(message.data ?: "{}")
                        requestId = request.getInt("id")
                        val result = when (request.getString("method")) {
                            "load" -> JSONArray(recentBoards.load())
                            "save" -> { recentBoards.save(request.getJSONArray("items").toString()); true }
                            else -> error("Unknown method")
                        }
                        JSONObject().put("id", requestId).put("result", result)
                    } catch (_: Exception) {
                        JSONObject().put("id", requestId).put("error", "Could not access local history.")
                    }
                    reply.postMessage(response.toString())
                }
            }
        }
        inkOverlay.eagerInit()
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    private fun commitStroke(stroke: PendingStroke) = commitStrokeForBoard(stroke, boardEpoch)

    private fun commitStrokeForBoard(stroke: PendingStroke, epoch: Int) {
        webView.post {
            if (rendererGone || epoch != boardEpoch) return@post
            val encodedPayload = JSONObject.quote(stroke.json)
            webView.evaluateJavascript(
                "window.hybridInkCommit && window.hybridInkCommit($encodedPayload)",
            ) { result ->
                if (result == "null" || result == "false") {
                    Log.w(TAG, "Stroke waiting for web editor")
                    if (!rendererGone && epoch == boardEpoch) webView.postDelayed({ commitStrokeForBoard(stroke, epoch) }, 500)
                }
            }
        }
    }

    private fun forwardEraserEvent(source: MotionEvent) {
        val event = MotionEvent.obtain(source)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            clearPendingEraserEvents()
            webEraserReady = false
            webEraserStarting = true
            pendingEraserEvents.addLast(event)
            val activation = ++eraserActivation
            webView.evaluateJavascript(
                "Boolean(window.hybridInkSetEraser && window.hybridInkSetEraser(true))",
            ) { result ->
                if (activation != eraserActivation) return@evaluateJavascript
                if (result != "true") {
                    webEraserStarting = false
                    Log.w(TAG, "Web editor was not ready for the S Pen eraser")
                    clearPendingEraserEvents()
                    return@evaluateJavascript
                }
                webView.postOnAnimation {
                    if (activation != eraserActivation) return@postOnAnimation
                    webEraserStarting = false
                    webEraserReady = true
                    while (pendingEraserEvents.isNotEmpty() && webEraserReady) {
                        dispatchEraserEvent(pendingEraserEvents.removeFirst())
                    }
                }
            }
            return
        }

        when {
            webEraserStarting -> pendingEraserEvents.addLast(event)
            webEraserReady -> dispatchEraserEvent(event)
            else -> event.recycle()
        }
    }

    private fun dispatchEraserEvent(event: MotionEvent) {
        val endsGesture = event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        val encodedPayload = JSONObject.quote(buildEraserEventJson(event))
        webView.evaluateJavascript(
            "window.hybridInkEraserEvent && window.hybridInkEraserEvent($encodedPayload)",
            null,
        )
        event.recycle()
        if (endsGesture) {
            webEraserReady = false
            webView.evaluateJavascript(
                "window.hybridInkSetEraser && window.hybridInkSetEraser(false)",
                null,
            )
        }
    }

    private fun buildEraserEventJson(event: MotionEvent): String {
        val pointerIndex = event.actionIndex.coerceAtMost(event.pointerCount - 1)
        val pointsJson = JSONArray()
        for (historyIndex in 0 until event.historySize) {
            pointsJson.put(
                JSONObject()
                    .put("x", event.getHistoricalX(pointerIndex, historyIndex).toDouble())
                    .put("y", event.getHistoricalY(pointerIndex, historyIndex).toDouble())
                    .put(
                        "pressure",
                        event.getHistoricalPressure(pointerIndex, historyIndex).toDouble(),
                    )
                    .put("time", event.getHistoricalEventTime(historyIndex)),
            )
        }
        pointsJson.put(
            JSONObject()
                .put("x", event.getX(pointerIndex).toDouble())
                .put("y", event.getY(pointerIndex).toDouble())
                .put("pressure", event.getPressure(pointerIndex).toDouble())
                .put("time", event.eventTime),
        )

        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_UP -> "up"
            MotionEvent.ACTION_CANCEL -> "cancel"
            else -> "move"
        }
        return JSONObject()
            .put("action", action)
            .put("pointerId", event.getPointerId(pointerIndex))
            .put("viewWidth", width)
            .put("viewHeight", height)
            .put("points", pointsJson)
            .toString()
    }

    private fun clearPendingEraserEvents() {
        while (pendingEraserEvents.isNotEmpty()) {
            pendingEraserEvents.removeFirst().recycle()
        }
    }

    fun destroy() {
        handwritingDialog?.dismiss()
        boardEpoch += 1
        inkOverlay.cancelUnfinishedStrokes()
        eraserActivation += 1
        clearPendingEraserEvents()
        if (!rendererGone) {
            webView.removeJavascriptInterface("AndroidInk")
            webView.destroy()
        }
    }

    @SuppressLint("MissingOnRenderProcessGone")
    private inner class HybridWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.url.host != "appassets.androidplatform.net"

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

        @Suppress("DEPRECATION")
        override fun shouldInterceptRequest(
            view: WebView,
            url: String,
        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(url.toUri())

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            Log.e(TAG, "WebView renderer exited; crashed=${detail.didCrash()}")
            rendererGone = true
            this@HybridCanvas.removeView(view)
            view.destroy()
            activity.recreate()
            return true
        }
    }

    private inner class AndroidInkBridge {
        @JavascriptInterface
        fun resetBoard() {
            activity.runOnUiThread { boardEpoch += 1; inkOverlay.discardAll() }
        }

        @JavascriptInterface
        fun setHitRegions(json: String) {
            val values = JSONArray(json)
            val rects = (0 until values.length()).map { i ->
                val r = values.getJSONArray(i)
                android.graphics.RectF(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat())
            }
            activity.runOnUiThread { inkOverlay.blockedRects = rects }
        }

        @JavascriptInterface
        fun setInkEnabled(enabled: Boolean) {
            activity.runOnUiThread { inkOverlay.inkEnabled = enabled }
        }

        @JavascriptInterface
        fun setInkStyle(
            color: String,
            renderedColor: String,
            strokeWidth: Float,
            pressureSensitivity: Float,
            profile: String,
            dash: String,
        ) {
            activity.runOnUiThread {
                inkOverlay.setInkStyle(
                    color,
                    renderedColor,
                    strokeWidth,
                    pressureSensitivity,
                    profile,
                    dash,
                )
            }
        }

        @JavascriptInterface
        fun setViewport(
            zoom: Float,
            scrollX: Double,
            scrollY: Double,
            offsetLeft: Double,
            offsetTop: Double,
        ) {
            val viewport = CanvasViewport(zoom, scrollX, scrollY, offsetLeft, offsetTop)
            activity.runOnUiThread { inkOverlay.setViewport(viewport) }
        }

        @JavascriptInterface
        fun strokeCommitted(token: String) {
            activity.runOnUiThread { inkOverlay.acknowledge(token) }
        }

        @JavascriptInterface
        fun reportWebReady() {
            Log.i(TAG, "tldraw bridge ready")
        }
    }
}

private data class RawPoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val time: Long,
)

private data class PendingStroke(
    val token: String,
    val json: String,
)

private data class CanvasViewport(
    val zoom: Float = 1f,
    val scrollX: Double = 0.0,
    val scrollY: Double = 0.0,
    val offsetLeft: Double = 0.0,
    val offsetTop: Double = 0.0,
)

private enum class StylusGesture {
    NONE,
    INK,
    ERASER,
}

@SuppressLint("ClickableViewAccessibility", "RestrictedApi", "ViewConstructor")
private class StylusInkOverlay(
    context: android.content.Context,
    private val onStrokeReady: (PendingStroke) -> Unit,
    private val onEraserEvent: (MotionEvent) -> Unit,
) : FrameLayout(context), InProgressStrokesFinishedListener {
    private val strokesView = InProgressStrokesView(context)
    private var activeGesture = StylusGesture.NONE
    var inkEnabled: Boolean = false
        set(value) {
            if (!value && field && activeGesture == StylusGesture.INK) {
                cancelUnfinishedStrokes()
                resetCurrentStroke()
                activeGesture = StylusGesture.NONE
            }
            field = value
        }

    private val density = resources.displayMetrics.density
    private val motionPredictor = MotionEventPredictor.newInstance(this)
    private var brushColor = "#1b1b1f"
    private var renderedBrushColor = brushColor
    private var nativeStrokeWidth = 2f
    private var pressureSensitivity = 0.7f
    private var brushProfile = "pressure"
    private var inkDash = "draw"
    private var strokeDash = "draw"
    private var viewport = CanvasViewport()
    private var strokeViewport = CanvasViewport()
    private var strokeColor = brushColor
    private var strokeWidth = nativeStrokeWidth
    private var strokePressureSensitivity = pressureSensitivity
    private var strokeProfile = brushProfile
    private val pressureFamily = createPressureFamily()
    private var brush = createBrush()
    private var currentStrokeId: InProgressStrokeId? = null
    private var currentPointerId: Int? = null
    private var strokeStartTimeMillis = 0L
    private var lastInputX = Float.NaN
    private var lastInputY = Float.NaN
    private var lastInputElapsedMillis = -1L
    private var lastNonZeroPressure = 0.5f
    private val currentPoints = ArrayList<RawPoint>(256)
    private val pendingByStrokeId = LinkedHashMap<InProgressStrokeId, PendingStroke>()
    private val strokeIdByToken = LinkedHashMap<String, InProgressStrokeId>()

    init {
        addView(
            strokesView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        strokesView.addFinishedStrokesListener(this)
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun discardAll() {
        inkEnabled = false
        strokesView.cancelUnfinishedStrokes()
        strokesView.removeFinishedStrokes(strokeIdByToken.values.toSet())
        strokeIdByToken.clear()
        pendingByStrokeId.clear()
        resetCurrentStroke()
        activeGesture = StylusGesture.NONE
    }

    fun eagerInit() = strokesView.eagerInit()

    fun cancelUnfinishedStrokes() = strokesView.cancelUnfinishedStrokes()

    fun setInkStyle(
        color: String,
        renderedColor: String,
        strokeWidth: Float,
        sensitivity: Float,
        profile: String,
        dash: String,
    ) {
        val parsedColor = runCatching { color.toColorInt() }.getOrDefault(Color.rgb(27, 27, 31))
        val parsedRenderedColor = runCatching { renderedColor.toColorInt() }
            .getOrDefault(parsedColor)
        brushColor = String.format("#%06X", 0xFFFFFF and parsedColor)
        renderedBrushColor = String.format("#%06X", 0xFFFFFF and parsedRenderedColor)
        nativeStrokeWidth = max(0.5f, strokeWidth)
        pressureSensitivity = sensitivity.coerceIn(0f, 1.5f)
        inkDash = if (dash == "solid") "solid" else "draw"
        brushProfile = if (profile == "monoline") "monoline" else "pressure"
        brush = createBrush(parsedRenderedColor)
    }

    fun setViewport(value: CanvasViewport) {
        viewport = value
        brush = createBrush(renderedBrushColor.toColorInt())
    }

    // tldraw pen radius: (1 + (baseWidth+1)*1.2) * ease(0.19 + 0.62*pressure).
    // Ink Brush.size is a diameter. Approximate the mixed linear/sine easing
    // with a cubic whose endpoint values and derivatives match tldraw.
    @OptIn(ExperimentalInkCustomBrushApi::class)
    private fun createPressureFamily(): BrushFamily {
        val start = 0.19f
        val end = 0.81f
        val halfPi = (Math.PI / 2).toFloat()
        fun ease(t: Float) = t * 0.65f + kotlin.math.sin(t * halfPi) * 0.35f
        fun derivative(t: Float) = 0.65f + 0.35f * halfPi * kotlin.math.cos(t * halfPi)
        val low = ease(start)
        val high = ease(end)
        val slopeStart = (end-start) * derivative(start) / (high-low)
        val slopeEnd = (end-start) * derivative(end) / (high-low)
        val response = BrushBehavior(TargetNode(
            target = TargetNode.Target.SIZE_MULTIPLIER,
            targetModifierRangeStart = low,
            targetModifierRangeEnd = high,
            input = ResponseNode(
                responseCurve = EasingFunction.CubicBezier(1f/3f, slopeStart/3f, 2f/3f, 1f-slopeEnd/3f),
                input = SourceNode(source = SourceNode.Source.NORMALIZED_PRESSURE, sourceValueRangeStart = 0f, sourceValueRangeEnd = 1f),
            ),
        ), developerComment = "Live S Pen pressure matched to tldraw pen radius")
        val marker = StockBrushes.marker()
        val coat = marker.coats.first()
        return BrushFamily(coats = listOf(coat.copy(tip = coat.tip.copy(behaviors = listOf(response, StockBrushes.predictionFadeOutBehavior)))), inputModel = marker.inputModel, clientBrushFamilyId = "tldraw-ink-pressure-v1")
    }

    private fun createBrush(colorInt: Int = Color.rgb(27, 27, 31)): Brush =
        Brush.createWithColorIntArgb(
            family = if (brushProfile == "monoline") StockBrushes.marker() else pressureFamily,
            colorIntArgb = colorInt,
            size = (if (brushProfile == "monoline") nativeStrokeWidth + 1f else 2f * (1f + (nativeStrokeWidth + 1f) * 1.2f)) * density * viewport.zoom,
            epsilon = 0.1f,
        )

    var blockedRects: List<android.graphics.RectF> = emptyList()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (activeGesture == StylusGesture.NONE && (!inkEnabled || blockedRects.any { it.contains(event.x / width, event.y / height) })) return false
        if (activeGesture == StylusGesture.NONE && !isStylusDown(event)) {
            return false
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startStylusGesture(event)
            MotionEvent.ACTION_MOVE -> updateStylusGesture(event)
            MotionEvent.ACTION_UP -> finishStylusGesture(event)
            MotionEvent.ACTION_CANCEL -> cancelStylusGesture(event)
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE,
            -> updateStylusGesture(event)
            else -> activeGesture != StylusGesture.NONE
        }
    }

    private fun isStylusDown(event: MotionEvent): Boolean =
        event.actionMasked == MotionEvent.ACTION_DOWN &&
            isStylusTool(event, event.actionIndex)

    private fun isStylusTool(event: MotionEvent, pointerIndex: Int): Boolean {
        val toolType = event.getToolType(pointerIndex)
        return toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun wantsEraser(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex.coerceAtMost(event.pointerCount - 1)
        val buttonMask = MotionEvent.BUTTON_STYLUS_PRIMARY or
            MotionEvent.BUTTON_STYLUS_SECONDARY
        return event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER ||
            event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY) ||
            event.isButtonPressed(MotionEvent.BUTTON_STYLUS_SECONDARY) ||
            (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                event.actionButton and buttonMask != 0)
    }

    private fun startStylusGesture(event: MotionEvent): Boolean {
        return if (wantsEraser(event)) {
            activeGesture = StylusGesture.ERASER
            requestUnbufferedDispatch(event)
            parent?.requestDisallowInterceptTouchEvent(true)
            Log.d(TAG, "S Pen eraser started; buttons=${event.buttonState}")
            onEraserEvent(event)
            true
        } else if (inkEnabled) {
            activeGesture = StylusGesture.INK
            startNativeStroke(event)
        } else {
            false
        }
    }

    private fun updateStylusGesture(event: MotionEvent): Boolean {
        return when (activeGesture) {
            StylusGesture.INK -> {
                if (wantsEraser(event)) {
                    finishNativeStroke(event)
                    activeGesture = StylusGesture.ERASER
                    sendSyntheticAction(event, MotionEvent.ACTION_DOWN)
                    true
                } else {
                    updateNativeStroke(event)
                }
            }
            StylusGesture.ERASER -> {
                if (!wantsEraser(event) && event.actionMasked != MotionEvent.ACTION_BUTTON_PRESS) {
                    sendSyntheticAction(event, MotionEvent.ACTION_UP)
                    activeGesture = StylusGesture.NONE
                    if (inkEnabled) {
                        activeGesture = StylusGesture.INK
                        startSyntheticInkStroke(event)
                    }
                    true
                } else {
                    onEraserEvent(event)
                    true
                }
            }
            StylusGesture.NONE -> false
        }
    }

    private fun finishStylusGesture(event: MotionEvent): Boolean {
        val handled = when (activeGesture) {
            StylusGesture.INK -> finishNativeStroke(event)
            StylusGesture.ERASER -> {
                onEraserEvent(event)
                true
            }
            StylusGesture.NONE -> false
        }
        activeGesture = StylusGesture.NONE
        parent?.requestDisallowInterceptTouchEvent(false)
        return handled
    }

    private fun cancelStylusGesture(event: MotionEvent): Boolean {
        val handled = when (activeGesture) {
            StylusGesture.INK -> cancelNativeStroke(event)
            StylusGesture.ERASER -> {
                onEraserEvent(event)
                true
            }
            StylusGesture.NONE -> false
        }
        activeGesture = StylusGesture.NONE
        parent?.requestDisallowInterceptTouchEvent(false)
        return handled
    }

    private fun sendSyntheticAction(source: MotionEvent, action: Int) {
        val event = MotionEvent.obtain(source)
        event.action = action
        onEraserEvent(event)
        event.recycle()
    }

    private fun startSyntheticInkStroke(source: MotionEvent) {
        val event = MotionEvent.obtain(source)
        event.action = MotionEvent.ACTION_DOWN
        startNativeStroke(event)
        event.recycle()
    }

    private fun startNativeStroke(event: MotionEvent): Boolean {
        requestUnbufferedDispatch(event)
        parent?.requestDisallowInterceptTouchEvent(true)
        motionPredictor.record(event)

        val pointerId = event.getPointerId(event.actionIndex)
        currentPointerId = pointerId
        strokeViewport = viewport
        strokeColor = brushColor
        strokeWidth = nativeStrokeWidth
        strokePressureSensitivity = pressureSensitivity
        strokeProfile = brushProfile
        strokeDash = inkDash
        currentPoints.clear()
        strokeStartTimeMillis = event.eventTime
        lastInputX = Float.NaN
        lastInputY = Float.NaN
        lastInputElapsedMillis = -1L
        lastNonZeroPressure = 0.5f
        recordSamples(event, pointerId)
        val pointerIndex = event.findPointerIndex(pointerId)
        val input = createExplicitInput(
            x = event.getX(pointerIndex),
            y = event.getY(pointerIndex),
            eventTimeMillis = event.eventTime,
            rawPressure = event.getPressure(pointerIndex),
            forceUnique = false,
        )
        currentStrokeId = strokesView.startStroke(input, brush)
        return true
    }

    private fun updateNativeStroke(event: MotionEvent): Boolean {
        val pointerId = currentPointerId ?: return false
        val strokeId = currentStrokeId ?: return false
        if (event.findPointerIndex(pointerId) < 0) return true

        motionPredictor.record(event)
        val predictedEvent = motionPredictor.predict()
        recordSamples(event, pointerId)
        val realInputs = buildExplicitInputBatch(event, pointerId, updateCursor = true)
        val predictedInputs = predictedEvent?.let {
            buildExplicitInputBatch(it, pointerId, updateCursor = false)
        } ?: MutableStrokeInputBatch()
        if (!realInputs.isEmpty()) {
            strokesView.addToStroke(realInputs, strokeId, predictedInputs)
        }
        predictedEvent?.recycle()
        return true
    }

    private fun finishNativeStroke(event: MotionEvent): Boolean {
        val pointerId = currentPointerId ?: return false
        val strokeId = currentStrokeId ?: return false

        motionPredictor.record(event)
        recordSamples(event, pointerId)
        val historyInputs = buildExplicitInputBatch(
            event,
            pointerId,
            updateCursor = true,
            includeCurrent = false,
        )
        if (!historyInputs.isEmpty()) {
            strokesView.addToStroke(historyInputs, strokeId, MutableStrokeInputBatch())
        }
        val pointerIndex = event.findPointerIndex(pointerId)
        val finishInput = createExplicitInput(
            x = event.getX(pointerIndex),
            y = event.getY(pointerIndex),
            eventTimeMillis = event.eventTime,
            rawPressure = event.getPressure(pointerIndex),
            forceUnique = true,
        )
        val pending = buildPendingStroke()
        pendingByStrokeId[strokeId] = pending
        strokesView.finishStroke(finishInput, strokeId)
        resetCurrentStroke()
        performClick()
        return true
    }

    private fun cancelNativeStroke(event: MotionEvent): Boolean {
        currentStrokeId?.let { strokesView.cancelStroke(it) }
        resetCurrentStroke()
        return true
    }

    /**
     * Converts MotionEvent samples ourselves instead of relying on Jetpack Ink's
     * optional-axis detection. Some Samsung events expose getPressure() but do
     * not advertise AXIS_PRESSURE for the event's exact source, which causes the
     * convenience overload to silently create pressure-less StrokeInputs.
     */
    private fun buildExplicitInputBatch(
        event: MotionEvent,
        pointerId: Int,
        updateCursor: Boolean,
        includeCurrent: Boolean = true,
    ): MutableStrokeInputBatch {
        val pointerIndex = event.findPointerIndex(pointerId)
        val batch = MutableStrokeInputBatch()
        if (pointerIndex < 0) return batch

        var cursorX = lastInputX
        var cursorY = lastInputY
        var cursorElapsed = lastInputElapsedMillis
        var pressureCursor = lastNonZeroPressure

        fun append(x: Float, y: Float, eventTimeMillis: Long, rawPressure: Float) {
            val elapsed = (eventTimeMillis - strokeStartTimeMillis).coerceAtLeast(0L)
            if (elapsed < cursorElapsed) return
            if (elapsed == cursorElapsed && x == cursorX && y == cursorY) return

            val pressure = if (rawPressure > 0.01f) {
                rawPressure.coerceIn(0.01f, 1f).also { pressureCursor = it }
            } else {
                pressureCursor
            }
            batch.add(
                StrokeInput.create(
                    x = x,
                    y = y,
                    elapsedTimeMillis = elapsed,
                    toolType = InputToolType.STYLUS,
                    pressure = pressure,
                ),
            )
            cursorX = x
            cursorY = y
            cursorElapsed = elapsed
        }

        for (historyIndex in 0 until event.historySize) {
            append(
                event.getHistoricalX(pointerIndex, historyIndex),
                event.getHistoricalY(pointerIndex, historyIndex),
                event.getHistoricalEventTime(historyIndex),
                event.getHistoricalPressure(pointerIndex, historyIndex),
            )
        }
        if (includeCurrent) {
            append(
                event.getX(pointerIndex),
                event.getY(pointerIndex),
                event.eventTime,
                event.getPressure(pointerIndex),
            )
        }

        if (updateCursor && !batch.isEmpty()) {
            lastInputX = cursorX
            lastInputY = cursorY
            lastInputElapsedMillis = cursorElapsed
            lastNonZeroPressure = pressureCursor
        }
        return batch
    }

    private fun createExplicitInput(
        x: Float,
        y: Float,
        eventTimeMillis: Long,
        rawPressure: Float,
        forceUnique: Boolean,
    ): StrokeInput {
        var elapsed = (eventTimeMillis - strokeStartTimeMillis).coerceAtLeast(0L)
        if (forceUnique && elapsed == lastInputElapsedMillis && x == lastInputX && y == lastInputY) {
            elapsed += 1L
        }
        val pressure = if (rawPressure > 0.01f) {
            rawPressure.coerceIn(0.01f, 1f).also { lastNonZeroPressure = it }
        } else {
            lastNonZeroPressure
        }
        lastInputX = x
        lastInputY = y
        lastInputElapsedMillis = elapsed
        return StrokeInput.create(
            x = x,
            y = y,
            elapsedTimeMillis = elapsed,
            toolType = InputToolType.STYLUS,
            pressure = pressure,
        )
    }

    private fun resetCurrentStroke() {
        currentStrokeId = null
        currentPointerId = null
        currentPoints.clear()
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun recordSamples(event: MotionEvent, pointerId: Int) {
        val pointerIndex = event.findPointerIndex(pointerId)
        if (pointerIndex < 0) return

        for (historyIndex in 0 until event.historySize) {
            addRawPoint(
                event.getHistoricalX(pointerIndex, historyIndex),
                event.getHistoricalY(pointerIndex, historyIndex),
                event.getHistoricalPressure(pointerIndex, historyIndex),
                event.getHistoricalEventTime(historyIndex),
            )
        }
        addRawPoint(
            event.getX(pointerIndex),
            event.getY(pointerIndex),
            event.getPressure(pointerIndex),
            event.eventTime,
        )
    }

    private fun addRawPoint(x: Float, y: Float, pressure: Float, time: Long) {
        val previous = currentPoints.lastOrNull()
        if (
            previous != null &&
            abs(previous.x - x) < 0.01f &&
            abs(previous.y - y) < 0.01f &&
            previous.time == time
        ) {
            return
        }
        currentPoints += RawPoint(x, y, pressure.coerceIn(0f, 1f), time)
    }

    private fun buildPendingStroke(): PendingStroke {
        val token = UUID.randomUUID().toString()
        val pointsJson = JSONArray()
        for (point in currentPoints) {
            pointsJson.put(
                JSONObject()
                    .put("x", point.x.toDouble())
                    .put("y", point.y.toDouble())
                    .put("pressure", point.pressure.toDouble())
                    .put("time", point.time),
            )
        }

        val json = JSONObject()
            .put("token", token)
            .put("viewWidth", width)
            .put("viewHeight", height)
            .put("color", strokeColor)
            .put("strokeWidth", strokeWidth.toDouble())
            .put("pressureSensitivity", strokePressureSensitivity.toDouble())
            .put("profile", strokeProfile)
            .put("dash", strokeDash)
            .put(
                "viewport",
                JSONObject()
                    .put("zoom", strokeViewport.zoom.toDouble())
                    .put("scrollX", strokeViewport.scrollX)
                    .put("scrollY", strokeViewport.scrollY)
                    .put("offsetLeft", strokeViewport.offsetLeft)
                    .put("offsetTop", strokeViewport.offsetTop),
            )
            .put("points", pointsJson)
            .toString()
        return PendingStroke(token, json)
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        for ((strokeId, stroke) in strokes) {
            val inputs = stroke.inputs
            if (!inputs.hasPressure()) {
                Log.w(TAG, "Finished native stroke has no pressure samples")
            } else {
                val sample = StrokeInput()
                var minPressure = 1f
                var maxPressure = 0f
                for (index in 0 until inputs.size) {
                    inputs.populate(index, sample)
                    minPressure = minOf(minPressure, sample.pressure)
                    maxPressure = maxOf(maxPressure, sample.pressure)
                }
                Log.d(
                    TAG,
                    "Native pressure samples=${inputs.size} range=$minPressure..$maxPressure",
                )
            }
            val pending = pendingByStrokeId.remove(strokeId) ?: continue
            strokeIdByToken[pending.token] = strokeId
            onStrokeReady(pending)
        }
    }

    fun acknowledge(token: String) {
        val strokeId = strokeIdByToken.remove(token) ?: return
        strokesView.removeFinishedStrokes(setOf(strokeId))
    }
}
