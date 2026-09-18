package com.example.screentranslator

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class OverlayView(context: Context) : View(context) {

    class Entry(val text: String, val rect: Rect, val srcSize: Float)

    private class Drawn(val rect: Rect, val layout: StaticLayout, val pad: Float, val area: Long)

    private var drawn: List<Drawn> = emptyList()

    private val bgPaint = Paint().apply {
        color = Color.rgb(18, 18, 18)
        isAntiAlias = false
    }

    private val textPaint = TextPaint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }

    fun setEntries(list: List<Entry>) {
        val density = resources.displayMetrics.density
        val pad = 2f * density
        val minSize = 9f * density
        val hardMax = 26f * density
        val out = ArrayList<Drawn>()
        for (en in list) {
            val w = en.rect.width()
            val h = en.rect.height()
            if (w <= 2 || h <= 2) continue
            val innerW = (w - pad * 2f).toInt()
            if (innerW <= 4) continue
            val innerH = h.toFloat() - 1f
            if (innerH <= 2f) continue
            var maxSize = en.srcSize
            if (maxSize < minSize) maxSize = minSize
            if (maxSize > hardMax) maxSize = hardMax
            val layout = fit(en.text, innerW, innerH, maxSize, minSize)
            out.add(Drawn(Rect(en.rect), layout, pad, w.toLong() * h.toLong()))
        }
        drawn = out.sortedByDescending { it.area }
        invalidate()
    }

    private fun fit(text: String, width: Int, height: Float, maxSize: Float, minSize: Float): StaticLayout {
        var size = maxSize
        if (size < minSize) size = minSize
        var layout = build(text, width, size, 0)
        var guard = 0
        while (layout.height > height && size > minSize && guard < 140) {
            size = size - 0.5f
            if (size < minSize) size = minSize
            layout = build(text, width, size, 0)
            guard++
        }
        if (layout.height > height) {
            textPaint.textSize = minSize
            val fm = textPaint.fontMetrics
            var lineH = fm.descent - fm.ascent
            if (lineH < 1f) lineH = 1f
            var maxLines = (height / lineH).toInt()
            if (maxLines < 1) maxLines = 1
            layout = build(text, width, minSize, maxLines)
        }
        return layout
    }

    private fun build(text: String, width: Int, size: Float, maxLines: Int): StaticLayout {
        textPaint.textSize = size
        val align = if (text.length <= 28) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(align)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
        if (maxLines > 0) {
            builder.setMaxLines(maxLines)
            builder.setEllipsize(TextUtils.TruncateAt.END)
        }
        return builder.build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawn.isEmpty()) return

        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val offX = loc[0]
        val offY = loc[1]

        for (d in drawn) {
            val l = (d.rect.left - offX).toFloat()
            val t = (d.rect.top - offY).toFloat()
            val r = (d.rect.right - offX).toFloat()
            val b = (d.rect.bottom - offY).toFloat()
            if (r <= l || b <= t) continue

            canvas.drawRect(l, t, r, b, bgPaint)

            var dy = t + ((b - t) - d.layout.height) / 2f
            if (dy < t) dy = t

            canvas.save()
            canvas.clipRect(l, t, r, b)
            canvas.translate(l + d.pad, dy)
            d.layout.draw(canvas)
            canvas.restore()
        }
    }
}

class TranslatorAccessibilityService : AccessibilityService() {

    private class Item(val text: String, val rect: Rect, val srcSize: Float, val fromImage: Boolean, val id: Int)

    private var overlayView: OverlayView? = null
    private var buttonView: TextView? = null
    private var buttonBg: GradientDrawable? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

    private val measurePaint = TextPaint()
    private val fitPaint = TextPaint()

    private var currentKeyIndex = 0
    private var currentModelIndex = 0
    private var screenWidth = 1
    private var screenHeight = 1
    private var isWorking = false
    private var isOverlayShowing = false
    private var overlayShownAt = 0L
    private var buttonSize = 1
    private var minBoxHeight = 1
    private var minBoxWidth = 1
    private var uiReady = false

    private var receiver: BroadcastReceiver? = null

