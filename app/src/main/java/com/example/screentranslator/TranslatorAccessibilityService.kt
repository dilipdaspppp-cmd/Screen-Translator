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
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OverlayView(context: Context) : View(context) {
    class Entry(val text: String, val rect: Rect, val srcSize: Float, val maxLines: Int)
    private class Drawn(val rect: Rect, val layout: StaticLayout, val pad: Float, val area: Long)
    private var drawn: List<Drawn> = emptyList()
    private val bgPaint = Paint().apply {
        color = Color.rgb(18, 18, 18)
        isAntiAlias = false
    }

    fun setEntries(list: List<Entry>) {
        val density = resources.displayMetrics.density
        val pad = 2f * density
        val minSize = 4f * density
        val out = ArrayList<Drawn>()
        for (en in list) {
            val w = en.rect.width()
            val h = en.rect.height()
            if (w <= 2 || h <= 2) continue
            val innerW = (w - pad * 2f).toInt()
            if (innerW <= 4) continue
            val innerH = (h - pad * 2f).toInt()
            if (innerH <= 2) continue
            val paint = TextPaint()
            paint.color = Color.WHITE
            paint.isAntiAlias = true
            var size = en.srcSize
            if (size < minSize) size = minSize
            paint.textSize = size
            val align = if (en.text.length <= 28) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
            var layout = buildLayout(en.text, paint, innerW, align, 0)
            var guard = 0
            while (layout.height > innerH && size > minSize && guard < 60) {
                size = size - 0.5f
                if (size < minSize) size = minSize
                paint.textSize = size
                layout = buildLayout(en.text, paint, innerW, align, 0)
                guard++
            }
            if (layout.height > innerH && en.maxLines > 0) {
                layout = buildLayout(en.text, paint, innerW, align, en.maxLines)
            }
            out.add(Drawn(Rect(en.rect), layout, pad, w.toLong() * h.toLong()))
        }
        drawn = out.sortedByDescending { it.area }
        invalidate()
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int,
                            align: Layout.Alignment, maxLines: Int): StaticLayout {
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
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
    private class Item(val text: String, val rect: Rect, val srcSize: Float, val fromImage: Boolean, val id: Int, val maxLines: Int = 6)
    private var overlayView: OverlayView? = null
    private var buttonView: TextView? = null
    private var buttonBg: GradientDrawable? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val measurePaint = TextPaint()
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

    private val comboLock = Any()
    private var comboBase = 0
    private val cooldown = HashMap<String, Long>()
    private val noThinking = HashSet<String>()
    private val session = AtomicInteger(0)
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > CACHE_MAX
        }
    }

    private val longPressRunnable = Runnable {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("uiOff", true).apply()
        syncUiState()
        toast(getString(R.string.msg_button_hidden))
    }

    private val textClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val imageClient = textClient.newBuilder()
        .readTimeout(55, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(70, TimeUnit.SECONDS)
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
            minBoxHeight = dpToPx(6)
            minBoxWidth = dpToPx(8)
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
        session.incrementAndGet()
        setButtonColor(COLOR_BUSY)
        buttonView?.visibility = View.GONE
        clearOverlay()
        handler.post { collectAndTranslate() }
    }

    private fun collectAndTranslate() {
        val mySession = session.get()
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
        val keys = getKeys()
        val models = getModels()
        if (keys.isEmpty() || models.isEmpty()) {
            saveError(getString(R.string.msg_no_key))
            resetWork()
            toast(getString(R.string.msg_no_key))
            return
        }
        val raw = ArrayList<Item>()
        if (root != null) {
            try {
                walk(root, raw, 0)
            } catch (e: Exception) { }
        }
        val appPkg = try { root?.packageName?.toString() ?: "" } catch (e: Exception) { "" }
        val cleaned = dedupe(filterParents(raw))
        val ordered = cleaned.sortedWith(Comparator { a, b ->
            if (a.rect.top != b.rect.top) a.rect.top - b.rect.top else a.rect.left - b.rect.left
        })
        val limited = if (ordered.size > MAX_ITEMS) ordered.subList(0, MAX_ITEMS) else ordered
        val items = ArrayList<Item>()
        for (i in limited.indices) {
            val s = limited[i]
            items.add(Item(s.text, Rect(s.rect), s.srcSize, false, i + 1))
        }

        var par = keys.size * 2
        if (par < 2) par = 2
        if (par > 6) par = 6
        val exec = Executors.newFixedThreadPool(par)

        val map: MutableMap<Int, String> = ConcurrentHashMap()
        val pending = ArrayList<Item>()
        for (it0 in items) {
            val c = cacheGet(it0.text)
            if (c != null) map[it0.id] = c else pending.add(it0)
        }
        val easy = ArrayList<Item>()
        val hardItems = ArrayList<Item>()
        for (p in pending) {
            if (isDeepText(p.text)) hardItems.add(p) else easy.add(p)
        }
        val size = pickChunk(easy.size, par)
        val futures = ArrayList<Future<Int>>()
        futures.addAll(submitChunks(exec, easy, size, keys, models, map, appPkg, 0, false))
        futures.addAll(submitChunks(exec, hardItems, HARD_CHUNK, keys, models, map, appPkg, futures.size, false))

        handler.postDelayed({
            captureScreen { shot ->
                if (items.isEmpty() && shot == null) {
                    try { exec.shutdownNow() } catch (e: Exception) { }
                    resetWork()
                    toast(getString(R.string.msg_no_text))
                } else {
                    buttonView?.visibility = View.VISIBLE
                    setButtonColor(COLOR_BUSY)
                    Thread { runJob(mySession, exec, items, map, futures, shot, keys, models, appPkg) }.start()
                }
            }
        }, 200)
    }

    private fun pickChunk(n: Int, par: Int): Int {
        if (n <= 0) return CHUNK_MIN
        var s = (n + par - 1) / par
        if (s < CHUNK_MIN) s = CHUNK_MIN
        if (s > CHUNK_MAX) s = CHUNK_MAX
        return s
    }

    private fun walk(node: AccessibilityNodeInfo, items: ArrayList<Item>, depth: Int) {
        if (items.size >= MAX_NODES || depth > 90) return
        try {
            val t = node.text?.toString()
            if (t != null) {
                var clean = t.replace(CHAR_NL, ' ').replace(CHAR_CR, ' ').trim()
                clean = stripEmojiTags(clean)
                if (clean.isNotEmpty() && node.isVisibleToUser && hasTranslatable(clean) && !isLiteral(clean) && !isLoneLatin(clean)) {
                    if (clean.length > 1200) clean = clean.substring(0, 1200)
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

    private fun collapseSpaces(s: String): String {
        val sb = StringBuilder()
        var prevSpace = false
        for (ch in s) {
            if (ch.isWhitespace()) {
                if (!prevSpace) sb.append(' ')
                prevSpace = true
            } else {
                sb.append(ch)
                prevSpace = false
            }
        }
        return sb.toString().trim()
    }

    private fun stripEmojiTags(s: String): String {
        if (s.indexOf('[') < 0) return s
        val sb = StringBuilder()
        var i = 0
        val n = s.length
        while (i < n) {
            val ch = s[i]
            if (ch == '[') {
                val close = s.indexOf(']', i + 1)
                val nextOpen = s.indexOf('[', i + 1)
                if (close > i + 1 && close - i - 1 <= 24 && (nextOpen < 0 || nextOpen > close)) {
                    i = close + 1
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return collapseSpaces(sb.toString())
    }

    private fun isLoneLatin(s: String): Boolean {
        if (s.length != 1) return false
        return s[0].code < 0x0250
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

    private fun hasBengali(s: String): Boolean {
        for (ch in s) {
            val c = ch.code
            if (c >= 0x0980 && c <= 0x09FF && Character.isLetter(ch)) return true
        }
        return false
    }

    private fun isUrlToken(tok: String): Boolean {
        if (tok.contains("http") || tok.contains("www.")) return true
        val at = tok.indexOf('@')
        return at > 0 && tok.indexOf('.', at) > at
    }

    private fun withoutUrls(s: String): String {
        val sb = StringBuilder()
        for (tok in s.split(' ')) {
            if (tok.isEmpty() || isUrlToken(tok)) continue
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(tok)
        }
        return sb.toString()
    }

    private fun isLiteral(s: String): Boolean {
        return !hasTranslatable(withoutUrls(s))
    }

    private fun foreignLeft(t: String): Boolean {
        val body = withoutUrls(t)
        var bn = 0
        var latin = 0
        for (ch in body) {
            if (!Character.isLetter(ch)) continue
            val c = ch.code
            if (c >= 0x0980 && c <= 0x09FF) {
                bn++
            } else if (c < 0x0250) {
                latin++
            } else {
                return true
            }
        }
        return latin * 3 > bn
    }

    private fun goodTr(src: String, tr: String): Boolean {
        val t = tr.trim()
        if (t.isEmpty()) return false
        if (isLiteral(src)) return true
        if (squash(t) == squash(src)) return false
        if (!hasBengali(t)) return false
        return !foreignLeft(t)
    }

    private fun softOk(src: String, tr: String): Boolean {
        val t = tr.trim()
        if (t.isEmpty()) return false
        if (squash(t) == squash(src)) return false
        return hasBengali(t)
    }

    private fun scriptClass(s: String): Int {
        var han = 0
        var kana = 0
        var hangul = 0
        var cyr = 0
        var arab = 0
        var uy = 0
        var thai = 0
        var deva = 0
        var heb = 0
        var greek = 0
        var latin = 0
        var other = 0
        for (ch in s) {
            if (!Character.isLetter(ch)) continue
            val c = ch.code
            if ((c in 0x4E00..0x9FFF) || (c in 0x3400..0x4DBF)) {
                han++
            } else if (c in 0x3040..0x30FF) {
                kana++
            } else if ((c in 0xAC00..0xD7AF) || (c in 0x1100..0x11FF) || (c in 0x3130..0x318F)) {
                hangul++
            } else if (c in 0x0400..0x052F) {
                cyr++
            } else if ((c in 0x0600..0x06FF) || (c in 0x0750..0x077F) || (c in 0xFB50..0xFDFF) || (c in 0xFE70..0xFEFF)) {
                arab++
                if (c == 0x06C6 || c == 0x06C7 || c == 0x06C8 || c == 0x06CB ||
                    c == 0x06D0 || c == 0x06AD || c == 0x06D5) {
                    uy++
                }
            } else if (c in 0x0E00..0x0E7F) {
                thai++
            } else if (c in 0x0900..0x097F) {
                deva++
            } else if (c in 0x0590..0x05FF) {
                heb++
            } else if (c in 0x0370..0x03FF) {
                greek++
            } else if (c < 0x0250) {
                latin++
            } else {
                other++
            }
        }
        if (uy > 0) return 6
        if (arab > 0) return 5
        if (kana > 0) return 2
        if (hangul > 0) return 3
        if (han > 0) return 1
        if (cyr > 0) return 4
        if (thai > 0) return 7
        if (deva > 0) return 8
        if (heb > 0) return 9
        if (greek > 0) return 10
        if (latin > 0) return 0
        if (other > 0) return 11
        return 0
    }

    private fun langName(c: Int): String {
        return when (c) {
            1 -> "Chinese"
            2 -> "Japanese"
            3 -> "Korean"
            4 -> "Cyrillic"
            5 -> "Arabic-script"
            6 -> "Uyghur"
            7 -> "Thai"
            8 -> "Devanagari"
            9 -> "Hebrew"
            10 -> "Greek"
            11 -> "Other-script"
            else -> "Latin"
        }
    }

    private fun isHardText(s: String): Boolean {
        return scriptClass(s) >= 4
    }

    private fun isDeepText(s: String): Boolean {
        val c = scriptClass(s)
        if (c >= 4) return true
        if (c in 1..3 && s.length <= 8) return true
        return false
    }

    private fun hasDeep(list: List<Item>): Boolean {
        for (x in list) {
            if (isDeepText(x.text)) return true
        }
        return false
    }

    private fun hasHan(list: List<Item>): Boolean {
        for (x in list) {
            val c = scriptClass(x.text)
            if (c == 1 || c == 2) return true
        }
        return false
    }

    private fun hasHard(list: List<Item>): Boolean {
        for (x in list) {
            if (isHardText(x.text)) return true
        }
        return false
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
        val flat = ArrayList<String>(raw.size)
        for (a in raw) {
            flat.add(squash(a.text))
        }
        val result = ArrayList<Item>()
        for (ai in raw.indices) {
            val a = raw[ai]
            val aFlat = flat[ai]
            var isParent = false
            for (bi in raw.indices) {
                if (ai == bi) continue
                val b = raw[bi]
                if (a.text.length <= b.text.length) continue
                if (flat[bi].length < 2) continue
                if (!a.rect.contains(b.rect)) continue
                if (aFlat.contains(flat[bi])) {
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

    private fun layoutBoxes(list: List<Item>): List<Item> {
        val d = resources.displayMetrics.density
        val gap = Math.max(2, (2f * d).toInt())
        val margin = Math.max(2, (3f * d).toInt())
        val hardMax = 26f * d
        val maxW = screenWidth - margin * 2
        val rightZone = screenWidth - dpToPx(24)
        val sorted = list.sortedWith(Comparator { a, b ->
            if (a.rect.top != b.rect.top) a.rect.top - b.rect.top else a.rect.left - b.rect.left
        })
        val placed = ArrayList<Item>()
        for (cand in sorted) {
            var size = cand.srcSize
            if (size > hardMax) size = hardMax
            val orig = cand.rect
            var boxW = orig.width()
            if (boxW > maxW) boxW = maxW
            var boxH = orig.height()
            if (boxH < minBoxHeight) boxH = minBoxHeight
            val rightAnchored = orig.right >= rightZone
            var left: Int
            if (rightAnchored) {
                left = orig.right - boxW
                if (left < margin) left = margin
            } else {
                left = orig.left
                if (left + boxW > screenWidth - margin) left = screenWidth - margin - boxW
                if (left < margin) left = margin
            }
            var top = orig.top
            if (top + boxH > screenHeight - margin) top = screenHeight - margin - boxH
            if (top < margin) top = margin
            val r = Rect(left, top, left + boxW, top + boxH)
            var guard2 = 0
            while (guard2 < 80) {
                guard2++
                var hit: Rect? = null
                for (p in placed) {
                    if (Rect.intersects(r, p.rect)) {
                        hit = p.rect
                        break
                    }
                }
                if (hit == null) break
                r.top = hit.bottom + gap
                r.bottom = r.top + boxH
                if (r.bottom > screenHeight - margin) {
                    r.bottom = screenHeight - margin
                    r.top = r.bottom - boxH
                    break
                }
            }
            placed.add(Item(cand.text, r, size, cand.fromImage, cand.id, BOX_MAX_LINES))
        }
        return placed
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
            val maxSide = 1440
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
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
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

    private fun submitChunks(exec: ExecutorService, list: List<Item>, size: Int, keys: List<String>,
                             models: List<String>, out: MutableMap<Int, String>, appPkg: String,
                             startOffset: Int, force: Boolean): List<Future<Int>> {
        val res = ArrayList<Future<Int>>()
        var i = 0
        var off = startOffset
        while (i < list.size) {
            var end = i + size
            if (end > list.size) end = list.size
            val chunk = ArrayList<Item>(list.subList(i, end))
            val o = off
            try {
                res.add(exec.submit(Callable<Int> { translateChunk(chunk, keys, models, out, appPkg, o, force) }))
            } catch (e: Exception) { }
            off++
            i = end
        }
        return res
    }

    private fun waitAll(futures: List<Future<Int>>): Int {
        var lastCode = 0
        for (f in futures) {
            val c = try {
                f.get(100, TimeUnit.SECONDS)
            } catch (e: Exception) {
                -1
            }
            if (c != 200) lastCode = c
        }
        return lastCode
    }

    private fun buildTextEntries(items: List<Item>, map: Map<Int, String>): List<Item> {
        val list = ArrayList<Item>()
        for (it0 in items) {
            val good = map[it0.id]
            val tr = good ?: map[-it0.id] ?: continue
            val clean = oneLine(tr)
            if (clean.isBlank()) continue
            if (squash(clean) == squash(it0.text)) continue
            if (good != null) cachePut(it0.text, clean)
            list.add(Item(clean, Rect(it0.rect), it0.srcSize, false, it0.id))
        }
        return list
    }

    private fun postDraw(mySession: Int, draw: List<OverlayView.Entry>) {
        handler.post {
            if (session.get() == mySession) {
                if (isWorking) {
                    showOverlay(draw)
                } else if (isOverlayShowing) {
                    try {
                        overlayView?.setEntries(draw)
                    } catch (e: Exception) { }
                }
            }
        }
    }

    private fun redrawNow(mySession: Int, lock: Any,
                          textRef: java.util.concurrent.atomic.AtomicReference<List<Item>>,
                          imageRef: java.util.concurrent.atomic.AtomicReference<List<Item>>): Boolean {
        var result = false
        synchronized(lock) {
            val texts = textRef.get()
            val images = imageRef.get()
            val all = if (images.isEmpty()) texts else mergeImage(texts, images)
            if (all.isNotEmpty()) {
                postDraw(mySession, toDraw(all))
                result = true
            }
        }
        return result
    }

    private fun runJob(mySession: Int, exec: ExecutorService, items: List<Item>,
                       map: MutableMap<Int, String>, futures: List<Future<Int>>, shot: String?,
                       keys: List<String>, models: List<String>, appPkg: String) {
        val textRef = java.util.concurrent.atomic.AtomicReference<List<Item>>(emptyList())
        val imageRef = java.util.concurrent.atomic.AtomicReference<List<Item>>(emptyList())
        val shownFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val drawLock = Any()
        var lastCode = 0
        try {
            var shotFuture: Future<Int>? = null
            if (shot != null) {
                val so = futures.size
                shotFuture = try {
                    exec.submit(Callable<Int> {
                        val res = translateShot(shot, items, keys, models, so)
                        imageRef.set(res)
                        if (res.isNotEmpty()) {
                            if (redrawNow(mySession, drawLock, textRef, imageRef)) shownFlag.set(true)
                        }
                        200
                    })
                } catch (e: Exception) {
                    null
                }
            }

            lastCode = waitAll(futures)

            var textEntries = buildTextEntries(items, map)
            textRef.set(textEntries)
            if (textEntries.isNotEmpty()) {
                saveError("")
                if (redrawNow(mySession, drawLock, textRef, imageRef)) shownFlag.set(true)
            }

            var round = 0
            while (round < 2) {
                val redo = ArrayList<Item>()
                for (it0 in items) {
                    if (map[it0.id] == null) redo.add(it0)
                }
                if (redo.isEmpty()) break
                val fix: MutableMap<Int, String> = ConcurrentHashMap()
                val sz = if (round == 0) RETRY_CHUNK else RETRY_CHUNK_2
                val c = waitAll(submitChunks(exec, redo, sz, keys, models, fix, appPkg, round + 1, true))
                if (c != 0 && textEntries.isEmpty()) lastCode = c
                var changed = false
                for (it0 in redo) {
                    val v = fix[it0.id]
                    if (v != null) {
                        val clean = oneLine(v)
                        if (clean.isNotBlank()) {
                            map[it0.id] = clean
                            changed = true
                        }
                    } else {
                        val sv = fix[-it0.id]
                        if (sv != null) {
                            val cleanSoft = oneLine(sv)
                            if (cleanSoft.isNotBlank()) {
                                map[-it0.id] = cleanSoft
                                changed = true
                            }
                        }
                    }
                }
                if (changed) {
                    textEntries = buildTextEntries(items, map)
                    textRef.set(textEntries)
                    if (textEntries.isNotEmpty()) {
                        saveError("")
                        if (redrawNow(mySession, drawLock, textRef, imageRef)) shownFlag.set(true)
                    }
                }
                round++
            }

            if (shotFuture != null) {
                try {
                    shotFuture.get(100, TimeUnit.SECONDS)
                } catch (e: Exception) { }
            }

            if (!shownFlag.get()) {
                if (lastCode != 0 && lastCode != 200) {
                    saveError("HTTP " + lastCode)
                    handler.post {
                        if (session.get() == mySession) {
                            resetWork()
                            toast(getString(R.string.msg_failed))
                        }
                    }
                } else {
                    saveError("")
                    handler.post {
                        if (session.get() == mySession) {
                            resetWork()
                            toast(getString(R.string.msg_no_text))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!shownFlag.get()) {
                handler.post {
                    if (session.get() == mySession) {
                        resetWork()
                        toast(getString(R.string.msg_failed))
                    }
                }
            }
        } finally {
            try {
                exec.shutdown()
            } catch (e: Exception) { }
        }
    }

    private fun mergeImage(textEntries: List<Item>, imageItems: List<Item>): List<Item> {
        val entries = ArrayList<Item>(textEntries)
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
        return entries
    }

    private fun toDraw(list: List<Item>): List<OverlayView.Entry> {
        val laid = layoutBoxes(list)
        val drawList = ArrayList<OverlayView.Entry>()
        for (e in laid) drawList.add(OverlayView.Entry(e.text, e.rect, e.srcSize, e.maxLines))
        return drawList
    }

    private fun cacheGet(text: String): String? {
        synchronized(cache) {
            return cache[text]
        }
    }

    private fun cachePut(text: String, tr: String) {
        if (squash(text) == squash(tr)) return
        if (!hasBengali(tr)) return
        synchronized(cache) {
            cache[text] = tr
        }
    }

    private fun comboOrder(keys: List<String>, models: List<String>, offset: Int): List<Int> {
        val total = keys.size * models.size
        val now = System.currentTimeMillis()
        val base = synchronized(comboLock) { ((comboBase % total) + total) % total }
        val first = base + (offset % keys.size) * models.size
        val ready = ArrayList<Int>()
        val cooled = ArrayList<Int>()
        for (i in 0 until total) {
            val idx = (first + i) % total
            val tag = keys[idx / models.size] + "|" + models[idx % models.size]
            val until = synchronized(comboLock) { cooldown[tag] ?: 0L }
            if (until > now) cooled.add(idx) else ready.add(idx)
        }
        ready.addAll(cooled)
        return ready
    }

    private fun markBad(idx: Int, keys: List<String>, models: List<String>, code: Int) {
        val total = keys.size * models.size
        val tag = keys[idx / models.size] + "|" + models[idx % models.size]
        val ms = when {
            code == 429 -> 60000L
            code == 401 || code == 403 || code == 404 -> 600000L
            code >= 500 -> 15000L
            else -> 20000L
        }
        synchronized(comboLock) {
            cooldown[tag] = System.currentTimeMillis() + ms
            if (((comboBase % total) + total) % total == idx) comboBase = (idx + 1) % total
        }
    }

    private fun translateChunk(chunk: List<Item>, keys: List<String>, models: List<String>,
                               out: MutableMap<Int, String>, appPkg: String, offset: Int,
                               force: Boolean): Int {
        if (chunk.isEmpty()) return 200
        val order = comboOrder(keys, models, offset)
        var lastCode = 0
        var remaining: List<Item> = chunk
        var okCalls = 0
        for (idx in order) {
            val key = keys[idx / models.size]
            val model = models[idx % models.size]
            val deep = force || hasDeep(remaining)
            val prompt = buildPrompt(remaining, appPkg, force)
            val level = if (deep) HARD_THINKING else TEXT_THINKING
            saveCurrentCombo(key, model)
            val res = callGemini(key, model, prompt, null, level)
            val body = res.first
            if (body != null) {
                val tmp: MutableMap<Int, String> = HashMap()
                parseTranslations(body, remaining, tmp)
                val still = ArrayList<Item>()
                for (it0 in remaining) {
                    val v = tmp[it0.id]
                    if (v != null && goodTr(it0.text, v)) {
                        out[it0.id] = v
                    } else {
                        if (v != null && softOk(it0.text, v)) out[-it0.id] = v
                        still.add(it0)
                    }
                }
                okCalls++
                lastCode = 204
                if (still.isEmpty()) return 200
                remaining = still
                if (okCalls >= 2) break
            } else {
                lastCode = res.second
                markBad(idx, keys, models, res.second)
            }
        }
        if (remaining.size < chunk.size) return 200
        return lastCode
    }

    private fun qq(s: String): String {
        return Char(34).toString() + s + Char(34)
    }

    private fun limitOf(x: Item): Int {
        val w = (x.rect.width() - 4).toFloat()
        val h = x.rect.height().toFloat()
        val sz = if (x.srcSize < 1f) 1f else x.srcSize
        var perLine = (w / (sz * 0.55f)).toInt()
        if (perLine < 1) perLine = 1
        var lines = (h / (sz * 1.25f)).toInt()
        if (lines < 1) lines = 1
        var cap = perLine * lines
        if (cap < 6) cap = 6
        return cap
    }

    private fun buildPrompt(chunk: List<Item>, appPkg: String, force: Boolean): String {
        val nl = CHAR_NL.toString()
        val hard = hasHard(chunk)
        val deep = force || hasDeep(chunk)
        val sb = StringBuilder()
        sb.append("You are an expert translator into Bengali (Bangla). Translate EVERY numbered text below into natural, fluent, easy Bengali (প্রাঞ্জল, সহজ, চলিত বাংলা), the way a native Bengali speaker naturally says it.").append(nl)
        if (appPkg.isNotBlank()) {
            sb.append("All texts are from one phone screen of the app ").append(appPkg).append(". Use this and the other items as context.").append(nl)
        }
        sb.append("Each line has the form: number | detected source language | text. Some lines have an extra field such as: max 6 chars, placed before the text. That means the box on screen is small, so write the Bengali in about that many characters or fewer, using the shortest natural word (for example খুঁজুন instead of অনুসন্ধান), but never at the cost of a wrong meaning. The language was detected from the writing system, so read the text as that language. Latin means English or another Latin-script language (Turkish, Spanish, French, Indonesian and so on): recognise it yourself. Arabic-script means Arabic, Persian, Urdu, Kazakh or Kyrgyz: recognise it yourself from the letters.").append(nl)
        sb.append("Rules:").append(nl)
        sb.append("1) EVERY letter, word and sentence in ANY language (Chinese, English, Uyghur, Arabic, Russian, Japanese, Korean, Hindi and all others) must be translated into Bengali. Nothing may be left in the original language. Never skip any item, even if it is a single word or a single character. Never return the original text unchanged. Every output must be written in Bengali script.").append(nl)
        sb.append("2) These must NOT be changed and must stay exactly in their place: symbols and punctuation (# @ & ? ! : ; - _ / + and similar), emojis, all digits (keep 0-9 exactly as they are, never turn them into Bengali digits), URLs and email addresses.").append(nl)
        sb.append("3) Hashtags: keep the # sign and translate the words after it into Bengali, with no space after the #, joining several words with an underscore. Example: #分享 = #শেয়ার. Mentions: keep the @ sign and write the name after it in Bengali script (by sound for names). Example: @Alex = @অ্যালেক্স.").append(nl)
        sb.append("4) Very short texts (one character, one word, tab names, button names, badges, counters with a word) must be translated too, by their meaning in a phone app, e.g. 新 = নতুন; 赞 = লাইক; 关注 = ফলো করুন; 更多 = আরও; Home = হোম.").append(nl)
        sb.append("5) Translate the meaning, not word by word, so it sounds smooth and natural. Use modern চলিত ভাষা with simple everyday words, never সাধু ভাষা.").append(nl)
        sb.append("6) English words that Bengali people commonly use, and names of people, apps, brands and places, must be written in Bengali script, e.g. লাইক, শেয়ার, কমেন্ট, সেটিংস, ভিডিও, ইউটিউব, গুগল, ফেসবুক.").append(nl)
        sb.append("7) Buttons and short labels: short and clear. Sentences: complete, correct Bengali grammar. Keep the original tone. Address the reader as আপনি.").append(nl)
        sb.append("8) Examples: You're all caught up = আপনি সব দেখে ফেলেছেন; What's on your mind? = আপনি কী ভাবছেন?; Not now = এখন না; Something went wrong = কিছু একটা সমস্যা হয়েছে.").append(nl)
        if (hasHan(chunk)) {
            sb.append("Common app words: 推荐 = প্রস্তাবিত; 关注 = ফলো করুন; 同城 = কাছাকাছি; 商城 = শপ; 直播 = লাইভ; 朋友 = বন্ধু; 消息 = মেসেজ; 我 = আমি; 首页 = হোম; 搜索 = খুঁজুন; 评论 = কমেন্ট; 分享 = শেয়ার; 点赞 = লাইক; 收藏 = সংরক্ষণ; 更多 = আরও; 设置 = সেটিংস.").append(nl)
        }
        if (hard) {
            sb.append("SPECIAL CARE: some texts are in a low-resource language such as Uyghur (a Turkic language written in Arabic script with letters like ئ ې ۇ ۆ ۈ ڭ ە ۋ; it can also be Kazakh or Kyrgyz written in Arabic script). It is NOT Arabic, Persian or Urdu. Read every word carefully, understand the real meaning in context, and never invent words or give an unrelated translation. Uyghur examples: ئىزدەش = খোঁজা; تەڭشەك = সেটিংস; ئاساسىي بەت = হোম পেজ.").append(nl)
        }
        if (deep) {
            sb.append("For every item first write its short English meaning in the field e, then translate that meaning into Bengali in the field b.").append(nl)
        }
        if (force) {
            sb.append("IMPORTANT: these items were wrongly left untranslated or only partly translated before. Translate every single word of each one into correct Bengali script now, so that no Chinese, English, Arabic or other foreign letters remain (only symbols, emojis, digits and URLs stay).").append(nl)
        }
        sb.append("Return exactly ").append(chunk.size).append(" items, i from 1 to ").append(chunk.size).append(", one item per numbered text.").append(nl)
        if (deep) {
            sb.append("Output only compact JSON, nothing else: {").append(qq("t")).append(":[{").append(qq("i")).append(":1,").append(qq("e")).append(":").append(qq("English meaning")).append(",").append(qq("b")).append(":").append(qq("বাংলা অনুবাদ")).append("}]}").append(nl)
        } else {
            sb.append("Output only compact JSON, nothing else: {").append(qq("t")).append(":[{").append(qq("i")).append(":1,").append(qq("b")).append(":").append(qq("বাংলা অনুবাদ")).append("}]}").append(nl)
        }
        sb.append("Texts:").append(nl)
        for (i in chunk.indices) {
            val x = chunk[i]
            sb.append(i + 1).append(" | ").append(langName(scriptClass(x.text))).append(" | ")
            val lim = limitOf(x)
            if (lim <= 16) sb.append("max ").append(lim).append(" chars | ")
            sb.append(x.text).append(nl)
        }
        return sb.toString()
    }

    private fun buildShotPrompt(known: List<Item>, full: Boolean): String {
        val nl = CHAR_NL.toString()
        val sb = StringBuilder()
        sb.append("This is a phone screenshot.").append(nl)
        if (full) {
            sb.append("Find all English or other non-Bengali text anywhere on the screen, including very small text and single words.").append(nl)
        } else {
            sb.append("Find only English or other non-Bengali text that appears inside images, photos, video frames, banners, watermarks, memes, posters or stylized graphics (not normal app UI text).").append(nl)
        }
        sb.append("Identify the language of each text carefully. For example Uyghur is written in Arabic script with letters like ئ ې ۇ ڭ ە ۋ and must not be confused with Arabic, Persian or Urdu. Understand the real meaning first, then translate it correctly.").append(nl)
        sb.append("Translate each into natural, fluent Bengali (প্রাঞ্জল, সহজ, চলিত বাংলা) by meaning, not word by word. Every letter and word of the text, in any language, must be written in Bengali script. Keep symbols (# @ & ? ! and similar), emojis, digits and numbers exactly as they are.").append(nl)
        sb.append("Group words that belong together (one line or one sentence block) into a single item.").append(nl)
        sb.append("For each item give box_2d as [ymin, xmin, ymax, xmax], normalized 0 to 1000, tightly covering the original text.").append(nl)
        sb.append("Skip text that is already Bengali. Return at most 30 items.").append(nl)
        sb.append("Output only JSON: {").append(qq("items")).append(":[{").append(qq("translated_text")).append(":").append(qq("...")).append(",").append(qq("box_2d")).append(":[0,0,0,0]}]}").append(nl)
        if (known.isNotEmpty()) {
            sb.append("These texts are already translated separately, skip them:").append(nl)
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
                              models: List<String>, offset: Int): List<Item> {
        val full = known.size < 4
        val prompt = buildShotPrompt(known, full)
        val order = comboOrder(keys, models, offset)
        for (idx in order) {
            val key = keys[idx / models.size]
            val model = models[idx % models.size]
            saveCurrentCombo(key, model)
            val res = callGemini(key, model, prompt, b64, SHOT_THINKING)
            val body = res.first
            if (body != null) return parseShot(body)
            markBad(idx, keys, models, res.second)
        }
        return ArrayList()
    }

    private fun callGemini(key: String, model: String, prompt: String, imageB64: String?, level: String): Pair<String?, Int> {
        val skip = synchronized(comboLock) { noThinking.contains(model) }
        if (skip) return rawCall(key, model, prompt, imageB64, null)
        val first = rawCall(key, model, prompt, imageB64, level)
        if (first.second == 400) {
            val second = rawCall(key, model, prompt, imageB64, null)
            if (second.second == 200 || second.second == 204) {
                synchronized(comboLock) { noThinking.add(model) }
            }
            return second
        }
        return first
    }

    private fun rawCall(key: String, model: String, prompt: String, imageB64: String?, level: String?): Pair<String?, Int> {
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
            gen.put("maxOutputTokens", 16384)
            gen.put("responseMimeType", "application/json")
            if (level != null) {
                val tc = JSONObject()
                tc.put("thinkingLevel", level)
                gen.put("thinkingConfig", tc)
            }
            val json = JSONObject()
            json.put("contents", contents)
            json.put("generationConfig", gen)
            val url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", key)
                .post(requestBody)
                .build()
            val c = if (imageB64 != null) imageClient else textClient
            c.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string()
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
            }
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
        if (t.length > 1500) t = t.substring(0, 1500)
        return t
    }

    private fun parseTranslations(body: String, chunk: List<Item>, out: MutableMap<Int, String>): Int {
        var added = 0
        var arr: JSONArray? = null
        try {
            val txt = trimJson(body)
            if (txt.startsWith("[")) {
                arr = JSONArray(txt)
            } else {
                val o = JSONObject(txt)
                arr = o.optJSONArray("t")
                if (arr == null) arr = o.optJSONArray("translations")
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
                    value = e.optString("b", "")
                    if (value.isBlank()) value = e.optString("translated_text", "")
                    if (value.isBlank()) value = e.optString("text", "")
                    if (value.isBlank()) value = e.optString("bangla", "")
                    local = e.optInt("i", -1)
                    if (local < 1) local = e.optInt("id", -1)
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

    private fun regexRescue(body: String, chunk: List<Item>, out: MutableMap<Int, String>): Int {
        var added = 0
        try {
            val q = Char(34).toString()
            val bs = Char(92).toString()
            val pat = "'(?:i|id)'~s*:~s*(~d+)[^{}]*?'(?:b|translated_text)'~s*:~s*'((?:[^'~~]|~~.)*)'"
            val rx = Regex(pat.replace(Char(39).toString(), q).replace(Char(126).toString(), bs))
            for (m in rx.findAll(body)) {
                val local = m.groupValues[1].toIntOrNull() ?: continue
                var value = m.groupValues[2].replace(bs + q, q).replace(bs + "n", " ")
                value = oneLine(value)
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
                if (out.size >= 30) break
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
        private const val MAX_NODES = 500
        private const val MAX_ITEMS = 200
        private const val CHUNK_MIN = 8
        private const val CHUNK_MAX = 30
        private const val HARD_CHUNK = 12
        private const val RETRY_CHUNK = 8
        private const val RETRY_CHUNK_2 = 4
        private const val BOX_MAX_LINES = 6
        private const val CACHE_MAX = 800
        private const val TEXT_THINKING = "minimal"
        private const val HARD_THINKING = "low"
        private const val SHOT_THINKING = "low"
        private const val COLOR_IDLE = "#2196F3"
        private const val COLOR_BUSY = "#FF9800"
        private const val COLOR_DONE = "#4CAF50"
    }
}
