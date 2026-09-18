package com.example.screentranslator

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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

class OverlayView : View {
    data class Entry(val text: String, val orig: String, val rect: Rect)

    private var entries: List<Entry> = emptyList()
    private val bgPaint = Paint().apply { color = Color.rgb(22, 22, 22) }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }

    constructor(context: Context) : super(context)

    fun setEntries(list: List<Entry>) {
        entries = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.isEmpty()) return

        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val offX = loc[0]
        val offY = loc[1]

        val density = resources.displayMetrics.density
        val cap = 20f * density
        val inner = 3f * density

        for (en in entries) {
            val l = en.rect.left.toFloat() - offX
            val t = en.rect.top.toFloat() - offY
            val r = en.rect.right.toFloat() - offX
            val b = en.rect.bottom.toFloat() - offY
            val w = r - l
            val h = b - t
            if (w <= 0f || h <= 0f) continue

            canvas.drawRect(l, t, r, b, bgPaint)

            val usableW = w - inner * 2f
            if (usableW <= 0f) continue

            textPaint.textSize = cap
            val origLines = wrap(en.orig, textPaint, usableW).size
            val est = h / (origLines * 1.22f)
            var size = if (cap < est) cap else est
            if (size < 9f) size = 9f
            textPaint.textSize = size

            var lines = wrap(en.text, textPaint, usableW)
            while (lines.size * textPaint.textSize * 1.22f > h && textPaint.textSize > 8f) {
                textPaint.textSize = textPaint.textSize - 1f
                lines = wrap(en.text, textPaint, usableW)
            }

            val totalH = lines.size * textPaint.textSize * 1.22f
            var y = t + (h - totalH) / 2f + textPaint.textSize

            canvas.save()
            canvas.clipRect(l, t, r, b)
            for (line in lines) {
                canvas.drawText(line, l + inner, y, textPaint)
                y += textPaint.textSize * 1.22f
            }
            canvas.restore()
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (maxWidth <= 0f) return listOf(text)
        val words = ArrayList<String>()
        for (raw in text.split(' ')) {
            if (raw.isEmpty()) continue
            if (paint.measureText(raw) <= maxWidth) {
                words.add(raw)
            } else {
                val chunk = StringBuilder()
                for (ch in raw) {
                    val test = chunk.toString() + ch
                    if (paint.measureText(test) > maxWidth && chunk.isNotEmpty()) {
                        words.add(chunk.toString())
                        chunk.clear()
                    }
                    chunk.append(ch)
                }
                if (chunk.isNotEmpty()) words.add(chunk.toString())
            }
        }
        val lines = ArrayList<String>()
        val current = StringBuilder()
        for (w in words) {
            val test = if (current.isEmpty()) w else current.toString() + " " + w
            if (paint.measureText(test) > maxWidth && current.isNotEmpty()) {
                lines.add(current.toString())
                current.clear()
                current.append(w)
            } else {
                current.clear()
                current.append(test)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        if (lines.isEmpty()) lines.add(text)
        return lines
    }
}

class TranslatorAccessibilityService : AccessibilityService() {
    private var overlayView: OverlayView? = null
    private var buttonView: TextView? = null
    private var buttonBg: GradientDrawable? = null
    private var buttonParams: android.view.WindowManager.LayoutParams? = null
    private var windowManager: android.view.WindowManager? = null
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
    private var uiReady = false

    private var exitReceiver: BroadcastReceiver? = null

    private val longPressRunnable = Runnable {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("uiOff", true).apply()
        syncUiState()
        Toast.makeText(this, getString(R.string.msg_button_hidden), Toast.LENGTH_SHORT).show()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        minBoxHeight = dpToPx(11)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        setupOverlay()
        setupFloatingButton()
        uiReady = true
        syncUiState()

        exitReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.example.screentranslator.EXIT") {
                    shutdown()
                }
            }
        }
        val filter = IntentFilter("com.example.screentranslator.EXIT")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(exitReceiver, filter)
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
        val since = System.currentTimeMillis() - overlayShownAt
        if (since < 1000) return
        overlayView?.setEntries(emptyList())
        overlayView?.visibility = View.INVISIBLE
        isOverlayShowing = false
        setButtonColor("#2196F3")
    }

    private fun syncUiState() {
        if (!uiReady) return
        val off = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getBoolean("uiOff", false)
        if (off) {
            buttonView?.visibility = View.GONE
            overlayView?.visibility = View.INVISIBLE
            isOverlayShowing = false
        } else {
            buttonView?.visibility = View.VISIBLE
        }
    }

    private fun setupOverlay() {
        overlayView = OverlayView(this).apply {
            visibility = View.INVISIBLE
        }
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
            textSize = 14f
            gravity = Gravity.CENTER
            background = buttonBg
        }

        buttonSize = dpToPx(44)
        buttonParams = android.view.WindowManager.LayoutParams(
            buttonSize, buttonSize,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
                        if (!moved && dt < 500) {
                            onButtonTap()
                        }
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
            overlayView?.setEntries(emptyList())
            overlayView?.visibility = View.INVISIBLE
            isOverlayShowing = false
            setButtonColor("#2196F3")
        } else {
            translateNow()
        }
    }

    private fun translateNow() {
        isWorking = true
        setButtonColor("#FF9800")
        buttonView?.visibility = View.GONE
        overlayView?.setEntries(emptyList())
        overlayView?.visibility = View.INVISIBLE
        handler.postDelayed({ collectAndTranslate() }, 250)
    }

    private data class Item(val text: String, val rect: Rect)

    private fun collectAndTranslate() {
        val root = rootInActiveWindow
        if (root == null) {
            resetWork()
            Toast.makeText(this, getString(R.string.msg_no_screen), Toast.LENGTH_SHORT).show()
            return
        }
        if (root.packageName == packageName) {
            resetWork()
            Toast.makeText(this, getString(R.string.msg_own_app), Toast.LENGTH_LONG).show()
            return
        }

        val raw = ArrayList<Item>()
        walk(root, raw)

        val noParents = filterParents(raw)
        val unique = dedupe(noParents)
        val clean = resolveOverlaps(unique)
        val items = if (clean.size > 40) clean.subList(0, 40) else clean

        if (items.isEmpty()) {
            resetWork()
            Toast.makeText(this, getString(R.string.msg_no_text), Toast.LENGTH_SHORT).show()
            return
        }

        buttonView?.visibility = View.VISIBLE
        val finalItems = ArrayList<Item>(items)
        Thread { callWithRotation(finalItems) }.start()
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

    private fun resolveOverlaps(list: List<Item>): List<Item> {
        val bySize = list.sortedBy { it.rect.width().toLong() * it.rect.height().toLong() }
        val accepted = ArrayList<Item>()
        for (cand in bySize) {
            var keep = true
            for (old in accepted) {
                if (overlapPercent(cand.rect, old.rect) > 25) {
                    keep = false
                    break
                }
            }
            if (keep) accepted.add(cand)
        }

        val ordered = accepted.sortedWith(Comparator { a, b ->
            if (a.rect.top != b.rect.top) a.rect.top - b.rect.top else a.rect.left - b.rect.left
        })
        return separate(ordered)
    }

    private fun separate(list: List<Item>): List<Item> {
        val out = ArrayList<Item>()
        for (item in list) {
            val r = Rect(item.rect)
            for (old in out) {
                if (r.right <= old.rect.left || r.left >= old.rect.right) continue
                if (r.top >= old.rect.bottom || r.bottom <= old.rect.top) continue
                if (r.top >= old.rect.top) {
                    r.top = old.rect.bottom
                } else {
                    r.bottom = old.rect.top
                }
            }
            if (r.width() > 0 && r.height() >= minBoxHeight) {
                out.add(Item(item.text, r))
            }
        }
        return out
    }

    private fun walk(node: AccessibilityNodeInfo, items: ArrayList<Item>) {
        if (items.size >= 150) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrBlank() && t.length > 1 && node.isVisibleToUser) {
            val r = Rect()
            node.getBoundsInScreen(r)
            val screen = Rect(0, 0, screenWidth, screenHeight)
            if (r.intersect(screen) && r.width() > 0 && r.height() >= minBoxHeight) {
                var clean = t.replace(CHAR_NL, ' ')
                if (clean.length > 700) clean = clean.substring(0, 700)
                items.add(Item(clean, r))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                walk(child, items)
                child.recycle()
            }
        }
    }

    private fun callWithRotation(items: List<Item>) {
        val keys = getKeys()
        val models = getModels()

        if (keys.isEmpty() || models.isEmpty()) {
            handler.post {
                resetWork()
                saveError(getString(R.string.msg_no_key))
                Toast.makeText(this, getString(R.string.msg_no_key), Toast.LENGTH_SHORT).show()
            }
            return
        }

        val prompt = buildPrompt(items)
        val totalCombos = keys.size * models.size
        var attempts = 0
        var resultText: String? = null
        var lastCode = 0

        while (attempts < totalCombos && resultText == null) {
            val key = keys[currentKeyIndex]
            val model = models[currentModelIndex]
            saveCurrentCombo(key, model)
            val result = callGeminiText(key, model, prompt)
            if (result.first != null) {
                resultText = result.first
            } else {
                lastCode = result.second
                moveToNextCombo(keys.size, models.size)
                attempts++
                Thread.sleep(300)
            }
        }

        if (resultText != null) {
            val finalText = resultText
            handler.post {
                showOverlay(finalText, items)
                isOverlayShowing = true
                overlayShownAt = System.currentTimeMillis()
                isWorking = false
                setButtonColor("#4CAF50")
                saveError("")
            }
        } else {
            saveError("HTTP " + lastCode)
            handler.post {
                resetWork()
                Toast.makeText(this, getString(R.string.msg_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildPrompt(items: List<Item>): String {
        val nl = CHAR_NL.toString()
        val sb = StringBuilder()
        sb.append("তুমি একজন দক্ষ অনুবাদক। নিচের লাইনগুলো একটি মোবাইল স্ক্রিনের লেখা।").append(nl)
        sb.append("প্রতিটি লাইনকে সহজ ও স্বাভাবিক বাংলায় অনুবাদ করো।").append(nl)
        sb.append("নিয়ম: সংখ্যা, সময়, নাম, ইমেইল, লিংক, কোড ও ইউজারনেম অপরিবর্তিত রাখবে।").append(nl)
        sb.append("অনুবাদ যতটা সম্ভব ছোট রাখবে, কারণ মূল লেখার জায়গাতেই দেখানো হবে।").append(nl)
        sb.append("লেখা আগে থেকেই বাংলা হলে সেটি হুবহু ফেরত দেবে।").append(nl)
        sb.append("উত্তর হবে শুধুমাত্র valid JSON: translations নামে একটি array, ")
        sb.append("প্রতিটি item এ id এবং translated_text থাকবে।").append(nl)
        sb.append("id হবে নিচের লাইনের নম্বর। JSON ছাড়া অন্য কোনো লেখা দেবে না।").append(nl)
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
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + key
            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string()
            response.close()

            if (code == 200 && body != null) {
                val jsonResponse = JSONObject(body)
                val candidates = jsonResponse.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val content = candidates.getJSONObject(0).getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    if (parts.length() > 0) {
                        var text = parts.getJSONObject(0).getString("text")
                        val s = text.indexOf('{')
                        val e = text.lastIndexOf('}')
                        if (s >= 0 && e > s) {
                            text = text.substring(s, e + 1)
                        }
                        return Pair(text, 200)
                    }
                }
            }
            return Pair(null, code)
        } catch (e: Exception) {
            return Pair(null, -1)
        }
    }

    private fun showOverlay(responseText: String, items: List<Item>) {
        try {
            val json = JSONObject(responseText)
            val translations = json.getJSONArray("translations")

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
                if (translated == items[i].text) continue
                entries.add(OverlayView.Entry(translated, items[i].text, items[i].rect))
            }

            overlayView?.setEntries(entries)
            overlayView?.visibility = View.VISIBLE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resetWork() {
        isWorking = false
        buttonView?.visibility = View.VISIBLE
        setButtonColor("#2196F3")
    }

    private fun saveError(msg: String) {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
            .putString("lastError", msg).apply()
    }

    private fun setButtonColor(hex: String) {
        buttonBg?.setColor(Color.parseColor(hex))
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
        try {
            exitReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { }
        exitReceiver = null
        disableSelf()
    }

    private fun removeViews() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        try {
            buttonView?.let { wm.removeView(it) }
        } catch (e: Exception) { }
        try {
            overlayView?.let { wm.removeView(it) }
        } catch (e: Exception) { }
        buttonView = null
        overlayView = null
    }

    override fun onDestroy() {
        removeViews()
        try {
            exitReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object {
        private val CHAR_NL = Char(10)
    }
}
