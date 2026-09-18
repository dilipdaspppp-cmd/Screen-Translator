package com.example.screentranslator

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
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
import java.util.concurrent.TimeUnit

class OverlayView(context: Context) : View(context) {

    data class Entry(val text: String, val rect: Rect)

    private class Drawn(val rect: Rect, val layout: StaticLayout, val pad: Float)

    private var drawn: List<Drawn> = emptyList()

    private val bgPaint = Paint().apply {
        color = Color.rgb(20, 20, 20)
        isAntiAlias = false
    }

    private val textPaint = TextPaint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }

    fun setEntries(list: List<Entry>) {
        val density = resources.displayMetrics.density
        val pad = 3f * density
        val maxSize = 20f * density
        val minSize = 8f * density
        val out = ArrayList<Drawn>()
        for (en in list) {
            val w = en.rect.width()
            val h = en.rect.height()
            if (w <= 0 || h <= 0) continue
            val innerW = (w - pad * 2f).toInt()
            if (innerW <= 2) continue
            val innerH = h.toFloat() - 2f
            if (innerH <= 2f) continue
            val layout = fit(en.text, innerW, innerH, maxSize, minSize)
            out.add(Drawn(Rect(en.rect), layout, pad))
        }
        drawn = out
        invalidate()
    }

    private fun fit(text: String, width: Int, height: Float, maxSize: Float, minSize: Float): StaticLayout {
        var size = maxSize
        if (size > height) size = height
        if (size < minSize) size = minSize
        var layout = build(text, width, size, 0)
        var guard = 0
        while (layout.height > height && size > minSize && guard < 80) {
            size = size - 1f
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
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
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

    private data class Item(val text: String, val rect: Rect)

    private var overlayView: OverlayView? = null
    private var buttonView: TextView? = null
    private var buttonBg: GradientDrawable? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

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
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
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
            minBoxHeight = dpToPx(11)
            minBoxWidth = dpToPx(16)
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
        if (!isOverlayShowing) return
        if (System.currentTimeMillis() - overlayShownAt < 1200) return
        clearOverlay()
        setButtonColor("#2196F3")
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        windowManager?.addView(overlayView, params)
    }

    private fun setupFloatingButton() {
        buttonBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#2196F3"))
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
        if (isWorking) return
        if (isOverlayShowing) {
            clearOverlay()
            setButtonColor("#2196F3")
        } else {
            translateNow()
        }
    }

    private fun translateNow() {
        isWorking = true
        setButtonColor("#FF9800")
        buttonView?.visibility = View.GONE
        clearOverlay()
        handler.postDelayed({ collectAndTranslate() }, 250)
    }

    private fun collectAndTranslate() {
        refreshMetrics()
        var root: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow
        } catch (e: Exception) { }

        if (root == null) {
            resetWork()
            toast(getString(R.string.msg_no_screen))
            return
        }
        if (root.packageName?.toString() == packageName) {
            resetWork()
            toast(getString(R.string.msg_own_app))
            return
        }

        val raw = ArrayList<Item>()
        try {
            walk(root, raw, 0)
        } catch (e: Exception) { }

        val noParents = filterParents(raw)
        val unique = dedupe(noParents)
        val packed = packRects(unique)
        val items = if (packed.size > 40) packed.subList(0, 40) else packed

        if (items.isEmpty()) {
            resetWork()
            toast(getString(R.string.msg_no_text))
            return
        }

        buttonView?.visibility = View.VISIBLE
        val finalItems = ArrayList<Item>(items)
        Thread { callWithRotation(finalItems) }.start()
    }

    private fun walk(node: AccessibilityNodeInfo, items: ArrayList<Item>, depth: Int) {
        if (items.size >= 140 || depth > 60) return
        try {
            val t = node.text?.toString()?.trim()
            if (!t.isNullOrBlank() && t.length > 1 && node.isVisibleToUser) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val screen = Rect(0, 0, screenWidth, screenHeight)
                if (r.intersect(screen) && r.width() >= minBoxWidth && r.height() >= minBoxHeight) {
                    var clean = t.replace(CHAR_NL, ' ').replace(CHAR_CR, ' ').trim()
                    if (clean.length > 600) clean = clean.substring(0, 600)
                    if (clean.length > 1) items.add(Item(clean, r))
                }
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i)
                if (child != null) walk(child, items, depth + 1)
            }
        } catch (e: Exception) { }
    }

    private fun filterParents(raw: List<Item>): List<Item> {
        val result = ArrayList<Item>()
        for (a in raw) {
            var isParent = false
            val aFlat = a.text.replace(" ", "")
            for (b in raw) {
                if (a === b) continue
                if (a.rect.width() >= b.rect.width() && a.rect.height() > b.rect.height()
                    && a.rect.contains(b.rect)
                    && aFlat.contains(b.text.replace(" ", ""))
                    && a.text.length > b.text.length
                ) {
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
                if (old.text == item.text && overlapPercent(item.rect, old.rect) > 60) {
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

    private fun packRects(list: List<Item>): List<Item> {
        val bySize = list.sortedWith(Comparator { a, b ->
            val areaA = a.rect.width().toLong() * a.rect.height().toLong()
            val areaB = b.rect.width().toLong() * b.rect.height().toLong()
            if (areaA < areaB) -1 else if (areaA > areaB) 1 else 0
        })

        val placed = ArrayList<Item>()
        for (cand in bySize) {
            val r = Rect(cand.rect)
            var ok = true
            var guard = 0
            while (guard < 40) {
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
            if (!ok) continue
            if (r.width() < minBoxWidth || r.height() < minBoxHeight) continue

            var clash = false
            for (p in placed) {
                if (Rect.intersects(r, p.rect)) {
                    clash = true
                    break
                }
            }
            if (clash) continue
            placed.add(Item(cand.text, r))
        }

        return placed.sortedWith(Comparator { a, b ->
            if (a.rect.top != b.rect.top) a.rect.top - b.rect.top else a.rect.left - b.rect.left
        })
    }

    private fun callWithRotation(items: List<Item>) {
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

        val prompt = buildPrompt(items)
        val totalCombos = keys.size * models.size
        var attempts = 0
        var resultText: String? = null
        var lastCode = 0

        while (attempts < totalCombos && resultText == null) {
            val key = keys[currentKeyIndex % keys.size]
            val model = models[currentModelIndex % models.size]
            saveCurrentCombo(key, model)
            val result = callGeminiText(key, model, prompt)
            if (result.first != null) {
                resultText = result.first
            } else {
                lastCode = result.second
                moveToNextCombo(keys.size, models.size)
                attempts++
                try {
                    Thread.sleep(300)
                } catch (e: Exception) { }
            }
        }

        if (resultText != null) {
            val finalText = resultText
            saveError("")
            handler.post {
                showOverlay(finalText, items)
                isWorking = false
            }
        } else {
            saveError("HTTP " + lastCode)
            handler.post {
                resetWork()
                toast(getString(R.string.msg_failed))
            }
        }
    }

    private fun buildPrompt(items: List<Item>): String {
        val nl = CHAR_NL.toString()
        val sb = StringBuilder()
        sb.append("তুমি একজন দক্ষ অনুবাদক। নিচের নম্বর দেওয়া লেখাগুলো যেকোনো ভাষা থেকে সহজ ও স্বাভাবিক বাংলায় অনুবাদ করো।").append(nl)
        sb.append("নিয়মগুলো মানবে:").append(nl)
        sb.append("১) প্রতিটি নম্বরের জন্য আলাদা অনুবাদ দেবে, নম্বর হুবহু মিলিয়ে দেবে।").append(nl)
        sb.append("২) সংখ্যা, সময়, তারিখ, ইমোজি, ইউজারনেম, লিংক ও ব্র্যান্ডের নাম অপরিবর্তিত রাখবে।").append(nl)
        sb.append("৩) লেখা আগেই বাংলা হলে হুবহু সেটাই ফেরত দেবে।").append(nl)
        sb.append("৪) অনুবাদ যতটা সম্ভব ছোট রাখবে, মূল লেখার চেয়ে বড় করবে না।").append(nl)
        sb.append("৫) কোনো ব্যাখ্যা, মন্তব্য বা মার্কডাউন দেবে না।").append(nl)
        sb.append("শুধু এই গঠনে JSON দেবে: translations নামের একটি array, প্রতিটি item এ id (সংখ্যা) এবং translated_text (string)।").append(nl)
        sb.append("লেখাগুলো:").append(nl)
        for (i in items.indices) {
            sb.append(i + 1).append(". ").append(items[i].text).append(nl)
        }
        return sb.toString()
    }

    private fun callGeminiText(key: String, model: String, prompt: String): Pair<String?, Int> {
        try {
            val json = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.2)
                    put("responseMimeType", "application/json")
                })
            }

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
                    val content = candidates.getJSONObject(0).optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null) {
                        val sb = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            if (part.optBoolean("thought", false)) continue
                            val piece = part.optString("text", "")
                            if (piece.isNotBlank()) sb.append(piece)
                        }
                        var text = sb.toString()
                        val s = text.indexOf('{')
                        val e = text.lastIndexOf('}')
                        if (s >= 0 && e > s) text = text.substring(s, e + 1)
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

    private fun showOverlay(responseText: String, items: List<Item>) {
        try {
            val json = JSONObject(responseText)
            val translations = json.optJSONArray("translations")
            if (translations == null) {
                resetWork()
                toast(getString(R.string.msg_failed))
                return
            }

            val byId = HashMap<Int, String>()
            for (i in 0 until translations.length()) {
                val entry = translations.opt(i)
                if (entry is JSONObject) {
                    val id = entry.optInt("id", i + 1)
                    val tr = entry.optString("translated_text", "")
                    if (tr.isNotBlank()) byId[id] = tr
                }
            }

            val entries = ArrayList<OverlayView.Entry>()
            for (i in items.indices) {
                var translated = byId[i + 1]
                if (translated == null && i < translations.length()) {
                    val entry = translations.opt(i)
                    if (entry is JSONObject) {
                        translated = entry.optString("translated_text", "")
                    } else if (entry != null) {
                        translated = entry.toString()
                    }
                }
                if (translated.isNullOrBlank()) continue
                val clean = translated.replace(CHAR_NL, ' ').replace(CHAR_CR, ' ').trim()
                if (clean.isBlank()) continue
                if (clean == items[i].text) continue
                entries.add(OverlayView.Entry(clean, items[i].rect))
            }

            if (entries.isEmpty()) {
                resetWork()
                toast(getString(R.string.msg_no_text))
                return
            }

            overlayView?.setEntries(entries)
            overlayView?.visibility = View.VISIBLE
            isOverlayShowing = true
            overlayShownAt = System.currentTimeMillis()
            buttonView?.visibility = View.VISIBLE
            setButtonColor("#4CAF50")
        } catch (e: Exception) {
            resetWork()
            toast(getString(R.string.msg_failed))
        }
    }

    private fun resetWork() {
        isWorking = false
        setButtonColor("#2196F3")
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
    }
}
