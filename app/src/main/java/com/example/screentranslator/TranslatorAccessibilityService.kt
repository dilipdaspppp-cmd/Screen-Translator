package com.example.screentranslator

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class OverlayView(context: Context) : View(context) {
    class Entry(val text: String, val rect: Rect, val srcSize: Float)

    private class Drawn(val rect: Rect, val layout: StaticLayout, val pad: Float, val area: Long)

    private var drawn: List<Drawn> = emptyList()
    private var built = HashMap<String, Drawn>()
    private val bgPaint = Paint().apply {
        color = Color.rgb(18, 18, 18)
        isAntiAlias = false
    }

    fun setEntries(list: List<Entry>) {
        val density = resources.displayMetrics.density
        val minSize = MIN_TEXT_DP * density
        val out = ArrayList<Drawn>()
        val next = HashMap<String, Drawn>()
        for (en in list) {
            val r = en.rect
            val k = en.text + "#" + r.left + "," + r.top + "," + r.right + "," + r.bottom + "," + en.srcSize
            var d = built[k]
            if (d == null) {
                d = try {
                    makeDrawn(en, density, minSize)
                } catch (e: Throwable) {
                    null
                }
            }
            if (d == null) continue
            next[k] = d
            out.add(d)
        }
        built = next
        drawn = out.sortedByDescending { it.area }
        invalidate()
    }

    private fun paintAt(size: Float): TextPaint {
        val p = TextPaint()
        p.color = Color.WHITE
        p.isAntiAlias = true
        p.textSize = size
        return p
    }

    private fun makeDrawn(en: Entry, density: Float, minSize: Float): Drawn? {
        val w = en.rect.width()
        val h = en.rect.height()
        if (w <= 2 || h <= 2) return null
        var pad = density
        if (pad * 6f > w || pad * 4f > h) pad = 0f
        val innerW = (w - pad * 2f).toInt()
        val innerH = (h - pad * 2f).toInt()
        if (innerW < 2 || innerH < 2) return null
        val align = if (en.text.length <= 28) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        var top = en.srcSize
        if (top < minSize) top = minSize
        var layout = buildLayout(en.text, paintAt(top), innerW, align, 0)
        if (layout.height > innerH) {
            val smallest = buildLayout(en.text, paintAt(minSize), innerW, align, 0)
            if (smallest.height > innerH) {
                var lineH = smallest.height.toFloat()
                if (smallest.lineCount > 0) lineH = lineH / smallest.lineCount.toFloat()
                if (lineH < 1f) lineH = 1f
                var maxL = (innerH.toFloat() / lineH).toInt()
                if (maxL < 1) maxL = 1
                layout = buildLayout(en.text, paintAt(minSize), innerW, align, maxL)
            } else {
                var lo = minSize
                var hi = top
                var best = smallest
                var guard = 0
                while (guard < 7 && hi - lo > 0.5f) {
                    val mid = (lo + hi) / 2f
                    val l = buildLayout(en.text, paintAt(mid), innerW, align, 0)
                    if (l.height <= innerH) {
                        lo = mid
                        best = l
                    } else {
                        hi = mid
                    }
                    guard++
                }
                layout = best
            }
        }
        return Drawn(Rect(en.rect), layout, pad, w.toLong() * h.toLong())
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int,
                            align: Layout.Alignment, maxLines: Int): StaticLayout {
        val b = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
        if (maxLines > 0) {
            b.setMaxLines(maxLines)
            b.setEllipsize(TextUtils.TruncateAt.END)
        }
        return b.build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawn.isEmpty()) return
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        for (d in drawn) {
            val l = (d.rect.left - loc[0]).toFloat()
            val t = (d.rect.top - loc[1]).toFloat()
            val r = (d.rect.right - loc[0]).toFloat()
            val b = (d.rect.bottom - loc[1]).toFloat()
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

    companion object {
        private const val MIN_TEXT_DP = 2.5f
    }
}

class TranslatorAccessibilityService : AccessibilityService() {

    private class Found(val text: String, val rect: Rect, val node: AccessibilityNodeInfo?)

    private class ScreenRead(val found: List<Found>, val appPkg: String, val ownApp: Boolean, val media: Boolean)

    private class Item(val text: String, val rect: Rect, size: Float, val node: AccessibilityNodeInfo?) {
        @Volatile var srcSize = size
        @Volatile var dead = false
    }

    private class ImgEntry(val text: String, val rect: Rect, val srcSize: Float)

    private class Combo(val key: String, val model: String) {
        val tag: String = key + "|" + model
    }

    private class CallSlot {
        @Volatile var call: Call? = null
        @Volatile var cancelled = false

        fun cancel() {
            cancelled = true
            try {
                call?.cancel()
            } catch (e: Throwable) { }
        }
    }

    private class Attempt(val combo: Combo, val startAt: Long) {
        val slot = CallSlot()
        @Volatile var lines = 0
        @Volatile var lastAt = startAt
        @Volatile var done = false
        @Volatile var code = 0
        @Volatile var body: String? = null
        @Volatile var recorded = false
    }

    private class GroupResult(val lines: Int, val completed: Boolean)

    private class WalkCtx(val out: ArrayList<Found>, val screen: Rect, val covers: List<Rect>,
                          val full: Boolean, val screenArea: Long, val screenH: Int) {
        var visited = 0
        var media = false
    }

    private class Job(val startAt: Long, val deadline: Long, val keys: List<String>,
                      val models: List<String>, val auto: Boolean, val turn: Int) {
        val cancelled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val slots: MutableList<CallSlot> = Collections.synchronizedList(ArrayList<CallSlot>())
        val good = ConcurrentHashMap<String, String>()
        val soft = ConcurrentHashMap<String, String>()
        val images: MutableList<ImgEntry> = Collections.synchronizedList(ArrayList<ImgEntry>())
        val badCombos: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val lines = AtomicInteger(0)
        @Volatile var items: List<Item> = emptyList()
        @Volatile var limits: Map<String, Int> = emptyMap()
        @Volatile var itemsReady = false
        @Volatile var appPkg = ""
        @Volatile var drawGate = false
        @Volatile var lastCode = 0
        @Volatile var saw429 = false
        @Volatile var endReason = 0
        @Volatile var eventSeen = false

        fun alive(): Boolean {
            return !cancelled.get() && !finished.get() && SystemClock.uptimeMillis() < deadline
        }
    }

    private var overlayView: OverlayView? = null
    private var buttonView: TextView? = null
    private var buttonBg: GradientDrawable? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var screenWidth = 1
    @Volatile private var screenHeight = 1
    @Volatile private var minBoxHeight = 1
    @Volatile private var minBoxWidth = 1
    private var buttonSize = 1
    private var uiReady = false
    private var receiver: BroadcastReceiver? = null

    private var state = ST_IDLE
    private var job: Job? = null
    private var capturing = false
    private var captureStartedAt = 0L
    private var verifyScheduled = false
    private var verifyBusy = false
    private var verifyStartedAt = 0L
    private var verifyAgain = false
    private val keyTurn = AtomicInteger(0)
    private val drawPending = AtomicBoolean(false)
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    private val comboLock = Any()
    private val cooldown = HashMap<String, Long>()
    private val levelFix = HashMap<String, String>()
    private val speedLock = Any()
    private val speedVal = HashMap<String, Double>()
    private val speedAt = HashMap<String, Long>()
    private val firstLat = HashMap<String, Double>()
    @Volatile private var lastComboSaved = ""
    @Volatile private var cacheDirty = false
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > CACHE_MAX
        }
    }

    @Volatile private var lastNetAt = 0L
    private val jsonType = "application/json".toMediaType()
    private val streamClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 10, TimeUnit.MINUTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val imageClient = streamClient.newBuilder()
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val longPressRunnable = Runnable {
        prefs().edit().putBoolean("uiOff", true).apply()
        syncUiState()
        toast(getString(R.string.msg_button_hidden))
    }

    private val verifyRunnable = Runnable { runVerify() }

    private val drawRunnable = Runnable {
        drawPending.set(false)
        doDraw()
    }

    // ---------------------------------------------------------------------
    // Service life cycle
    // ---------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        capturing = false
        drawPending.set(false)
        verifyScheduled = false
        verifyBusy = false
        if (job == null) state = ST_IDLE
        loadSpeeds()
        runBg { loadCache() }
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
        clearAll()
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
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) return
        val pkg = event.packageName?.toString() ?: ""
        val own = pkg == packageName
        if (own && type == AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        if (state != ST_IDLE) {
            val j = job
            if (j != null && !j.itemsReady) {
                j.eventSeen = true
            } else {
                scheduleVerify()
            }
        }
        if (!own) syncUiState()
    }

    override fun onInterrupt() { }

    // ---------------------------------------------------------------------
    // Floating button and overlay windows
    // ---------------------------------------------------------------------

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
        if (state == ST_BUSY) {
            val j = job
            if (j == null || SystemClock.uptimeMillis() - j.startAt >= CANCEL_AFTER_MS) {
                clearAll()
                toast(getString(R.string.msg_cancelled))
            } else {
                toast(getString(R.string.msg_wait))
            }
            return
        }
        if (state == ST_SHOWN) {
            clearAll()
            return
        }
        startJob()
    }

    private fun clearOverlay() {
        try {
            overlayView?.setEntries(emptyList())
            overlayView?.visibility = View.INVISIBLE
        } catch (e: Throwable) { }
    }

    private fun clearAll() {
        val j = job
        job = null
        if (j != null) cancelJob(j)
        handler.removeCallbacks(verifyRunnable)
        verifyScheduled = false
        verifyAgain = false
        clearOverlay()
        state = ST_IDLE
        setButtonColor(COLOR_IDLE)
        syncUiState()
    }

    private fun syncUiState() {
        if (!uiReady) return
        val off = prefs().getBoolean("uiOff", false)
        if (off) {
            val j = job
            if (j != null) {
                job = null
                cancelJob(j)
            }
            state = ST_IDLE
            setButtonColor(COLOR_IDLE)
            buttonView?.visibility = View.GONE
            clearOverlay()
        } else {
            if (capturing && SystemClock.uptimeMillis() - captureStartedAt > 6000L) capturing = false
            if (!capturing) buttonView?.visibility = View.VISIBLE
        }
    }

    // ---------------------------------------------------------------------
    // One translation job (one tap)
    // ---------------------------------------------------------------------

    private fun startJob() {
        if (!uiReady) return
        refreshMetrics()
        val keys = getKeys()
        val auto = prefs().getBoolean("autoModel", true)
        val models = getModels(auto)
        if (keys.isEmpty() || models.isEmpty()) {
            saveError(getString(R.string.msg_no_key))
            toast(getString(R.string.msg_no_key))
            return
        }
        val now = SystemClock.uptimeMillis()
        val j = Job(now, now + JOB_DEADLINE_MS, keys, models, auto, keyTurn.getAndIncrement())
        job = j
        state = ST_BUSY
        clearOverlay()
        setButtonColor(COLOR_BUSY)
        handler.postDelayed({ watchdog(j) }, JOB_DEADLINE_MS + WATCHDOG_EXTRA_MS)
        if (System.currentTimeMillis() - lastNetAt > WARM_AFTER_MS) runBg { warmConnection() }
        val ok = runBg {
            try {
                runJob(j)
            } catch (t: Throwable) { }
            handler.post { finishJob(j) }
        }
        if (!ok) finishJob(j)
    }

    private fun watchdog(j: Job) {
        if (j.finished.get()) return
        cancelSlots(j)
        finishJob(j)
    }

    private fun cancelJob(j: Job) {
        j.cancelled.set(true)
        cancelSlots(j)
    }

    private fun cancelSlots(j: Job) {
        val copy = synchronized(j.slots) { ArrayList<CallSlot>(j.slots) }
        for (s in copy) s.cancel()
    }

    private fun runJob(j: Job) {
        val read = readScreen(true)
        if (!j.alive()) return
        if (read == null) {
            j.endReason = END_NO_SCREEN
            return
        }
        if (read.ownApp) {
            j.endReason = END_OWN_APP
            return
        }
        j.appPkg = read.appPkg
        val items = buildItems(read.found)
        val uniq = LinkedHashSet<String>()
        val limits = HashMap<String, Int>()
        for (it0 in items) {
            uniq.add(it0.text)
            val lim = limitOf(it0)
            val old = limits[it0.text]
            if (old == null || lim < old) limits[it0.text] = lim
        }
        for (t in uniq) {
            val c = cacheGet(t)
            if (c != null) j.good[t] = c
        }
        j.limits = limits
        j.items = items
        j.itemsReady = true
        val needShot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (items.size < 4 || read.media)
        val imageLatch = CountDownLatch(if (needShot) 1 else 0)
        if (needShot) {
            handler.post { captureForJob(j, imageLatch) }
            handler.postDelayed({ openGate(j) }, DRAW_GATE_MS)
        } else {
            handler.post { openGate(j) }
        }
        if (j.eventSeen) handler.post { scheduleVerify() }
        if (items.isEmpty() && !needShot) {
            j.endReason = END_NO_TEXT
            return
        }
        runBg { refineSizes(j) }
        val pending = ArrayList<String>()
        for (t in uniq) {
            if (!j.good.containsKey(t)) pending.add(t)
        }
        if (pending.isNotEmpty()) {
            val normal = ArrayList<String>()
            val hard = ArrayList<String>()
            for (t in pending) {
                if (isHardText(t)) hard.add(t) else normal.add(t)
            }
            val parts = ArrayList<List<String>>()
            val strongFlags = ArrayList<Boolean>()
            for (p in splitParts(normal, PART_ITEMS, PART_CHARS, MAX_PARTS)) {
                parts.add(p)
                strongFlags.add(false)
            }
            for (p in splitParts(hard, HARD_PART_ITEMS, HARD_PART_CHARS, 2)) {
                parts.add(p)
                strongFlags.add(true)
            }
            val latch = CountDownLatch(parts.size)
            for (i in parts.indices) {
                val p = parts[i]
                val strong = strongFlags[i]
                val started = runBg {
                    try {
                        runTextTask(j, p, strong, false, i)
                    } finally {
                        latch.countDown()
                    }
                }
                if (!started) latch.countDown()
            }
            awaitLatch(latch, j)
            var round = 0
            while (round < FIX_ROUNDS && j.alive() && j.lines.get() > 0) {
                val left = ArrayList<String>()
                for (t in uniq) {
                    if (left.size >= FIX_MAX) break
                    if (!j.good.containsKey(t) && !allDead(j, t)) left.add(t)
                }
                if (left.isEmpty()) break
                runTextTask(j, left, true, true, 20 + round)
                round++
            }
        }
        awaitLatch(imageLatch, j)
    }

    private fun allDead(j: Job, text: String): Boolean {
        for (it0 in j.items) {
            if (it0.text == text && !it0.dead) return false
        }
        return true
    }

    private fun awaitLatch(latch: CountDownLatch, j: Job) {
        while (j.alive()) {
            try {
                if (latch.await(100, TimeUnit.MILLISECONDS)) return
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun splitParts(list: List<String>, maxItems: Int, maxChars: Int, maxParts: Int): List<List<String>> {
        val out = ArrayList<List<String>>()
        if (list.isEmpty()) return out
        var chars = 0
        for (t in list) chars += t.length
        var n = (list.size + maxItems - 1) / maxItems
        val byChars = (chars + maxChars - 1) / maxChars
        if (byChars > n) n = byChars
        if (n < 1) n = 1
        if (n > maxParts) n = maxParts
        if (n > list.size) n = list.size
        val size = (list.size + n - 1) / n
        var i = 0
        while (i < list.size) {
            var end = i + size
            if (end > list.size) end = list.size
            out.add(ArrayList(list.subList(i, end)))
            i = end
        }
        return out
    }

    private fun finishJob(j: Job) {
        if (!j.finished.compareAndSet(false, true)) return
        cancelSlots(j)
        runBg { saveCache() }
        if (job !== j || j.cancelled.get()) return
        j.drawGate = true
        doDraw()
        if (state == ST_SHOWN) {
            syncUiState()
            return
        }
        job = null
        state = ST_IDLE
        clearOverlay()
        setButtonColor(COLOR_IDLE)
        syncUiState()
        when (j.endReason) {
            END_OWN_APP -> toast(getString(R.string.msg_own_app))
            END_NO_SCREEN -> toast(getString(R.string.msg_no_screen))
            END_NO_TEXT -> {
                saveError("")
                toast(getString(R.string.msg_no_text))
            }
            else -> {
                val code = j.lastCode
                val noImages = synchronized(j.images) { j.images.isEmpty() }
                if (j.items.isEmpty() && noImages && (code == 0 || code == 200)) {
                    saveError("")
                    toast(getString(R.string.msg_no_text))
                } else if (j.saw429 && j.lines.get() == 0) {
                    saveError("HTTP 429")
                    toast(getString(R.string.msg_quota))
                } else if (code == -1) {
                    saveError(getString(R.string.err_net))
                    toast(getString(R.string.msg_failed))
                } else if (code != 0 && code != 200) {
                    saveError("HTTP " + (if (code == 4290) 429 else code))
                    toast(getString(R.string.msg_failed))
                } else {
                    toast(getString(R.string.msg_failed))
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private fun openGate(j: Job) {
        j.drawGate = true
        requestDraw()
    }

    private fun requestDraw() {
        if (drawPending.compareAndSet(false, true)) {
            handler.postDelayed(drawRunnable, DRAW_THROTTLE_MS)
        }
    }

    private fun doDraw() {
        val j = job ?: return
        if (j.cancelled.get() || !j.drawGate) return
        if (state == ST_IDLE) return
        val list = ArrayList<OverlayView.Entry>()
        for (it0 in j.items) {
            if (it0.dead) continue
            val tr = j.good[it0.text] ?: j.soft[it0.text] ?: continue
            list.add(OverlayView.Entry(tr, it0.rect, it0.srcSize))
        }
        synchronized(j.images) {
            for (im in j.images) list.add(OverlayView.Entry(im.text, im.rect, im.srcSize))
        }
        if (list.isEmpty()) {
            if (state == ST_SHOWN) {
                if (j.finished.get()) {
                    clearAll()
                } else {
                    clearOverlay()
                    state = ST_BUSY
                    setButtonColor(COLOR_BUSY)
                }
            }
            return
        }
        try {
            overlayView?.setEntries(list)
            overlayView?.visibility = View.VISIBLE
        } catch (e: Throwable) {
            return
        }
        if (state == ST_BUSY) {
            state = ST_SHOWN
            setButtonColor(COLOR_DONE)
            saveError("")
        }
        syncUiState()
    }

    // ---------------------------------------------------------------------
    // Checking whether the translated texts are still on screen
    // ---------------------------------------------------------------------

    private fun scheduleVerify() {
        if (verifyScheduled) return
        verifyScheduled = true
        handler.postDelayed(verifyRunnable, VERIFY_DELAY_MS)
    }

    private fun runVerify() {
        verifyScheduled = false
        val j = job ?: return
        if (state == ST_IDLE || j.cancelled.get()) return
        if (!j.itemsReady) {
            j.eventSeen = true
            return
        }
        val now = SystemClock.uptimeMillis()
        if (verifyBusy && now - verifyStartedAt < 5000L) {
            verifyAgain = true
            return
        }
        verifyBusy = true
        verifyStartedAt = now
        val ok = runBg {
            var read: ScreenRead? = null
            try {
                read = readScreen(false)
            } catch (t: Throwable) { }
            val r = read
            handler.post { applyVerify(j, r) }
        }
        if (!ok) verifyBusy = false
    }

    private fun applyVerify(j: Job, read: ScreenRead?) {
        verifyBusy = false
        if (job === j && !j.cancelled.get() && state != ST_IDLE && read != null) {
            if (read.ownApp) {
                clearAll()
                return
            }
            val map = HashMap<String, ArrayList<Rect>>()
            for (f in read.found) {
                var l = map[f.text]
                if (l == null) {
                    l = ArrayList()
                    map[f.text] = l
                }
                l.add(f.rect)
            }
            var total = 0
            var dead = 0
            var gone = 0
            for (it0 in j.items) {
                total++
                if (it0.dead) {
                    dead++
                    continue
                }
                if (!stillThere(it0, map)) {
                    it0.dead = true
                    dead++
                    gone++
                }
            }
            val hasImages = synchronized(j.images) { j.images.isNotEmpty() }
            if (total == 0) {
                if (hasImages) {
                    clearAll()
                    return
                }
            } else if (dead >= total || dead * 10 >= total * 7) {
                clearAll()
                return
            } else if (gone > 0) {
                if (hasImages && dead * 2 >= total) {
                    synchronized(j.images) { j.images.clear() }
                }
                requestDraw()
            }
        }
        if (verifyAgain && job != null) {
            verifyAgain = false
            scheduleVerify()
        }
    }

    private fun stillThere(it0: Item, map: HashMap<String, ArrayList<Rect>>): Boolean {
        val rs = map[it0.text] ?: return false
        for (r in rs) {
            if (abs(r.left - it0.rect.left) <= 4 && abs(r.top - it0.rect.top) <= 4 &&
                abs(r.right - it0.rect.right) <= 4 && abs(r.bottom - it0.rect.bottom) <= 4
            ) return true
        }
        return false
    }

    // ---------------------------------------------------------------------
    // Reading the screen
    // ---------------------------------------------------------------------

    private fun readScreen(full: Boolean): ScreenRead? {
        val sw = screenWidth
        val sh = screenHeight
        val screen = Rect(0, 0, sw, sh)
        val area = sw.toLong() * sh.toLong()
        val out = ArrayList<Found>()
        var appPkg = ""
        var ownApp = false
        var media = false
        var any = false
        val list = try {
            windows
        } catch (e: Throwable) {
            null
        }
        val wins = ArrayList<AccessibilityWindowInfo>()
        if (list != null) {
            for (w in list) {
                if (w != null) wins.add(w)
            }
        }
        wins.sortWith(Comparator { a, b -> b.layer - a.layer })
        val covers = ArrayList<Rect>()
        var topApp = true
        for (w in wins) {
            if (out.size >= MAX_FOUND) break
            val type = w.type
            val b = Rect()
            w.getBoundsInScreen(b)
            if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                covers.add(Rect(b))
                continue
            }
            val readable = type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    (type == AccessibilityWindowInfo.TYPE_SYSTEM && b.height() >= sh / 4)
            if (!readable) continue
            val root = try {
                w.root
            } catch (e: Throwable) {
                null
            }
            if (root == null) {
                covers.add(Rect(b))
                continue
            }
            val pkg = root.packageName?.toString() ?: ""
            if (type == AccessibilityWindowInfo.TYPE_APPLICATION && topApp) {
                topApp = false
                appPkg = pkg
                if (pkg == packageName) ownApp = true
            }
            if (pkg == packageName) {
                covers.add(Rect(b))
                continue
            }
            any = true
            val ctx = WalkCtx(out, screen, ArrayList(covers), full, area, sh)
            walkNode(root, ctx, 0)
            if (ctx.media) media = true
            covers.add(Rect(b))
        }
        if (!any && !ownApp) {
            val root = try {
                rootInActiveWindow
            } catch (e: Throwable) {
                null
            }
            if (root != null) {
                val pkg = root.packageName?.toString() ?: ""
                if (pkg == packageName) {
                    ownApp = true
                } else {
                    appPkg = pkg
                    any = true
                    val ctx = WalkCtx(out, screen, emptyList(), full, area, sh)
                    walkNode(root, ctx, 0)
                    if (ctx.media) media = true
                }
            }
        }
        if (!any && !ownApp) return null
        return ScreenRead(out, appPkg, ownApp, media)
    }

    private fun walkNode(node: AccessibilityNodeInfo, ctx: WalkCtx, depth: Int): Int {
        if (ctx.visited >= MAX_VISIT || ctx.out.size >= MAX_FOUND || depth > 90) return 0
        ctx.visited++
        var flags = 0
        try {
            if (!node.isVisibleToUser) return 0
            val cls = node.className?.toString() ?: ""
            val isImage = cls.contains("Image")
            val r = Rect()
            node.getBoundsInScreen(r)
            var own: String? = null
            val t = node.text?.toString()
            if (t != null && t.isNotBlank()) {
                own = t
            } else if (Build.VERSION.SDK_INT >= 26 && node.isEditable) {
                val hint = node.hintText?.toString()
                if (hint != null && hint.isNotBlank()) own = hint
            }
            if (own != null) {
                flags = flags or 1
                addFound(own, r, ctx, if (ctx.full) node else null)
            }
            val count = node.childCount
            for (i in 0 until count) {
                if (ctx.visited >= MAX_VISIT || ctx.out.size >= MAX_FOUND) break
                val child = try {
                    node.getChild(i)
                } catch (e: Throwable) {
                    null
                }
                if (child != null) flags = flags or walkNode(child, ctx, depth + 1)
            }
            var described = false
            if (own == null && (flags and 3) == 0 && !isImage) {
                val d = node.contentDescription?.toString()
                if (d != null && d.isNotBlank()) {
                    described = true
                    flags = flags or 1
                    if (descAllowed(cls, r, ctx)) addFound(d, r, ctx, null)
                }
            }
            if (ctx.full && !ctx.media) {
                val vis = Rect(r)
                if (vis.intersect(ctx.screen)) {
                    val a = vis.width().toLong() * vis.height().toLong()
                    val big = a * 100L >= ctx.screenArea * 5L
                    if (big && (isImage || cls.contains("Video") || cls.contains("Surface") ||
                                cls.contains("Texture") || cls.contains("Player"))
                    ) {
                        ctx.media = true
                    } else if (count == 0 && own == null && !described && a * 100L >= ctx.screenArea * 15L &&
                        (cls == "android.view.View" || cls.endsWith("FrameLayout"))
                    ) {
                        ctx.media = true
                    }
                }
            }
            if (isImage) flags = flags or 2
        } catch (e: Throwable) { }
        return flags
    }

    private fun descAllowed(cls: String, r: Rect, ctx: WalkCtx): Boolean {
        if (cls.contains("WebView") || cls.contains("ProgressBar") || cls.contains("SeekBar") ||
            cls.contains("RatingBar") || cls.contains("Video") || cls.contains("Surface") ||
            cls.contains("Texture") || cls.contains("EditText") || cls.contains("RecyclerView") ||
            cls.contains("ListView") || cls.contains("ScrollView") || cls.contains("ViewPager") ||
            cls.contains("GridView")
        ) return false
        val vis = Rect(r)
        if (!vis.intersect(ctx.screen)) return false
        val a = vis.width().toLong() * vis.height().toLong()
        if (a * 100L > ctx.screenArea * 30L) return false
        if (vis.height() * 10 > ctx.screenH * 4) return false
        return true
    }

    private fun addFound(raw: String, bounds: Rect, ctx: WalkCtx, node: AccessibilityNodeInfo?) {
        var clean = cleanText(raw)
        if (clean.isEmpty()) return
        if (!hasTranslatable(clean) || isLiteral(clean) || isLoneLatin(clean)) return
        if (clean.length > MAX_TEXT_LEN) clean = clean.substring(0, MAX_TEXT_LEN)
        val r = Rect(bounds)
        if (!r.intersect(ctx.screen)) return
        if (r.width() < minBoxWidth || r.height() < minBoxHeight) return
        if (isCovered(r, ctx.covers)) return
        ctx.out.add(Found(clean, r, node))
    }

    private fun isCovered(r: Rect, covers: List<Rect>): Boolean {
        if (covers.isEmpty()) return false
        val area = r.width().toLong() * r.height().toLong()
        if (area <= 0L) return true
        for (c in covers) {
            val left = if (r.left > c.left) r.left else c.left
            val top = if (r.top > c.top) r.top else c.top
            val right = if (r.right < c.right) r.right else c.right
            val bottom = if (r.bottom < c.bottom) r.bottom else c.bottom
            if (right <= left || bottom <= top) continue
            val inter = (right - left).toLong() * (bottom - top).toLong()
            if (inter * 2L > area) return true
        }
        return false
    }

    private fun cleanText(raw: String): String {
        val t = raw.replace(CHAR_NL, ' ').replace(CHAR_CR, ' ').trim()
        if (t.isEmpty()) return t
        return stripEmojiTags(t)
    }

    private fun buildItems(found: List<Found>): List<Item> {
        val kept = dedupe(filterParents(found))
        val ordered = kept.sortedWith(Comparator { a, b ->
            if (a.rect.top != b.rect.top) a.rect.top - b.rect.top else a.rect.left - b.rect.left
        })
        val limited = if (ordered.size > MAX_ITEMS) ordered.subList(0, MAX_ITEMS) else ordered
        val paint = TextPaint()
        val out = ArrayList<Item>()
        for (f in limited) {
            out.add(Item(f.text, Rect(f.rect), estimateSize(f.text, f.rect, paint), f.node))
        }
        return out
    }

    private fun containsLoose(outer: Rect, inner: Rect): Boolean {
        return inner.left >= outer.left - 2 && inner.top >= outer.top - 2 &&
                inner.right <= outer.right + 2 && inner.bottom <= outer.bottom + 2
    }

    private fun filterParents(raw: List<Found>): List<Found> {
        val n = raw.size
        if (n < 2) return raw
        val flat = Array(n) { squash(raw[it].text) }
        val drop = BooleanArray(n)
        val kids = ArrayList<Int>()
        for (ai in 0 until n) {
            val a = raw[ai]
            kids.clear()
            for (bi in 0 until n) {
                if (bi == ai) continue
                if (flat[bi].isEmpty()) continue
                if (flat[ai].length <= flat[bi].length) continue
                if (!containsLoose(a.rect, raw[bi].rect)) continue
                if (!flat[ai].contains(flat[bi])) continue
                kids.add(bi)
            }
            if (kids.isEmpty()) continue
            var cover = 0
            for (k in kids) {
                var inner = false
                for (k2 in kids) {
                    if (k2 == k) continue
                    if (flat[k2].length > flat[k].length && containsLoose(raw[k2].rect, raw[k].rect) &&
                        flat[k2].contains(flat[k])
                    ) {
                        inner = true
                        break
                    }
                }
                if (!inner) cover += flat[k].length
            }
            if (cover * 10 >= flat[ai].length * 8) {
                drop[ai] = true
            } else {
                for (k in kids) drop[k] = true
            }
        }
        val out = ArrayList<Found>()
        for (i in 0 until n) {
            if (!drop[i]) out.add(raw[i])
        }
        return out
    }

    private fun dedupe(list: List<Found>): List<Found> {
        val out = ArrayList<Found>()
        for (item in list) {
            var dup = -1
            for (i in out.indices) {
                val old = out[i]
                if (old.text != item.text) continue
                if (old.rect == item.rect || overlapPercent(item.rect, old.rect) > 55 ||
                    overlapPercent(old.rect, item.rect) > 55
                ) {
                    dup = i
                    break
                }
            }
            if (dup < 0) {
                out.add(item)
            } else {
                val old = out[dup]
                val oldArea = old.rect.width().toLong() * old.rect.height().toLong()
                val newArea = item.rect.width().toLong() * item.rect.height().toLong()
                if (newArea < oldArea) out[dup] = item
            }
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

    private fun estimateSize(text: String, r: Rect, paint: TextPaint): Float {
        val d = resources.displayMetrics.density
        val minS = 9f * d
        val maxS = 26f * d
        val w = (r.width() - 2).toFloat()
        val h = r.height().toFloat()
        if (w < 4f) return minS
        if (fitsAt(text, maxS, w, h, paint)) return maxS
        if (!fitsAt(text, minS, w, h, paint)) return minS
        var lo = minS
        var hi = maxS
        var i = 0
        while (i < 8 && hi - lo > 0.5f) {
            val mid = (lo + hi) / 2f
            if (fitsAt(text, mid, w, h, paint)) lo = mid else hi = mid
            i++
        }
        return lo
    }

    private fun fitsAt(text: String, size: Float, w: Float, h: Float, paint: TextPaint): Boolean {
        paint.textSize = size
        val total = paint.measureText(text)
        val fm = paint.fontMetrics
        var lineH = fm.descent - fm.ascent
        if (lineH < 1f) lineH = 1f
        var lines = Math.ceil((total / w).toDouble()).toInt()
        if (lines < 1) lines = 1
        return lines.toFloat() * lineH <= h + 1f
    }

    private fun refineSizes(j: Job) {
        if (Build.VERSION.SDK_INT < 30) return
        val t0 = SystemClock.uptimeMillis()
        var changed = false
        var count = 0
        for (it0 in j.items) {
            if (j.cancelled.get()) return
            if (count >= 150 || SystemClock.uptimeMillis() - t0 > 2500L) break
            val node = it0.node ?: continue
            count++
            val px = readTextSize(node)
            if (px > 0f) {
                var v = px
                val cap = it0.rect.height().toFloat()
                if (cap > 0f && v > cap) v = cap
                if (abs(v - it0.srcSize) > 0.5f) {
                    it0.srcSize = v
                    changed = true
                }
            }
        }
        if (changed) requestDraw()
    }

    private fun readTextSize(node: AccessibilityNodeInfo): Float {
        if (Build.VERSION.SDK_INT < 30) return -1f
        try {
            val key = AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY
            val avail = node.availableExtraData
            if (avail == null || !avail.contains(key)) return -1f
            if (!node.refreshWithExtraData(key, Bundle())) return -1f
            val info = node.extraRenderingInfo ?: return -1f
            return info.textSizeInPx
        } catch (t: Throwable) {
            return -1f
        }
    }

    // ---------------------------------------------------------------------
    // Text checks
    // ---------------------------------------------------------------------

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
                    c == 0x06D0 || c == 0x06AD || c == 0x06D5
                ) {
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
            } else if (c in 0x0980..0x09FF) {
                continue
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
        val c = scriptClass(s)
        return c == 5 || c == 6 || c == 11
    }

    private fun anyHan(list: List<String>): Boolean {
        for (x in list) {
            val c = scriptClass(x)
            if (c == 1 || c == 2) return true
        }
        return false
    }

    private fun anyArabicScript(list: List<String>): Boolean {
        for (x in list) {
            val c = scriptClass(x)
            if (c == 5 || c == 6) return true
        }
        return false
    }

    private fun squash(s: String): String {
        return s.replace(" ", "").lowercase()
    }

    private fun oneLine(s: String): String {
        var t = s.replace(CHAR_NL, ' ').replace(CHAR_CR, ' ').trim()
        if (t.length > 1500) t = t.substring(0, 1500)
        return t
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

    // ---------------------------------------------------------------------
    // Prompts
    // ---------------------------------------------------------------------

    private fun qq(s: String): String {
        return Char(34).toString() + s + Char(34)
    }

    private fun buildSystem(texts: List<String>): String {
        val sb = StringBuilder()
        sb.append("You are an expert human translator. Translate phone screen texts from any language into Bengali exactly as it is written and spoken today in West Bengal, India: the standard colloquial Bengali of Kolkata (পশ্চিমবঙ্গের চলিত বাংলা). Every translation must read as if a native Bengali from Kolkata wrote it: smooth, natural and easy (প্রাঞ্জল), never machine-like and never word by word.").append(NL)
        sb.append("Rules:").append(NL)
        sb.append("1) Use West Bengal words, spelling and style. Never use Bangladeshi words or style, and never সাধু ভাষা. For example: জল not পানি, স্নান not গোসল, নিমন্ত্রণ not দাওয়াত, জলখাবার not নাস্তা, মাংস not গোশত, নুন not লবণ, লঙ্কা not মরিচ, বাড়ি not বাসা, অতিথি not মেহমান, ধন্যবাদ not শুকরিয়া, দিদি not আপু, দাদা not ভাইয়া, বউদি not ভাবি, মাসি not খালা, পিসি not ফুপু, কাকা not চাচা, ফোন করুন not ফোন দিন.").append(NL)
        sb.append("2) Translate the meaning so it sounds natural in Bengali, keep the original tone, and use correct grammar and simple everyday words. Address the reader as আপনি.").append(NL)
        sb.append("3) EVERY letter, word and sentence in ANY language (Chinese, English, Uyghur, Arabic, Russian, Japanese, Korean, Hindi and all others) must become Bengali. Nothing may stay in the original language or script. Never skip any item, even a single character or a single word, and never return the original text unchanged.").append(NL)
        sb.append("4) Keep these exactly as they are and in their place: digits 0-9 (never change them into Bengali digits), symbols and punctuation (# @ & ? ! : ; - _ / + and similar), emojis, URLs and email addresses.").append(NL)
        sb.append("5) Hashtags: keep the # sign and translate the words after it, with no space after #, joining several words with _ (example: #分享 = #শেয়ার). Mentions: keep the @ sign and write the name after it in Bengali letters by its sound (example: @Alex = @অ্যালেক্স).").append(NL)
        sb.append("6) Names of people, places, apps and brands, and English words that Bengali people commonly use, must be written in Bengali letters, for example লাইক, শেয়ার, কমেন্ট, ভিডিও, লাইভ, সেটিংস, ইউটিউব, গুগল, ফেসবুক.").append(NL)
        sb.append("7) Buttons, tabs, badges and short labels must be short and clear, as in a Bengali phone app (更多 = আরও, 新 = নতুন, 赞 = লাইক, 关注 = ফলো করুন, Home = হোম, Not now = এখন না). Sentences must be complete and natural (You're all caught up = আপনি সব দেখে ফেলেছেন; What's on your mind? = আপনি কী ভাবছেন?; Something went wrong = কিছু একটা সমস্যা হয়েছে).").append(NL)
        if (anyHan(texts)) {
            sb.append("Common Chinese app words: 推荐 = প্রস্তাবিত; 关注 = ফলো করুন; 同城 = কাছাকাছি; 商城 = শপ; 直播 = লাইভ; 朋友 = বন্ধু; 消息 = মেসেজ; 我 = আমি; 首页 = হোম; 搜索 = খুঁজুন; 评论 = কমেন্ট; 分享 = শেয়ার; 点赞 = লাইক; 收藏 = সংরক্ষণ; 更多 = আরও; 设置 = সেটিংস; 回复 = উত্তর দিন; 展开 = আরও দেখুন; 收起 = কম দেখুন; 粉丝 = ফলোয়ার; 获赞 = লাইক পেয়েছেন; 作品 = পোস্ট; 私信 = মেসেজ; 热门 = জনপ্রিয়; 全部 = সব; 取消 = বাতিল; 确定 = ঠিক আছে; 发送 = পাঠান; 复制 = কপি; 删除 = মুছে দিন; 举报 = রিপোর্ট; 下载 = ডাউনলোড.").append(NL)
        }
        if (anyArabicScript(texts)) {
            sb.append("SPECIAL CARE: some texts are in a low-resource language such as Uyghur (a Turkic language written in Arabic script with letters like ئ ې ۇ ۆ ۈ ڭ ە ۋ; it can also be Kazakh or Kyrgyz written in Arabic script). It is NOT Arabic, Persian or Urdu. Read every word carefully, understand the real meaning in context, and never invent words or give an unrelated translation. Uyghur examples: ئىزدەش = খোঁজা; تەڭشەك = সেটিংস; ئاساسىي بەت = হোম পেজ.").append(NL)
        }
        return sb.toString()
    }

    private fun buildPrompt(texts: List<String>, appPkg: String, limits: Map<String, Int>, force: Boolean): String {
        val sb = StringBuilder()
        if (appPkg.isNotBlank()) {
            sb.append("All texts are from one screen of the phone app ").append(appPkg).append(", listed from top to bottom. Use them as context for each other.").append(NL)
        }
        if (force) {
            sb.append("IMPORTANT: these texts were left untranslated or only partly translated before. Translate every single word of each one into Bengali now, so that no Chinese, English, Arabic or other foreign letters remain (only symbols, emojis, digits and URLs stay as they are).").append(NL)
        }
        sb.append("Each input line is: number | language | text. The language was detected from the writing system: Latin means English or another Latin-script language, Arabic-script means Arabic, Persian, Urdu, Kazakh or Kyrgyz; recognise the exact language yourself. A line may also have max N chars before the text: that box on the screen is small, so use the shortest natural Bengali that fits in about N characters, without changing the meaning.").append(NL)
        sb.append("Texts:").append(NL)
        for (i in texts.indices) {
            val t = texts[i]
            sb.append(i + 1).append(" | ").append(langName(scriptClass(t))).append(" | ")
            val lim = limits[t] ?: 999
            if (lim <= 16) sb.append("max ").append(lim).append(" chars | ")
            sb.append(t).append(NL)
        }
        sb.append("Output exactly ").append(texts.size).append(" lines, one line per text in the same order, each in this exact format: number|Bengali translation").append(NL)
        sb.append("Format example only (not a real answer): 7|উত্তর দিন").append(NL)
        sb.append("Output nothing else: no explanations, no blank lines, no quotes, no markdown. Never merge two texts into one line and never split one text over two lines.").append(NL)
        return sb.toString()
    }

    private fun buildShotPrompt(known: List<String>, full: Boolean): String {
        val sb = StringBuilder()
        sb.append("This is a phone screenshot.").append(NL)
        if (full) {
            sb.append("Find all text on the screen that is not Bengali, in any language, including very small text and single words.").append(NL)
        } else {
            sb.append("Find only text that is not Bengali and appears inside pictures, photos, video frames, banners, posters, memes, stickers, watermarks or stylized graphics (not normal app interface text).").append(NL)
        }
        sb.append("Identify the language of each text carefully. For example Uyghur is written in Arabic script with letters like ئ ې ۇ ڭ ە ۋ and must not be confused with Arabic, Persian or Urdu. Understand the real meaning first.").append(NL)
        sb.append("Translate each text into the natural colloquial Bengali of West Bengal (Kolkata), smooth and human (প্রাঞ্জল), never Bangladeshi words or style (for example জল not পানি, স্নান not গোসল) and never সাধু ভাষা. Every word in any language must be written in Bengali. Keep symbols (# @ & ? ! and similar), emojis and digits exactly as they are.").append(NL)
        sb.append("Group words that belong together (one line or one sentence block) into a single item.").append(NL)
        sb.append("For each item give box_2d as [ymin, xmin, ymax, xmax], normalized 0 to 1000, tightly covering the original text.").append(NL)
        sb.append("Skip text that is already Bengali. Return at most 30 items.").append(NL)
        sb.append("Output only JSON: {").append(qq("items")).append(":[{").append(qq("translated_text")).append(":").append(qq("...")).append(",").append(qq("box_2d")).append(":[0,0,0,0]}]}").append(NL)
        if (known.isNotEmpty()) {
            sb.append("These texts are already translated separately, skip them:").append(NL)
            for (k in known) {
                var t = k
                if (t.length > 40) t = t.substring(0, 40)
                sb.append("- ").append(t).append(NL)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------
    // Text translation (streaming, one request for the whole screen)
    // ---------------------------------------------------------------------

    private fun runTextTask(j: Job, texts: List<String>, strong: Boolean, force: Boolean, part: Int) {
        if (texts.isEmpty()) return
        val level = if (strong) HARD_THINKING else TEXT_THINKING
        val models = if (strong) strongModels(j) else j.models
        val tried = HashSet<String>()
        val answered: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        var pending: List<String> = texts.filter { !j.good.containsKey(it) }
        var groups = 0
        while (j.alive() && pending.isNotEmpty() && groups < MAX_GROUPS) {
            groups++
            val order = comboOrder(j, models, part)
            val res = runGroup(j, pending, force, level, order, tried, answered)
            if (res.completed) break
            if (res.lines == 0 && !hasUntried(order, tried)) break
            val next = ArrayList<String>()
            for (t in pending) {
                if (!answered.contains(t) && !j.good.containsKey(t)) next.add(t)
            }
            pending = next
        }
    }

    private fun hasUntried(order: List<Combo>, tried: Set<String>): Boolean {
        for (c in order) {
            if (!tried.contains(c.tag)) return true
        }
        return false
    }

    private fun runGroup(j: Job, texts: List<String>, force: Boolean, level: String, order: List<Combo>,
                         tried: MutableSet<String>, answered: MutableSet<String>): GroupResult {
        val sys = buildSystem(texts)
        val prompt = buildPrompt(texts, j.appPkg, j.limits, force)
        val live = ArrayList<Attempt>()

        fun launchNext(): Boolean {
            if (live.size >= MAX_ATTEMPTS) return false
            for (c in order) {
                if (tried.contains(c.tag)) continue
                tried.add(c.tag)
                if (j.badCombos.contains(c.tag)) continue
                val a = Attempt(c, SystemClock.uptimeMillis())
                j.slots.add(a.slot)
                live.add(a)
                val ok = runBg { runTextAttempt(j, a, texts, sys, prompt, level, answered) }
                if (!ok) {
                    a.code = -1
                    a.done = true
                }
                return true
            }
            return false
        }

        if (!launchNext()) return GroupResult(0, false)
        while (j.alive()) {
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                break
            }
            var all = true
            for (t in texts) {
                if (!answered.contains(t)) {
                    all = false
                    break
                }
            }
            if (all) break
            val now = SystemClock.uptimeMillis()
            var streaming: Attempt? = null
            var active = 0
            var newest: Attempt? = null
            var oldest: Attempt? = null
            for (a in live) {
                if (a.lines > 0 && streaming == null) streaming = a
                if (!a.done) {
                    active++
                    if (newest == null || a.startAt > newest.startAt) newest = a
                    if (oldest == null || a.startAt < oldest.startAt) oldest = a
                }
            }
            if (streaming != null) {
                for (a in live) {
                    if (a === streaming || a.done) continue
                    if (a.lines == 0 && !a.slot.cancelled) {
                        a.slot.cancel()
                        markSlow(a.combo)
                    }
                }
                for (a in live) {
                    if (!a.done && a.lines > 0 && now - a.lastAt > STALL_MS) a.slot.cancel()
                }
            }
            if (active == 0) {
                if (streaming != null) break
                if (!launchNext()) break
                continue
            }
            if (streaming == null && newest != null) {
                val waited = now - newest.startAt
                if (waited > firstLineLimit(newest.combo.model, level)) {
                    if (active < MAX_LIVE) {
                        launchNext()
                    } else if (oldest != null && now - oldest.startAt > FIRST_LINE_MAX_MS) {
                        oldest.slot.cancel()
                        markSlow(oldest.combo)
                        launchNext()
                    }
                }
            }
        }
        val stillAlive = j.alive()
        var lines = 0
        var completed = false
        for (a in live) {
            if (!a.done && (a.lines == 0 || !stillAlive)) a.slot.cancel()
            lines += a.lines
            if (a.done && a.code == 200 && a.lines > 0) completed = true
        }
        return GroupResult(lines, completed)
    }

    private fun runTextAttempt(j: Job, a: Attempt, texts: List<String>, sys: String, prompt: String,
                               level: String, answered: MutableSet<String>) {
        var code = -1
        try {
            code = callWithLevels(a, sys, prompt, null, level, true) { n, tr ->
                onLine(j, a, texts, n, tr, answered)
            }
        } catch (t: Throwable) {
            code = -1
        }
        if (a.slot.cancelled) code = -2
        val took = SystemClock.uptimeMillis() - a.startAt
        if (code == 200) {
            if (a.lines > 0) {
                if (!a.recorded) {
                    a.recorded = true
                    recordSpeed(a.combo.model, took, 4 + texts.size)
                }
            } else {
                code = 204
            }
        }
        if (code != 200 && code != -2) {
            markBad(j, a.combo, code)
            noteCode(j, code)
        }
        a.code = code
        a.done = true
    }

    private fun onLine(j: Job, a: Attempt, texts: List<String>, n: Int, tr: String, answered: MutableSet<String>) {
        if (n < 1 || n > texts.size) return
        val src = texts[n - 1]
        val now = SystemClock.uptimeMillis()
        if (a.lines == 0) {
            recordFirst(a.combo.model, now - a.startAt)
            saveCurrentCombo(a.combo)
        }
        a.lines = a.lines + 1
        a.lastAt = now
        j.lines.incrementAndGet()
        answered.add(src)
        if (a.lines >= texts.size && !a.recorded) {
            a.recorded = true
            recordSpeed(a.combo.model, now - a.startAt, 4 + texts.size)
        }
        val clean = oneLine(tr)
        if (clean.isEmpty()) return
        if (goodTr(src, clean)) {
            j.good[src] = clean
            j.soft.remove(src)
            cachePut(src, clean)
        } else if (softOk(src, clean)) {
            if (!j.good.containsKey(src)) j.soft[src] = clean
        }
        requestDraw()
    }

    private fun parseLine(raw: String): Pair<Int, String>? {
        var s = raw.trim()
        while (s.isNotEmpty() && (s[0] == '*' || s[0] == '-' || s[0] == '`' || s[0] == '>' || s[0] == '#')) {
            s = s.substring(1).trim()
        }
        if (s.isEmpty()) return null
        var i = 0
        var num = 0
        var digits = 0
        while (i < s.length) {
            val c = s[i]
            val d = if (c in '0'..'9') c.code - 48 else if (c.code in 0x09E6..0x09EF) c.code - 0x09E6 else -1
            if (d < 0) break
            num = num * 10 + d
            digits++
            i++
            if (digits > 4) return null
        }
        if (digits == 0) return null
        while (i < s.length && s[i] == ' ') i++
        if (i >= s.length) return null
        val sep = s[i]
        if (sep != '|' && sep != '｜') return null
        var t = s.substring(i + 1).trim()
        var guard = 0
        while (guard < 3) {
            guard++
            var k = t.indexOf('|')
            if (k < 0) k = t.indexOf('｜')
            if (k < 0) break
            val head = t.substring(0, k).trim().lowercase()
            val label = LABELS.contains(head) || (head.startsWith("max ") && head.endsWith("chars"))
            if (label) {
                t = t.substring(k + 1).trim()
            } else {
                break
            }
        }
        if (t.length >= 2 && t[0] == '"' && t[t.length - 1] == '"') t = t.substring(1, t.length - 1).trim()
        if (t.isEmpty()) return null
        return Pair(num, t)
    }

    // ---------------------------------------------------------------------
    // Text inside pictures (screenshot), only when it is needed
    // ---------------------------------------------------------------------

    private fun captureForJob(j: Job, latch: CountDownLatch) {
        if (job !== j || !j.alive() || !uiReady) {
            latch.countDown()
            openGate(j)
            return
        }
        capturing = true
        captureStartedAt = SystemClock.uptimeMillis()
        buttonView?.visibility = View.GONE
        handler.postDelayed({
            captureScreen { b64 ->
                capturing = false
                if (job === j) syncUiState()
                openGate(j)
                if (b64 != null && j.alive()) {
                    val ok = runBg {
                        try {
                            runImageTask(j, b64)
                        } finally {
                            latch.countDown()
                        }
                    }
                    if (!ok) latch.countDown()
                } else {
                    latch.countDown()
                }
            }
        }, 120)
    }

    private fun captureScreen(done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            done(null)
            return
        }
        val fired = AtomicBoolean(false)
        val finishOnce: (String?) -> Unit = { value ->
            if (fired.compareAndSet(false, true)) done(value)
        }
        handler.postDelayed({ finishOnce(null) }, 4000)
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val ok = runBg {
                            var b64: String? = null
                            try {
                                val hb: HardwareBuffer = screenshot.hardwareBuffer
                                val rawBmp = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                                if (rawBmp != null) {
                                    val soft = rawBmp.copy(Bitmap.Config.ARGB_8888, false)
                                    try {
                                        rawBmp.recycle()
                                    } catch (e: Throwable) { }
                                    if (soft != null) {
                                        b64 = encodeShot(soft)
                                        try {
                                            soft.recycle()
                                        } catch (e: Throwable) { }
                                    }
                                }
                                try {
                                    hb.close()
                                } catch (e: Throwable) { }
                            } catch (e: Throwable) { }
                            val result = b64
                            handler.post { finishOnce(result) }
                        }
                        if (!ok) finishOnce(null)
                    }

                    override fun onFailure(errorCode: Int) {
                        handler.post { finishOnce(null) }
                    }
                })
        } catch (e: Throwable) {
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
                } catch (e: Throwable) { }
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) return null
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Throwable) {
            return null
        }
    }

    private fun runImageTask(j: Job, b64: String) {
        val known = ArrayList<String>()
        for (it0 in j.items) {
            if (known.size >= 40) break
            if (!known.contains(it0.text)) known.add(it0.text)
        }
        val full = j.items.size < 4
        val prompt = buildShotPrompt(known, full)
        val order = comboOrder(j, imageModels(j), 3)
        val tried = HashSet<String>()
        val live = ArrayList<Attempt>()

        fun launchNext(): Boolean {
            for (c in order) {
                if (tried.contains(c.tag)) continue
                tried.add(c.tag)
                if (j.badCombos.contains(c.tag)) continue
                val a = Attempt(c, SystemClock.uptimeMillis())
                j.slots.add(a.slot)
                live.add(a)
                val ok = runBg { runImageAttempt(j, a, prompt, b64) }
                if (!ok) {
                    a.code = -1
                    a.done = true
                }
                return true
            }
            return false
        }

        if (!launchNext()) return
        var body: String? = null
        while (j.alive()) {
            try {
                Thread.sleep(POLL_MS * 2)
            } catch (e: InterruptedException) {
                break
            }
            var active = 0
            var newest: Attempt? = null
            for (a in live) {
                val b = a.body
                if (a.done && b != null) {
                    body = b
                    break
                }
                if (!a.done) {
                    active++
                    if (newest == null || a.startAt > newest.startAt) newest = a
                }
            }
            if (body != null) break
            if (active == 0) {
                if (!launchNext()) break
                continue
            }
            if (active < MAX_LIVE && newest != null &&
                SystemClock.uptimeMillis() - newest.startAt > IMAGE_HEDGE_MS
            ) {
                launchNext()
            }
        }
        for (a in live) {
            if (!a.done) a.slot.cancel()
        }
        val result = body ?: return
        if (!j.alive()) return
        val found = parseShot(result)
        val accepted = ArrayList<ImgEntry>()
        for (im in found) {
            var clash = false
            for (it0 in j.items) {
                if (overlapPercent(im.rect, it0.rect) > 25) {
                    clash = true
                    break
                }
            }
            if (!clash) {
                for (o in accepted) {
                    if (overlapPercent(im.rect, o.rect) > 40) {
                        clash = true
                        break
                    }
                }
            }
            if (!clash) accepted.add(im)
        }
        if (accepted.isNotEmpty()) {
            synchronized(j.images) { j.images.addAll(accepted) }
            requestDraw()
        }
    }

    private fun runImageAttempt(j: Job, a: Attempt, prompt: String, b64: String) {
        var code = -1
        try {
            code = callWithLevels(a, "", prompt, b64, SHOT_THINKING, false, null)
        } catch (t: Throwable) {
            code = -1
        }
        if (a.slot.cancelled) code = -2
        if (code == 200) saveCurrentCombo(a.combo)
        if (code != 200 && code != -2) {
            markBad(j, a.combo, code)
            noteCode(j, code)
        }
        a.code = code
        a.done = true
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

    private fun parseShot(body: String): List<ImgEntry> {
        val out = ArrayList<ImgEntry>()
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
            val sw = screenWidth
            val sh = screenHeight
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                var tr = oneLine(e.optString("translated_text", ""))
                if (tr.isBlank()) tr = oneLine(e.optString("text", ""))
                if (tr.isBlank() || !hasBengali(tr)) continue
                var box = e.optJSONArray("box_2d")
                if (box == null) box = e.optJSONArray("box")
                if (box == null || box.length() < 4) continue
                val ymin = box.optDouble(0, -1.0)
                val xmin = box.optDouble(1, -1.0)
                val ymax = box.optDouble(2, -1.0)
                val xmax = box.optDouble(3, -1.0)
                if (ymin < 0.0 || xmin < 0.0 || ymax <= ymin || xmax <= xmin) continue
                var left = (xmin / 1000.0 * sw).toInt()
                var top = (ymin / 1000.0 * sh).toInt()
                var right = (xmax / 1000.0 * sw).toInt()
                var bottom = (ymax / 1000.0 * sh).toInt()
                if (left < 0) left = 0
                if (top < 0) top = 0
                if (right > sw) right = sw
                if (bottom > sh) bottom = sh
                if (right - left < minBoxWidth || bottom - top < minBoxHeight) continue
                val r = Rect(left, top, right, bottom)
                if (r.width() > sw * 96 / 100 && r.height() > sh / 2) continue
                var size = r.height().toFloat() * 0.62f
                val cap = 24f * resources.displayMetrics.density
                if (size > cap) size = cap
                out.add(ImgEntry(tr, r, size))
                if (out.size >= 30) break
            }
        } catch (e: Throwable) { }
        return out
    }

    // ---------------------------------------------------------------------
    // Gemini calls
    // ---------------------------------------------------------------------

    private fun callWithLevels(a: Attempt, sys: String, prompt: String, image: String?, level: String,
                               stream: Boolean, onLine: ((Int, String) -> Unit)?): Int {
        val tag = a.combo.model + "|" + level
        val known = synchronized(comboLock) { levelFix[tag] }
        var skip: String? = null
        var skipUsed = false
        if (known != null) {
            val use = if (known.isEmpty()) null else known
            val c = callOnce(a, sys, prompt, image, use, stream, onLine)
            if (c != 400) return c
            forgetLevel(tag)
            skip = use
            skipUsed = true
        }
        val tries = ArrayList<String?>()
        var i = THINK_LEVELS.indexOf(level)
        if (i < 0) i = THINK_LEVELS.size
        while (i < THINK_LEVELS.size) {
            tries.add(THINK_LEVELS[i])
            i++
        }
        tries.add(null)
        var last = 400
        for (lv in tries) {
            if (a.slot.cancelled) return -2
            if (skipUsed && lv == skip) continue
            val c = callOnce(a, sys, prompt, image, lv, stream, onLine)
            if (c != 400) {
                if (c == 200) rememberLevel(tag, lv ?: "")
                return c
            }
            last = c
        }
        return last
    }

    private fun buildBody(sys: String, prompt: String, image: String?, level: String?): String {
        val parts = JSONArray()
        if (image != null) {
            val data = JSONObject()
            data.put("mime_type", "image/jpeg")
            data.put("data", image)
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
        if (image != null) gen.put("responseMimeType", "application/json")
        if (level != null) {
            val tc = JSONObject()
            tc.put("thinkingLevel", level)
            gen.put("thinkingConfig", tc)
        }
        val json = JSONObject()
        if (sys.isNotEmpty()) {
            val sp = JSONArray()
            val st = JSONObject()
            st.put("text", sys)
            sp.put(st)
            val si = JSONObject()
            si.put("parts", sp)
            json.put("systemInstruction", si)
        }
        json.put("contents", contents)
        json.put("generationConfig", gen)
        return json.toString()
    }

    private fun callOnce(a: Attempt, sys: String, prompt: String, image: String?, level: String?,
                         stream: Boolean, onLine: ((Int, String) -> Unit)?): Int {
        val slot = a.slot
        if (slot.cancelled) return -2
        try {
            val body = buildBody(sys, prompt, image, level)
            val base = "https://generativelanguage.googleapis.com/v1beta/models/" + a.combo.model
            val url = if (stream) base + ":streamGenerateContent?alt=sse" else base + ":generateContent"
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", a.combo.key)
                .post(body.toRequestBody(jsonType))
                .build()
            val client = if (image != null) imageClient else streamClient
            val call = client.newCall(request)
            slot.call = call
            lastNetAt = System.currentTimeMillis()
            if (slot.cancelled) {
                call.cancel()
                return -2
            }
            call.execute().use { response ->
                val code = response.code
                if (code != 200) {
                    val err = try {
                        response.body?.string()
                    } catch (e: Throwable) {
                        null
                    }
                    return mapError(code, err)
                }
                if (!stream) {
                    val text = response.body?.string() ?: return -1
                    val out = extractText(JSONObject(text))
                    if (out.isBlank()) return 204
                    a.body = out
                    return 200
                }
                val source = response.body?.source() ?: return -1
                val buf = StringBuilder()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    a.lastAt = SystemClock.uptimeMillis()
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty()) continue
                    val obj = try {
                        JSONObject(payload)
                    } catch (e: Throwable) {
                        null
                    } ?: continue
                    val err = obj.optJSONObject("error")
                    if (err != null) return mapError(err.optInt("code", 500), payload)
                    val piece = extractText(obj)
                    if (piece.isEmpty()) continue
                    buf.append(piece)
                    var nl = buf.indexOf(NL)
                    while (nl >= 0) {
                        val ln = buf.substring(0, nl)
                        buf.delete(0, nl + 1)
                        emitLine(ln, onLine)
                        nl = buf.indexOf(NL)
                    }
                }
                if (buf.isNotEmpty()) emitLine(buf.toString(), onLine)
                return 200
            }
        } catch (t: Throwable) {
            return if (slot.cancelled) -2 else -1
        }
    }

    private fun warmConnection() {
        try {
            lastNetAt = System.currentTimeMillis()
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/")
                .head()
                .build()
            streamClient.newCall(request).execute().use { }
        } catch (t: Throwable) { }
    }

    private fun emitLine(ln: String, onLine: ((Int, String) -> Unit)?) {
        if (onLine == null) return
        val p = parseLine(ln) ?: return
        onLine(p.first, p.second)
    }

    private fun extractText(o: JSONObject): String {
        val cands = o.optJSONArray("candidates") ?: return ""
        if (cands.length() == 0) return ""
        val cand = cands.optJSONObject(0) ?: return ""
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            val t = p.optString("text", "")
            if (t.isNotEmpty()) sb.append(t)
        }
        return sb.toString()
    }

    private fun mapError(code: Int, body: String?): Int {
        val b = body ?: ""
        if (code == 429) {
            if (b.contains("PerDay") || b.contains("per day") || b.contains("PER_DAY")) return 4290
            return 429
        }
        if (code == 400 && (b.contains("API key") || b.contains("API_KEY"))) return 401
        return code
    }

    // ---------------------------------------------------------------------
    // Keys, models, speed and rest times
    // ---------------------------------------------------------------------

    private fun comboOrder(j: Job, models: List<String>, offset: Int): List<Combo> {
        val now = System.currentTimeMillis()
        val ready = ArrayList<Combo>()
        val cooled = ArrayList<Pair<Combo, Long>>()
        val ks = j.keys
        if (ks.isEmpty()) return ready
        for (m in models) {
            for (k in ks.indices) {
                val key = ks[(j.turn + offset + k) % ks.size]
                val c = Combo(key, m)
                if (j.badCombos.contains(c.tag)) continue
                val until = synchronized(comboLock) { cooldown[c.tag] ?: 0L }
                if (until > now) cooled.add(Pair(c, until)) else ready.add(c)
            }
        }
        if (ready.isEmpty()) {
            cooled.sortBy { it.second }
            for (p in cooled) {
                if (ready.size >= 2) break
                if (p.second - now <= 70000L) ready.add(p.first)
            }
        }
        return ready
    }

    private fun markBad(j: Job, c: Combo, code: Int) {
        val ms = when {
            code == 4290 -> 3600000L
            code == 429 -> 60000L
            code == 401 || code == 403 || code == 404 -> 600000L
            code >= 500 -> 15000L
            code == -1 -> 8000L
            else -> 20000L
        }
        synchronized(comboLock) {
            val until = System.currentTimeMillis() + ms
            val old = cooldown[c.tag] ?: 0L
            if (until > old) cooldown[c.tag] = until
        }
        j.badCombos.add(c.tag)
        if (code == 404) markDead(c.model)
    }

    private fun markSlow(c: Combo) {
        synchronized(comboLock) {
            val until = System.currentTimeMillis() + 15000L
            val old = cooldown[c.tag] ?: 0L
            if (until > old) cooldown[c.tag] = until
        }
    }

    private fun noteCode(j: Job, code: Int) {
        if (code == 429 || code == 4290) j.saw429 = true
        j.lastCode = code
    }

    private fun firstLineLimit(model: String, level: String): Long {
        val base = synchronized(speedLock) { firstLat[model] }
        var t = if (base != null) {
            (base * 2.2).toLong() + 1500L
        } else if (level == TEXT_THINKING) {
            FIRST_LINE_MS
        } else {
            FIRST_LINE_HARD_MS
        }
        if (t < FIRST_LINE_MIN_MS) t = FIRST_LINE_MIN_MS
        if (t > FIRST_LINE_MAX_MS) t = FIRST_LINE_MAX_MS
        return t
    }

    private fun recordFirst(model: String, ms: Long) {
        if (ms <= 0L) return
        synchronized(speedLock) {
            val old = firstLat[model]
            firstLat[model] = if (old == null) ms.toDouble() else old * 0.6 + ms.toDouble() * 0.4
        }
    }

    private fun strongModels(j: Job): List<String> {
        if (j.auto) return deadLast(HARD_PREF)
        val out = ArrayList<String>()
        for (m in HARD_PREF) {
            if (j.models.contains(m)) out.add(m)
        }
        return if (out.isEmpty()) j.models else out
    }

    private fun imageModels(j: Job): List<String> {
        if (j.auto) return deadLast(IMAGE_PREF)
        val out = ArrayList<String>()
        for (m in IMAGE_PREF) {
            if (j.models.contains(m)) out.add(m)
        }
        return if (out.isEmpty()) j.models else out
    }

    private fun deadLast(list: List<String>): List<String> {
        val okList = ArrayList<String>()
        val deadList = ArrayList<String>()
        for (m in list) {
            val sp = speedOf(m)
            if (sp != null && sp >= DEAD_SPEED) deadList.add(m) else okList.add(m)
        }
        okList.addAll(deadList)
        return okList
    }

    private fun rememberLevel(tag: String, lv: String) {
        val old = synchronized(comboLock) {
            val o = levelFix[tag]
            levelFix[tag] = lv
            o
        }
        if (old != lv) {
            try {
                prefs().edit().putString("lvl_" + tag, lv).apply()
            } catch (e: Exception) { }
        }
    }

    private fun forgetLevel(tag: String) {
        synchronized(comboLock) { levelFix.remove(tag) }
        try {
            prefs().edit().remove("lvl_" + tag).apply()
        } catch (e: Exception) { }
    }

    private fun getKeys(): List<String> {
        val p = prefs()
        return listOf(
            p.getString("key1", "") ?: "",
            p.getString("key2", "") ?: "",
            p.getString("key3", "") ?: "",
            p.getString("key4", "") ?: "",
            p.getString("key5", "") ?: ""
        ).map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun getModels(auto: Boolean): List<String> {
        if (auto) return rankModels(ALL_MODELS)
        val p = prefs()
        val models = ArrayList<String>()
        if (p.getBoolean("model1", true)) models.add(ALL_MODELS[0])
        if (p.getBoolean("model2", true)) models.add(ALL_MODELS[1])
        if (p.getBoolean("model3", true)) models.add(ALL_MODELS[2])
        if (p.getBoolean("model4", false)) models.add(ALL_MODELS[3])
        if (p.getBoolean("model5", false)) models.add(ALL_MODELS[4])
        return models
    }

    private fun loadSpeeds() {
        try {
            val p = prefs()
            synchronized(comboLock) {
                for (m in ALL_MODELS) {
                    for (lv in THINK_LEVELS) {
                        val tag = m + "|" + lv
                        val v = p.getString("lvl_" + tag, null)
                        if (v != null) levelFix[tag] = v
                    }
                }
            }
            synchronized(speedLock) {
                for (m in ALL_MODELS) {
                    val v = p.getFloat("spd_" + m, -1f)
                    if (v > 0f) {
                        speedVal[m] = v.toDouble()
                        speedAt[m] = p.getLong("spdt_" + m, 0L)
                    }
                }
            }
        } catch (e: Exception) { }
    }

    private fun recordSpeed(model: String, ms: Long, units: Int) {
        if (ms <= 0L || units <= 0) return
        val v = ms.toDouble() / units.toDouble()
        val now = System.currentTimeMillis()
        var nv = v
        synchronized(speedLock) {
            val old = speedVal[model]
            if (old != null && old < v * 20.0) {
                val age = now - (speedAt[model] ?: 0L)
                nv = if (age > SPEED_STALE_MS) old * 0.3 + v * 0.7 else old * 0.6 + v * 0.4
            }
            speedVal[model] = nv
            speedAt[model] = now
        }
        try {
            prefs().edit()
                .putFloat("spd_" + model, nv.toFloat())
                .putLong("spdt_" + model, now)
                .apply()
        } catch (e: Exception) { }
    }

    private fun markDead(model: String) {
        val now = System.currentTimeMillis()
        synchronized(speedLock) {
            speedVal[model] = DEAD_SPEED
            speedAt[model] = now
        }
        try {
            prefs().edit()
                .putFloat("spd_" + model, DEAD_SPEED.toFloat())
                .putLong("spdt_" + model, now)
                .apply()
        } catch (e: Exception) { }
    }

    private fun speedOf(model: String): Double? {
        synchronized(speedLock) {
            return speedVal[model]
        }
    }

    private fun priorSpeed(model: String): Double {
        return if (model.contains("lite")) 300.0 else 600.0
    }

    private fun rankModels(list: List<String>): List<String> {
        val score = HashMap<String, Double>()
        for (m in list) score[m] = speedOf(m) ?: priorSpeed(m)
        val out = ArrayList<String>(list)
        out.sortWith(Comparator { a, b -> java.lang.Double.compare(score[a] ?: 0.0, score[b] ?: 0.0) })
        return out
    }

    private fun saveCurrentCombo(c: Combo) {
        if (lastComboSaved == c.tag) return
        lastComboSaved = c.tag
        try {
            prefs().edit()
                .putString("currentKey", c.key.take(8) + "...")
                .putString("currentModel", c.model)
                .apply()
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------------
    // Saved translations (memory book)
    // ---------------------------------------------------------------------

    private fun cacheGet(text: String): String? {
        synchronized(cache) {
            return cache[text]
        }
    }

    private fun cachePut(text: String, tr: String) {
        if (squash(text) == squash(tr)) return
        if (!hasBengali(tr)) return
        synchronized(cache) {
            if (cache[text] != tr) {
                cache[text] = tr
                cacheDirty = true
            }
        }
    }

    private fun loadCache() {
        try {
            val old = File(filesDir, OLD_CACHE_FILE)
            if (old.exists()) old.delete()
        } catch (e: Exception) { }
        try {
            val f = File(filesDir, CACHE_FILE)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            val names = o.keys()
            synchronized(cache) {
                while (names.hasNext()) {
                    val k = names.next()
                    val v = o.optString(k, "")
                    if (v.isNotBlank() && !cache.containsKey(k)) cache[k] = v
                }
            }
        } catch (e: Exception) { }
    }

    private fun saveCache() {
        if (!cacheDirty) return
        cacheDirty = false
        try {
            val o = JSONObject()
            synchronized(cache) {
                for (e in cache.entries) {
                    o.put(e.key, e.value)
                }
            }
            val f = File(filesDir, CACHE_FILE)
            val tmp = File(filesDir, CACHE_FILE + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString())
                tmp.delete()
            }
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    private fun prefs(): SharedPreferences {
        return getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
    }

    private fun runBg(block: () -> Unit): Boolean {
        return try {
            pool.execute {
                try {
                    block()
                } catch (t: Throwable) { }
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { }
    }

    private fun saveError(msg: String) {
        try {
            prefs().edit().putString("lastError", msg).apply()
        } catch (e: Exception) { }
    }

    private fun setButtonColor(hex: String) {
        try {
            buttonBg?.setColor(Color.parseColor(hex))
        } catch (e: Exception) { }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun shutdown() {
        val j = job
        job = null
        if (j != null) cancelJob(j)
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
        val j = job
        job = null
        if (j != null) cancelJob(j)
        state = ST_IDLE
        uiReady = false
        handler.removeCallbacksAndMessages(null)
        removeViews()
        unregister()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        val j = job
        job = null
        if (j != null) cancelJob(j)
        state = ST_IDLE
        uiReady = false
        handler.removeCallbacksAndMessages(null)
        removeViews()
        unregister()
        try {
            pool.shutdown()
        } catch (e: Throwable) { }
        super.onDestroy()
    }

    companion object {
        private val CHAR_NL = Char(10)
        private val CHAR_CR = Char(13)
        private val NL: String = Char(10).toString()
        private const val ST_IDLE = 0
        private const val ST_BUSY = 1
        private const val ST_SHOWN = 2
        private const val END_NO_SCREEN = 1
        private const val END_OWN_APP = 2
        private const val END_NO_TEXT = 3
        private const val MAX_VISIT = 4000
        private const val MAX_FOUND = 500
        private const val MAX_ITEMS = 200
        private const val MAX_TEXT_LEN = 1200
        private const val PART_ITEMS = 45
        private const val PART_CHARS = 3500
        private const val MAX_PARTS = 3
        private const val HARD_PART_ITEMS = 40
        private const val HARD_PART_CHARS = 3000
        private const val FIX_ROUNDS = 2
        private const val FIX_MAX = 80
        private const val MAX_GROUPS = 4
        private const val MAX_LIVE = 2
        private const val MAX_ATTEMPTS = 10
        private const val CACHE_MAX = 1500
        private const val CACHE_FILE = "tr_cache_wb.json"
        private const val OLD_CACHE_FILE = "tr_cache.json"
        private const val TEXT_THINKING = "minimal"
        private const val HARD_THINKING = "low"
        private const val SHOT_THINKING = "low"
        private const val COLOR_IDLE = "#2196F3"
        private const val COLOR_BUSY = "#FF9800"
        private const val COLOR_DONE = "#4CAF50"
        private const val JOB_DEADLINE_MS = 30000L
        private const val WATCHDOG_EXTRA_MS = 3000L
        private const val CANCEL_AFTER_MS = 4000L
        private const val DRAW_GATE_MS = 1500L
        private const val DRAW_THROTTLE_MS = 90L
        private const val VERIFY_DELAY_MS = 300L
        private const val FIRST_LINE_MS = 5000L
        private const val FIRST_LINE_HARD_MS = 10000L
        private const val FIRST_LINE_MIN_MS = 3500L
        private const val FIRST_LINE_MAX_MS = 14000L
        private const val STALL_MS = 5000L
        private const val IMAGE_HEDGE_MS = 15000L
        private const val POLL_MS = 60L
        private const val WARM_AFTER_MS = 240000L
        private const val SPEED_STALE_MS = 1800000L
        private const val DEAD_SPEED = 120000.0
        private val THINK_LEVELS = listOf("minimal", "low", "medium", "high")
        private val LABELS = setOf(
            "latin", "chinese", "japanese", "korean", "cyrillic", "arabic-script", "uyghur",
            "thai", "devanagari", "hebrew", "greek", "other-script", "bengali", "bangla"
        )
        private val ALL_MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-3-flash-preview",
            "gemini-3.8-flash",
            "gemini-3.6-flash"
        )
        private val HARD_PREF = listOf(
            "gemini-3.6-flash",
            "gemini-3.8-flash",
            "gemini-3-flash-preview",
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite"
        )
        private val IMAGE_PREF = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "gemini-3-flash-preview",
            "gemini-3.8-flash",
            "gemini-3.1-flash-lite"
        )
    }
}