    private val longPressRunnable = Runnable {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("uiOff", true).apply()
        syncUiState()
        toast(getString(R.string.msg_button_hidden))
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (uiReady) {
            refreshMetrics()
            syncUiState()
            return
        }
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            refreshMetrics()
            minBoxHeight = dpToPx(10)
            minBoxWidth = dpToPx(14)
            setupOverlay()
            setupFloatingButton()
            uiReady = true
            registerReceivers()
            syncUiState()
        } catch (e: Exception) {
            uiReady = false
        }
    }

    private fun registerReceivers() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action == "com.example.screentranslator.EXIT") {
                    shutdown()
                } else if (action == "com.example.screentranslator.SYNC") {
                    syncUiState()
                }
            }
        }
        val filter = IntentFilter()
        filter.addAction("com.example.screentranslator.EXIT")
        filter.addAction("com.example.screentranslator.SYNC")
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        } catch (e: Exception) { }
    }

    private fun refreshMetrics() {
        try {
            val wm = windowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
                val bounds = wm.currentWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            } else {
                val dm = resources.displayMetrics
                screenWidth = dm.widthPixels
                screenHeight = dm.heightPixels
            }
        } catch (e: Exception) {
            val dm = resources.displayMetrics
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
        }
        if (screenWidth < 1) screenWidth = 1
        if (screenHeight < 1) screenHeight = 1
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshMetrics()
        clearOverlay()
        val p = buttonParams
        if (p != null) {
            if (p.x > screenWidth - buttonSize) p.x = screenWidth - buttonSize
            if (p.y > screenHeight - buttonSize) p.y = screenHeight - buttonSize
            if (p.x < 0) p.x = 0
            if (p.y < 0) p.y = 0
            try {
                buttonView?.let { windowManager?.updateViewLayout(it, p) }
            } catch (e: Exception) { }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""
        if (pkg == packageName) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            hideOverlayIfShowing()
            syncUiState()
        }
    }

    override fun onInterrupt() { }

    private fun hideOverlayIfShowing() {
        if (isWorking) return
        if (!isOverlayShowing) return
        if (System.currentTimeMillis() - overlayShownAt < 1500) return
        clearOverlay()
        setButtonColor(COLOR_IDLE)
    }

    private fun clearOverlay() {
        overlayView?.setEntries(emptyList())
        overlayView?.visibility = View.INVISIBLE
        isOverlayShowing = false
    }

    private fun syncUiState() {
        if (!uiReady) return
        val off = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getBoolean("uiOff", false)
        if (off) {
            buttonView?.visibility = View.GONE
            clearOverlay()
        } else {
            if (!isWorking) buttonView?.visibility = View.VISIBLE
        }
    }

    private fun setupOverlay() {
        overlayView = OverlayView(this).apply { visibility = View.INVISIBLE }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        windowManager?.addView(overlayView, params)
    }

    private fun setupFloatingButton() {
        buttonBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(COLOR_IDLE))
        }
        buttonView = TextView(this).apply {
            text = getString(R.string.button_label)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = buttonBg
        }

        buttonSize = dpToPx(46)
        buttonParams = WindowManager.LayoutParams(
            buttonSize, buttonSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        buttonParams?.gravity = Gravity.TOP or Gravity.START
        buttonParams?.x = dpToPx(8)
        buttonParams?.y = screenHeight / 2

        attachButtonTouch()
        windowManager?.addView(buttonView, buttonParams)
    }

    private fun attachButtonTouch() {
        buttonView?.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0f
            private var startY = 0f
            private var downTime = 0L
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = (buttonParams?.x ?: 0).toFloat()
                        startY = (buttonParams?.y ?: 0).toFloat()
                        downTime = System.currentTimeMillis()
                        moved = false
                        handler.postDelayed(longPressRunnable, 900)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!moved && (Math.abs(dx) > 30 || Math.abs(dy) > 30)) {
                            moved = true
                            handler.removeCallbacks(longPressRunnable)
                        }
                        if (moved) {
                            var nx = (startX + dx).toInt()
                            var ny = (startY + dy).toInt()
                            if (nx < 0) nx = 0
                            if (ny < 0) ny = 0
                            if (nx > screenWidth - buttonSize) nx = screenWidth - buttonSize
                            if (ny > screenHeight - buttonSize) ny = screenHeight - buttonSize
                            buttonParams?.x = nx
                            buttonParams?.y = ny
                            try {
                                windowManager?.updateViewLayout(v, buttonParams)
                            } catch (e: Exception) { }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPressRunnable)
                        val dt = System.currentTimeMillis() - downTime
                        if (!moved && dt < 600) onButtonTap()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressRunnable)
                        return true
                    }
                }
                return true
            }
        })
    }

    private fun onButtonTap() {
        if (isWorking) {
            toast(getString(R.string.msg_wait))
            return
        }
        if (isOverlayShowing) {
            clearOverlay()
            setButtonColor(COLOR_IDLE)
        } else {
            translateNow()
        }
    }

    private fun translateNow() {
        isWorking = true
        setButtonColor(COLOR_BUSY)
        buttonView?.visibility = View.GONE
        clearOverlay()
        handler.postDelayed({ collectAndTranslate() }, 220)
    }

    private fun collectAndTranslate() {
        refreshMetrics()
        var root: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow
        } catch (e: Exception) { }

        if (root != null && root.packageName?.toString() == packageName) {
            resetWork()
            toast(getString(R.string.msg_own_app))
            return
        }

        val raw = ArrayList<Item>()
        if (root != null) {
            try {
                walk(root, raw, 0)
            } catch (e: Exception) { }
        }

        val cleaned = dedupe(filterParents(raw))
        val limited = if (cleaned.size > MAX_ITEMS) cleaned.subList(0, MAX_ITEMS) else cleaned
        val items = ArrayList<Item>()
        for (i in limited.indices) {
            val s = limited[i]
            items.add(Item(s.text, Rect(s.rect), s.srcSize, false, i + 1))
        }

        captureScreen { shot ->
            if (items.isEmpty() && shot == null) {
                resetWork()
                toast(getString(R.string.msg_no_text))
            } else {
                startWork(items, shot)
            }
        }
    }

    private fun walk(node: AccessibilityNodeInfo, items: ArrayList<Item>, depth: Int) {
        if (items.size >= MAX_NODES || depth > 90) return
        try {
            val t = node.text?.toString()
            if (t != null) {
                var clean = t.replace(CHAR_NL, ' ').replace(CHAR_CR, ' ').trim()
                if (clean.length > 1 && node.isVisibleToUser && hasTranslatable(clean)) {
                    if (clean.length > 700) clean = clean.substring(0, 700)
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    val screen = Rect(0, 0, screenWidth, screenHeight)
                    if (r.intersect(screen) && r.width() >= minBoxWidth && r.height() >= minBoxHeight) {
                        items.add(Item(clean, r, estimateSize(clean, r), false, 0))
                    }
                }
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i)
                if (child != null) walk(child, items, depth + 1)
            }
        } catch (e: Exception) { }
    }

    private fun hasTranslatable(s: String): Boolean {
        var letters = 0
        var bengali = 0
        for (ch in s) {
            if (Character.isLetter(ch)) {
                letters++
                val code = ch.code
                if (code >= 0x0980 && code <= 0x09FF) bengali++
            }
        }
        if (letters == 0) return false
        return bengali * 100 / letters < 70
    }

    private fun estimateSize(text: String, r: Rect): Float {
        val d = resources.displayMetrics.density
        val minS = 9f * d
        var size = 26f * d
        val w = (r.width() - 2).toFloat()
        val h = r.height().toFloat()
        if (w < 4f) return minS
        while (size > minS) {
            measurePaint.textSize = size
            val total = measurePaint.measureText(text)
            val fm = measurePaint.fontMetrics
            var lineH = fm.descent - fm.ascent
            if (lineH < 1f) lineH = 1f
            var lines = Math.ceil((total / w).toDouble()).toInt()
            if (lines < 1) lines = 1
            if (lines.toFloat() * lineH <= h + 1f) return size
            size = size - 1f
        }
        return minS
    }

    private fun squash(s: String): String {
        return s.replace(" ", "").lowercase()
    }

    private fun filterParents(raw: List<Item>): List<Item> {
        val result = ArrayList<Item>()
        for (a in raw) {
            var isParent = false
            val aFlat = squash(a.text)
            for (b in raw) {
                if (a === b) continue
                if (a.text.length <= b.text.length) continue
                if (!a.rect.contains(b.rect)) continue
                if (aFlat.contains(squash(b.text))) {
                    isParent = true
                    break
                }
            }
            if (!isParent) result.add(a)
        }
        return result
    }

    private fun dedupe(list: List<Item>): List<Item> {
        val out = ArrayList<Item>()
        for (item in list) {
            var duplicate = false
            for (old in out) {
                if (old.text == item.text && old.rect == item.rect) {
                    duplicate = true
                    break
                }
                if (old.text == item.text && overlapPercent(item.rect, old.rect) > 55) {
                    duplicate = true
                    break
                }
            }
            if (!duplicate) out.add(item)
        }
        return out
    }

    private fun overlapPercent(a: Rect, b: Rect): Int {
        val left = if (a.left > b.left) a.left else b.left
        val top = if (a.top > b.top) a.top else b.top
        val right = if (a.right < b.right) a.right else b.right
        val bottom = if (a.bottom < b.bottom) a.bottom else b.bottom
        if (right <= left || bottom <= top) return 0
        val inter = (right - left).toLong() * (bottom - top).toLong()
        val area = a.width().toLong() * a.height().toLong()
        if (area <= 0L) return 0
        return ((inter * 100L) / area).toInt()
    }

    private fun layoutRects(list: List<Item>): MutableList<Item> {
        val sorted = list.sortedWith(Comparator { a, b ->
            val areaA = a.rect.width().toLong() * a.rect.height().toLong()
            val areaB = b.rect.width().toLong() * b.rect.height().toLong()
            if (areaA < areaB) -1 else if (areaA > areaB) 1 else 0
        })

        val placed = ArrayList<Item>()
        for (cand in sorted) {
            val orig = Rect(cand.rect)
            val r = Rect(orig)
            var ok = true
            var guard = 0
            while (guard < 60) {
                guard++
                var hit: Rect? = null
                for (p in placed) {
                    if (Rect.intersects(r, p.rect)) {
                        hit = p.rect
                        break
                    }
                }
                if (hit == null) break

                val ox = Math.min(r.right, hit.right) - Math.max(r.left, hit.left)
                val oy = Math.min(r.bottom, hit.bottom) - Math.max(r.top, hit.top)
                if (oy <= ox) {
                    if (r.centerY() >= hit.centerY()) r.top = hit.bottom else r.bottom = hit.top
                } else {
                    if (r.centerX() >= hit.centerX()) r.left = hit.right else r.right = hit.left
                }
                if (r.width() < minBoxWidth || r.height() < minBoxHeight) {
                    ok = false
                    break
                }
            }
            if (ok) {
                for (p in placed) {
                    if (Rect.intersects(r, p.rect)) {
                        ok = false
                        break
                    }
                }
            }
            val keptArea = r.width().toLong() * r.height().toLong()
            val origArea = orig.width().toLong() * orig.height().toLong()
            if (ok && keptArea * 100L >= origArea * 45L) {
                placed.add(Item(cand.text, r, cand.srcSize, cand.fromImage, cand.id))
            } else {
                placed.add(Item(cand.text, orig, cand.srcSize, cand.fromImage, cand.id))
            }
        }
        return placed
    }

    private fun needsRoom(item: Item): Boolean {
        fitPaint.textSize = if (item.srcSize > 1f) item.srcSize else 12f
        val total = fitPaint.measureText(item.text)
        val fm = fitPaint.fontMetrics
        var lineH = fm.descent - fm.ascent
        if (lineH < 1f) lineH = 1f
        val w = (item.rect.width() - 4).toFloat()
        if (w < 4f) return true
        var lines = Math.ceil((total / w).toDouble()).toInt()
        if (lines < 1) lines = 1
        return lines.toFloat() * lineH > item.rect.height().toFloat()
    }

    private fun collides(r: Rect, list: List<Item>, skip: Int): Boolean {
        for (i in list.indices) {
            if (i == skip) continue
            if (Rect.intersects(r, list[i].rect)) return true
        }
        return false
    }

    private fun grow(placed: MutableList<Item>) {
        val screen = Rect(0, 0, screenWidth, screenHeight)
        for (i in placed.indices) {
            val item = placed[i]
            if (!needsRoom(item)) continue
            val r = item.rect
            val maxExtraW = (r.width() * 0.7f).toInt()
            var extra = 0
            while (extra < maxExtraW) {
                val test = Rect(r.left, r.top, r.right + 8, r.bottom)
                if (!screen.contains(test)) break
                if (collides(test, placed, i)) break
                r.right = test.right
                extra += 8
                if (!needsRoom(item)) break
            }
            if (!needsRoom(item)) continue
            val maxExtraH = (r.height() * 0.8f).toInt()
            extra = 0
            while (extra < maxExtraH) {
                val test = Rect(r.left, r.top, r.right, r.bottom + 6)
                if (!screen.contains(test)) break
                if (collides(test, placed, i)) break
                r.bottom = test.bottom
                extra += 6
                if (!needsRoom(item)) break
            }
        }
    }

    private fun captureScreen(done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            done(null)
            return
        }
        val fired = booleanArrayOf(false)
        val finishOnce: (String?) -> Unit = { value ->
            if (!fired[0]) {
                fired[0] = true
                done(value)
            }
        }
        handler.postDelayed({ finishOnce(null) }, 6000)
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        Thread {
                            var b64: String? = null
                            try {
                                val hb: HardwareBuffer = screenshot.hardwareBuffer
                                val rawBmp = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                                if (rawBmp != null) {
                                    val soft = rawBmp.copy(Bitmap.Config.ARGB_8888, false)
                                    try {
                                        rawBmp.recycle()
                                    } catch (e: Exception) { }
                                    if (soft != null) {
                                        b64 = encodeShot(soft)
                                        try {
                                            soft.recycle()
                                        } catch (e: Exception) { }
                                    }
                                }
                                try {
                                    hb.close()
                                } catch (e: Exception) { }
                            } catch (e: Exception) { }
                            val result = b64
                            handler.post { finishOnce(result) }
                        }.start()
                    }

                    override fun onFailure(errorCode: Int) {
                        handler.post { finishOnce(null) }
                    }
                })
        } catch (e: Exception) {
            finishOnce(null)
        }
    }

    private fun encodeShot(src: Bitmap): String? {
        try {
            val w = src.width
            val h = src.height
            if (w <= 0 || h <= 0) return null
            val maxSide = 1152
            val longSide = if (w > h) w else h
            var scale = 1f
            if (longSide > maxSide) scale = maxSide.toFloat() / longSide.toFloat()
            var scaled = src
            if (scale < 1f) {
                var nw = (w * scale).toInt()
                var nh = (h * scale).toInt()
                if (nw < 1) nw = 1
                if (nh < 1) nh = 1
                scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            if (scaled !== src) {
                try {
                    scaled.recycle()
                } catch (e: Exception) { }
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) return null
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            return null
        }
    }

    private fun startWork(items: List<Item>, shot: String?) {
        buttonView?.visibility = View.VISIBLE
        setButtonColor(COLOR_BUSY)
        val copy = ArrayList<Item>(items)
        Thread { runJob(copy, shot) }.start()
    }

    private fun runJob(items: List<Item>, shot: String?) {
        try {
            val keys = getKeys()
            val models = getModels()
            if (keys.isEmpty() || models.isEmpty()) {
                saveError(getString(R.string.msg_no_key))
                handler.post {
                    resetWork()
                    toast(getString(R.string.msg_no_key))
                }
                return
            }

            val map = HashMap<Int, String>()
            var lastCode = 0

            if (items.isNotEmpty()) {
                var i = 0
                while (i < items.size) {
                    var end = i + CHUNK
                    if (end > items.size) end = items.size
                    val code = translateChunk(items.subList(i, end), keys, models, map)
                    if (code != 200) lastCode = code
                    i = end
                }
                val missing = ArrayList<Item>()
                for (it0 in items) {
                    if (!map.containsKey(it0.id)) missing.add(it0)
                }
                if (missing.isNotEmpty() && missing.size <= 60) {
                    var j = 0
                    while (j < missing.size) {
                        var end = j + CHUNK
                        if (end > missing.size) end = missing.size
                        translateChunk(missing.subList(j, end), keys, models, map)
                        j = end
                    }
                }
            }

            val imageItems = ArrayList<Item>()
            if (shot != null) {
                imageItems.addAll(translateShot(shot, items, keys, models))
            }

            val entries = ArrayList<Item>()
            for (it0 in items) {
                val tr = map[it0.id] ?: continue
                val clean = oneLine(tr)
                if (clean.isBlank()) continue
                if (squash(clean) == squash(it0.text)) continue
                entries.add(Item(clean, Rect(it0.rect), it0.srcSize, false, it0.id))
            }
            var extraId = 500000
            for (im in imageItems) {
                var clash = false
                for (e in entries) {
                    if (overlapPercent(im.rect, e.rect) > 25) {
                        clash = true
                        break
                    }
                }
                if (!clash) {
                    entries.add(Item(im.text, Rect(im.rect), im.srcSize, true, extraId))
                    extraId++
                }
            }

            if (entries.isEmpty()) {
                if (lastCode != 0 && lastCode != 200) {
                    saveError("HTTP " + lastCode)
                    handler.post {
                        resetWork()
                        toast(getString(R.string.msg_failed))
                    }
                } else {
                    saveError("")
                    handler.post {
                        resetWork()
                        toast(getString(R.string.msg_no_text))
                    }
                }
                return
            }

            val laid = layoutRects(entries)
            grow(laid)
            val drawList = ArrayList<OverlayView.Entry>()
            for (e in laid) drawList.add(OverlayView.Entry(e.text, e.rect, e.srcSize))
            saveError("")
            handler.post { showOverlay(drawList) }
        } catch (e: Exception) {
            handler.post {
                resetWork()
                toast(getString(R.string.msg_failed))
            }
        }
    }

    private fun translateChunk(chunk: List<Item>, keys: List<String>, models: List<String>,
                               out: HashMap<Int, String>): Int {
        if (chunk.isEmpty()) return 200
        val prompt = buildPrompt(chunk)
        val total = keys.size * models.size
        var attempts = 0
        var lastCode = 0
        while (attempts < total) {
            val key = keys[currentKeyIndex % keys.size]
            val model = models[currentModelIndex % models.size]
            saveCurrentCombo(key, model)
            val res = callGemini(key, model, prompt, null)
            val body = res.first
            if (body != null) {
                val added = parseTranslations(body, chunk, out)
                if (added > 0) return 200
                lastCode = 204
            } else {
                lastCode = res.second
            }
            moveToNextCombo(keys.size, models.size)
            attempts++
            try {
                Thread.sleep(250)
            } catch (e: Exception) { }
        }
        return lastCode
    }

    private fun buildPrompt(chunk: List<Item>): String {
        val nl = CHAR_NL.toString()
        val sb = StringBuilder()
        sb.append("তুমি একজন পেশাদার অনুবাদক। নিচের নম্বর দেওয়া প্রতিটি লেখা সহজ বাংলায় অনুবাদ করো।").append(nl)
        sb.append("নিয়ম:").append(nl)
        sb.append("1) তালিকার প্রতিটি নম্বরের জন্য অবশ্যই একটি করে ফলাফল দিতে হবে, একটিও বাদ দেওয়া যাবে না।").append(nl)
        sb.append("2) মোট ").append(chunk.size).append(" টি আইটেম আছে, তাই ঠিক ").append(chunk.size).append(" টি ফলাফল দাও।").append(nl)
        sb.append("3) অনুবাদ যত সম্ভব ছোট রাখো, মূল লেখার চেয়ে অনেক বড় করবে না।").append(nl)
        sb.append("4) নাম, ব্র্যান্ড, সংখ্যা, সময়, ইমেইল, লিংক, কোড অপরিবর্তিত রাখো।").append(nl)
        sb.append("5) কোনো ব্যাখ্যা, নোট বা অতিরিক্ত লেখা দেবে না।").append(nl)
        sb.append("6) লেখা আগে থেকেই বাংলা হলে হুবহু সেটাই ফেরত দাও।").append(nl)
        sb.append("শুধু এই JSON ফরম্যাটে উত্তর দাও: {translations:[{id:1,translated_text:বাংলা}]}").append(nl)
        sb.append("লেখার তালিকা:").append(nl)
        for (i in chunk.indices) {
            sb.append(i + 1).append(". ").append(chunk[i].text).append(nl)
        }
        return sb.toString()
    }

    private fun buildShotPrompt(known: List<Item>, full: Boolean): String {
        val nl = CHAR_NL.toString()
        val sb = StringBuilder()
        sb.append("এটি একটি মোবাইল স্ক্রিনের ছবি। ছবিটি ভালোভাবে দেখো।").append(nl)
        if (full) {
            sb.append("ছবিতে যত লেখা দেখা যাচ্ছে সব খুঁজে বের করে বাংলায় অনুবাদ করো।").append(nl)
        } else {
            sb.append("শুধু সেই লেখাগুলো খুঁজে বের করো যেগুলো ছবি, পোস্টার, লোগো, থাম্বনেইল বা গ্রাফিক্সের ভেতরে আঁকা আছে, তারপর বাংলায় অনুবাদ করো।").append(nl)
        }
        sb.append("প্রতিটি লেখার জন্য তার অবস্থান box_2d আকারে দাও, মান হবে [ymin,xmin,ymax,xmax] এবং 0 থেকে 1000 এর মধ্যে স্বাভাবিকীকৃত।").append(nl)
        sb.append("বক্সটি শুধু ওই লেখাটুকু ঘিরে থাকবে, পুরো ছবি নয়।").append(nl)
        sb.append("সর্বোচ্চ 22 টি আইটেম দাও। সংখ্যা, ঘড়ির সময়, ব্যাটারি বা সিগন্যাল আইকনের লেখা বাদ দাও।").append(nl)
        sb.append("কোনো ব্যাখ্যা দেবে না। শুধু এই JSON দাও: {items:[{translated_text:বাংলা,box_2d:[0,0,0,0]}]}").append(nl)
        if (known.isNotEmpty()) {
            sb.append("নিচের লেখাগুলো আগেই অনুবাদ হয়ে গেছে, এগুলো আর দেবে না:").append(nl)
            var count = 0
            for (k in known) {
                if (count >= 40) break
                var t = k.text
                if (t.length > 40) t = t.substring(0, 40)
                sb.append("- ").append(t).append(nl)
                count++
            }
        }
        return sb.toString()
    }

    private fun translateShot(b64: String, known: List<Item>, keys: List<String>,
                              models: List<String>): List<Item> {
        val full = known.size < 4
        val prompt = buildShotPrompt(known, full)
        val total = keys.size * models.size
        var attempts = 0
        while (attempts < total) {
            val key = keys[currentKeyIndex % keys.size]
            val model = models[currentModelIndex % models.size]
            saveCurrentCombo(key, model)
            val res = callGemini(key, model, prompt, b64)
            val body = res.first
            if (body != null) return parseShot(body)
            moveToNextCombo(keys.size, models.size)
            attempts++
            try {
                Thread.sleep(250)
            } catch (e: Exception) { }
        }
        return ArrayList()
    }

    private fun callGemini(key: String, model: String, prompt: String, imageB64: String?): Pair<String?, Int> {
        try {
            val parts = JSONArray()
            if (imageB64 != null) {
                val data = JSONObject()
                data.put("mime_type", "image/jpeg")
                data.put("data", imageB64)
                val inline = JSONObject()
                inline.put("inline_data", data)
                parts.put(inline)
            }
            val textPart = JSONObject()
            textPart.put("text", prompt)
            parts.put(textPart)

            val content = JSONObject()
            content.put("role", "user")
            content.put("parts", parts)
            val contents = JSONArray()
            contents.put(content)

            val gen = JSONObject()
            gen.put("temperature", 0.1)
            gen.put("maxOutputTokens", 8192)
            gen.put("responseMimeType", "application/json")

            val json = JSONObject()
            json.put("contents", contents)
            json.put("generationConfig", gen)

            val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                    model + ":generateContent?key=" + key
            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(requestBody).build()

            val response = client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string()
            response.close()

            if (code == 200 && body != null) {
                val jsonResponse = JSONObject(body)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val cand = candidates.optJSONObject(0)
                    val contentOut = cand?.optJSONObject("content")
                    val outParts = contentOut?.optJSONArray("parts")
                    if (outParts != null) {
                        val sb = StringBuilder()
                        for (i in 0 until outParts.length()) {
                            val part = outParts.optJSONObject(i) ?: continue
                            if (part.optBoolean("thought", false)) continue
                            val piece = part.optString("text", "")
                            if (piece.isNotBlank()) sb.append(piece)
                        }
                        val text = sb.toString()
                        if (text.isNotBlank()) return Pair(text, 200)
                    }
                }
                return Pair(null, 204)
            }
            return Pair(null, code)
        } catch (e: Exception) {
            return Pair(null, -1)
        }
    }

    private fun trimJson(s: String): String {
        var t = s.trim()
        val a = t.indexOf('{')
        val b = t.indexOf('[')
        var start = -1
        if (a < 0) {
            start = b
        } else if (b < 0) {
            start = a
        } else {
            start = if (a < b) a else b
        }
        if (start > 0) t = t.substring(start)
        val endCurly = t.lastIndexOf('}')
        val endSquare = t.lastIndexOf(']')
        val end = if (endCurly > endSquare) endCurly else endSquare
        if (end > 0 && end < t.length - 1) t = t.substring(0, end + 1)
        return t
    }

    private fun oneLine(s: String): String {
        var t = s.replace(CHAR_NL, ' ').replace(CHAR_CR, ' ').trim()
        if (t.length > 400) t = t.substring(0, 400)
        return t
    }

    private fun parseTranslations(body: String, chunk: List<Item>, out: HashMap<Int, String>): Int {
        var added = 0
        var arr: JSONArray? = null
        try {
            val txt = trimJson(body)
            if (txt.startsWith("[")) {
                arr = JSONArray(txt)
            } else {
                val o = JSONObject(txt)
                arr = o.optJSONArray("translations")
                if (arr == null) arr = o.optJSONArray("items")
                if (arr == null) arr = o.optJSONArray("data")
            }
        } catch (e: Exception) {
            arr = null
        }

        if (arr != null) {
            for (i in 0 until arr.length()) {
                val e = arr.opt(i)
                if (e == null || e === JSONObject.NULL) continue
                var value = ""
                var local = -1
                if (e is JSONObject) {
                    value = e.optString("translated_text", "")
                    if (value.isBlank()) value = e.optString("text", "")
                    if (value.isBlank()) value = e.optString("bangla", "")
                    local = e.optInt("id", -1)
                } else {
                    value = e.toString()
                }
                value = oneLine(value)
                if (value.isBlank()) continue
                if (local < 1 || local > chunk.size) local = i + 1
                if (local < 1 || local > chunk.size) continue
                val gid = chunk[local - 1].id
                if (!out.containsKey(gid)) {
                    out[gid] = value
                    added++
                }
            }
        }
        if (added < chunk.size) added += regexRescue(body, chunk, out)
        return added
    }

    private fun regexRescue(body: String, chunk: List<Item>, out: HashMap<Int, String>): Int {
        var added = 0
        try {
            val rx = Regex("\"id\"\\s*:\\s*(\\d+)[\\s\\S]{0,60}?\"translated_text\"\\s*:\\s*\"([^\"]*)\"")
            for (m in rx.findAll(body)) {
                val local = m.groupValues[1].toIntOrNull() ?: continue
                val value = oneLine(m.groupValues[2])
                if (value.isBlank()) continue
                if (local < 1 || local > chunk.size) continue
                val gid = chunk[local - 1].id
                if (!out.containsKey(gid)) {
                    out[gid] = value
                    added++
                }
            }
        } catch (e: Exception) { }
        return added
    }

    private fun parseShot(body: String): List<Item> {
        val out = ArrayList<Item>()
        try {
            var arr: JSONArray? = null
            val txt = trimJson(body)
            if (txt.startsWith("[")) {
                arr = JSONArray(txt)
            } else {
                val o = JSONObject(txt)
                arr = o.optJSONArray("items")
                if (arr == null) arr = o.optJSONArray("translations")
                if (arr == null) arr = o.optJSONArray("data")
            }
            if (arr == null) return out
            var id = 900000
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val tr = oneLine(e.optString("translated_text", ""))
                if (tr.isBlank()) continue
                var box = e.optJSONArray("box_2d")
                if (box == null) box = e.optJSONArray("box")
                if (box == null || box.length() < 4) continue
                val ymin = box.optDouble(0, -1.0)
                val xmin = box.optDouble(1, -1.0)
                val ymax = box.optDouble(2, -1.0)
                val xmax = box.optDouble(3, -1.0)
                if (ymin < 0.0 || xmin < 0.0 || ymax <= ymin || xmax <= xmin) continue
                var left = (xmin / 1000.0 * screenWidth).toInt()
                var top = (ymin / 1000.0 * screenHeight).toInt()
                var right = (xmax / 1000.0 * screenWidth).toInt()
                var bottom = (ymax / 1000.0 * screenHeight).toInt()
                if (left < 0) left = 0
                if (top < 0) top = 0
                if (right > screenWidth) right = screenWidth
                if (bottom > screenHeight) bottom = screenHeight
                if (right - left < minBoxWidth || bottom - top < minBoxHeight) continue
                val r = Rect(left, top, right, bottom)
                if (r.width() > screenWidth * 96 / 100 && r.height() > screenHeight / 2) continue
                var size = r.height().toFloat() * 0.62f
                val cap = 24f * resources.displayMetrics.density
                if (size > cap) size = cap
                out.add(Item(tr, r, size, true, id))
                id++
                if (out.size >= 22) break
            }
        } catch (e: Exception) { }
        return out
    }

    private fun showOverlay(list: List<OverlayView.Entry>) {
        try {
            overlayView?.setEntries(list)
            overlayView?.visibility = View.VISIBLE
            isOverlayShowing = true
            overlayShownAt = System.currentTimeMillis()
            isWorking = false
            buttonView?.visibility = View.VISIBLE
            setButtonColor(COLOR_DONE)
        } catch (e: Exception) {
            resetWork()
            toast(getString(R.string.msg_failed))
        }
    }

    private fun resetWork() {
        isWorking = false
        setButtonColor(COLOR_IDLE)
        syncUiState()
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { }
    }

    private fun saveError(msg: String) {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
            .putString("lastError", msg).apply()
    }

    private fun setButtonColor(hex: String) {
        try {
            buttonBg?.setColor(Color.parseColor(hex))
        } catch (e: Exception) { }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getKeys(): List<String> {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return listOf(
            prefs.getString("key1", "") ?: "",
            prefs.getString("key2", "") ?: "",
            prefs.getString("key3", "") ?: "",
            prefs.getString("key4", "") ?: "",
            prefs.getString("key5", "") ?: ""
        ).filter { it.isNotBlank() }
    }

    private fun getModels(): List<String> {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val models = mutableListOf<String>()
        if (prefs.getBoolean("model1", true)) models.add("gemini-3.5-flash-lite")
        if (prefs.getBoolean("model2", true)) models.add("gemini-3.1-flash-lite")
        if (prefs.getBoolean("model3", true)) models.add("gemini-3-flash-preview")
        return models
    }

    private fun saveCurrentCombo(key: String, model: String) {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit().apply {
            putString("currentKey", key.take(8) + "...")
            putString("currentModel", model)
            apply()
        }
    }

    private fun moveToNextCombo(keyCount: Int, modelCount: Int) {
        currentModelIndex++
        if (currentModelIndex >= modelCount) {
            currentModelIndex = 0
            currentKeyIndex = (currentKeyIndex + 1) % keyCount
        }
    }

    private fun shutdown() {
        uiReady = false
        removeViews()
        unregister()
        try {
            disableSelf()
        } catch (e: Exception) { }
    }

    private fun unregister() {
        try {
            receiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { }
        receiver = null
    }

    private fun removeViews() {
        try {
            buttonView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { }
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { }
        buttonView = null
        overlayView = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        uiReady = false
        handler.removeCallbacksAndMessages(null)
        removeViews()
        unregister()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        uiReady = false
        handler.removeCallbacksAndMessages(null)
        removeViews()
        unregister()
        super.onDestroy()
    }

    companion object {
        private val CHAR_NL = Char(10)
        private val CHAR_CR = Char(13)
        private const val MAX_NODES = 400
        private const val MAX_ITEMS = 100
        private const val CHUNK = 30
        private const val COLOR_IDLE = "#2196F3"
        private const val COLOR_BUSY = "#FF9800"
        private const val COLOR_DONE = "#4CAF50"
    }
}
