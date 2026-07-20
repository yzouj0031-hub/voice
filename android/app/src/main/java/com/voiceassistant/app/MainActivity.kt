package com.voiceassistant.app

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 语音助手主界面。
 * 界面用网页（assets 里的 HTML/CSS/JS）实现，语音识别、语音合成、网络请求
 * 都由这里的原生代码完成，通过 window.Native 桥接暴露给网页。
 * 网络请求走原生，彻底绕开 WebView 的跨域限制，兼容任意 AI 接口。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // 供后台唤醒服务判断：App 在前台时，服务不抢麦克风
        @Volatile var appInForeground = false

        // 权限请求码
        private const val REQ_MIC = 1
        private const val REQ_NOTIF = 2
        private const val REQ_CONTACTS = 3
        private const val REQ_CALL = 4
    }

    // 因缺权限（如读取通讯录）而挂起的动作，授予后自动重试
    @Volatile private var pendingAction: JSONObject? = null

    private lateinit var webView: WebView
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingUtterances = AtomicInteger(0)

    private val chatSeq = AtomicInteger(0)
    @Volatile private var currentConn: HttpURLConnection? = null

    private var pageLoaded = false
    private var pendingWake = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.addJavascriptInterface(Bridge(), "Native")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                if (pendingWake) { pendingWake = false; triggerAutoStart() }
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webView.loadUrl("file:///android_asset/index.html")

        initTts()
        ensureMicPermission()
        handleWakeIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("wake", false) == true) {
            if (pageLoaded) triggerAutoStart() else pendingWake = true
        }
    }

    // 被唤醒后自动开始一轮对话（稍作延迟，确保唤醒服务已释放麦克风）
    private fun triggerAutoStart() {
        main.postDelayed({ dispatch("autoStartConversation") }, 600)
    }

    override fun onResume() {
        super.onResume()
        appInForeground = true
        // 从"修改系统设置"页返回后，若已授权则自动重试挂起的亮度调节
        pendingAction?.let { a ->
            if (a.optString("tool") == "set_brightness" && Settings.System.canWrite(this)) {
                pendingAction = null
                runActions(JSONArray().put(a).toString())
            }
        }
    }

    override fun onStop() {
        appInForeground = false
        super.onStop()
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------- 语音合成 ----------------
    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                ttsReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (pendingUtterances.decrementAndGet() <= 0) dispatch("onTtsDone")
            }
            @Deprecated("deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                if (pendingUtterances.decrementAndGet() <= 0) dispatch("onTtsDone")
            }
        })
    }

    // ---------------- JS 桥接 ----------------
    inner class Bridge {
        @JavascriptInterface
        fun startListening() = main.post { startRecognition() }

        @JavascriptInterface
        fun stopListening() = main.post { recognizer?.stopListening() }

        @JavascriptInterface
        fun speak(text: String) = main.post {
            if (!ttsReady || text.isBlank()) return@post
            pendingUtterances.incrementAndGet()
            dispatch("onTtsStart")
            val id = "u" + System.nanoTime()
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        }

        @JavascriptInterface
        fun stopSpeaking() = main.post {
            tts?.stop()
            pendingUtterances.set(0)
            dispatch("onTtsDone")
        }

        @JavascriptInterface
        fun chat(payloadJson: String) {
            val seq = chatSeq.incrementAndGet()
            currentConn?.disconnect()
            net.execute { runChat(payloadJson, seq) }
        }

        // ---- 语音唤醒 ----
        @JavascriptInterface
        fun setWakeEnabled(enabled: Boolean, word: String) = main.post {
            if (enabled) {
                if (!hasMic()) {   // 没有麦克风权限时先申请，避免前台服务因缺权限崩溃
                    ensureMicPermission()
                    dispatch("onSpeechError", "请先允许麦克风权限，再开启语音唤醒。")
                    return@post
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
                    )
                }
                val i = Intent(this@MainActivity, WakeService::class.java).apply {
                    action = WakeService.ACTION_START
                    putExtra(WakeService.EXTRA_WORD, word)
                }
                ContextCompat.startForegroundService(this@MainActivity, i)
            } else {
                val i = Intent(this@MainActivity, WakeService::class.java).apply {
                    action = WakeService.ACTION_STOP
                }
                ContextCompat.startForegroundService(this@MainActivity, i)
            }
        }

        @JavascriptInterface
        fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this@MainActivity)

        @JavascriptInterface
        fun openOverlaySettings() = main.post {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try { startActivity(i) } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }

        // ---- 助手动作：打电话 / 闹钟 / 定时器 / 短信 / 开应用 / 导航 / 搜索 / 日历 ----
        // 网页把 AI 解析出的动作数组（JSON）发过来，这里逐个用安卓原生能力执行。
        @JavascriptInterface
        fun performActions(json: String) = main.post { runActions(json) }

        // ---- 拉取模型列表 / 测试连接（都走原生网络，绕开跨域）----
        @JavascriptInterface
        fun listModels(payloadJson: String) = net.execute { runListModels(payloadJson) }

        @JavascriptInterface
        fun testConnection(payloadJson: String) = net.execute { runTest(payloadJson) }
    }

    private fun runActions(json: String) {
        val arr = try { JSONArray(json) } catch (_: Exception) { return }
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            try {
                when (a.optString("tool")) {
                    "call" -> doCall(a)
                    "set_alarm" -> doSetAlarm(a)
                    "set_timer" -> doSetTimer(a)
                    "send_sms" -> doSendSms(a)
                    "open_app" -> doOpenApp(a)
                    "navigate" -> doNavigate(a)
                    "search_web" -> doSearchWeb(a)
                    "add_calendar" -> doAddCalendar(a)
                    "set_volume" -> doSetVolume(a)
                    "set_brightness" -> doSetBrightness(a)
                    "flashlight" -> doFlashlight(a)
                    else -> {}
                }
            } catch (e: Exception) {
                actionResult("这个操作没能完成：${e.message ?: "未知错误"}", true)
            }
        }
    }

    // 把动作结果回传给网页（显示为系统气泡；speak=true 时还会朗读出来，用于失败或需要用户注意的情况）
    private fun actionResult(msg: String, speak: Boolean) {
        val obj = JSONObject().put("msg", msg).put("speak", speak)
        dispatch("onActionResult", obj.toString())
    }

    private fun tryStart(intent: Intent, okMsg: String, speakOk: Boolean, failMsg: String) {
        try {
            startActivity(intent)
            actionResult(okMsg, speakOk)
        } catch (_: ActivityNotFoundException) {
            actionResult(failMsg, true)
        } catch (e: Exception) {
            actionResult("${failMsg}（${e.message}）", true)
        }
    }

    // ---- 打电话 ----
    // 有 CALL_PHONE 权限就直接拨；没有就打开拨号盘并预填号码，用户点一下即可拨出。
    private fun doCall(a: JSONObject) {
        var number = a.optString("number").trim()
        val name = a.optString("name").trim()
        if (number.isEmpty() && name.isNotEmpty()) {
            val looked = lookupContactNumber(name)
            if (looked == null) {
                // lookupContactNumber 在缺少权限时会申请权限并返回 null，这里给出提示
                if (hasContacts()) actionResult("通讯录里没找到“$name”。", true)
                else {
                    pendingAction = a
                    actionResult("请允许读取通讯录后，再说一次要打给谁。", true)
                }
                return
            }
            number = looked
        }
        if (number.isEmpty()) { actionResult("不知道要打给谁呢。", true); return }
        val label = if (name.isNotEmpty()) name else number
        val uri = Uri.parse("tel:" + Uri.encode(number))
        if (hasCallPhone()) {
            tryStart(Intent(Intent.ACTION_CALL, uri), "正在拨打 $label", false, "拨号失败。")
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
            // 没权限时先用拨号盘兜底，用户点绿色按钮即可拨出
            tryStart(Intent(Intent.ACTION_DIAL, uri), "已为你拨号 $label，点绿色按钮拨出。", true, "无法打开拨号盘。")
        }
    }

    // ---- 闹钟 ----
    private fun doSetAlarm(a: JSONObject) {
        val hour = a.optInt("hour", -1)
        val minute = a.optInt("minute", 0)
        if (hour !in 0..23 || minute !in 0..59) { actionResult("闹钟时间没听清。", true); return }
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            val msg = a.optString("message")
            if (msg.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, msg)
        }
        tryStart(i, "已设闹钟 %02d:%02d".format(hour, minute), false, "手机上没有可用的闹钟应用。")
    }

    // ---- 定时器 ----
    private fun doSetTimer(a: JSONObject) {
        val seconds = a.optInt("seconds", 0)
        if (seconds <= 0) { actionResult("定时时长没听清。", true); return }
        val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            val msg = a.optString("message")
            if (msg.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, msg)
        }
        val mm = seconds / 60
        val ss = seconds % 60
        val human = if (mm > 0) "${mm}分${if (ss > 0) "${ss}秒" else ""}" else "${ss}秒"
        tryStart(i, "已设 $human 定时器", false, "手机上没有可用的定时器应用。")
    }

    // ---- 发短信（打开短信应用并预填，用户确认后发送，更安全）----
    private fun doSendSms(a: JSONObject) {
        var number = a.optString("number").trim()
        val name = a.optString("name").trim()
        if (number.isEmpty() && name.isNotEmpty()) {
            val looked = lookupContactNumber(name)
            if (looked == null) {
                if (hasContacts()) actionResult("通讯录里没找到“$name”。", true)
                else { pendingAction = a; actionResult("请允许读取通讯录后，再说一次。", true) }
                return
            }
            number = looked
        }
        if (number.isEmpty()) { actionResult("不知道要发给谁呢。", true); return }
        val label = if (name.isNotEmpty()) name else number
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).apply {
            val body = a.optString("body")
            if (body.isNotEmpty()) putExtra("sms_body", body)
        }
        tryStart(i, "已写好给 $label 的短信，确认后发送。", true, "无法打开短信应用。")
    }

    // ---- 打开应用（按名字匹配已安装应用）----
    private fun doOpenApp(a: JSONObject) {
        val appName = a.optString("app").trim()
        if (appName.isEmpty()) { actionResult("要打开哪个应用呢？", true); return }
        val pkg = findPackageByLabel(appName)
        if (pkg == null) { actionResult("没找到应用“$appName”。", true); return }
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) { actionResult("“$appName”无法打开。", true); return }
        tryStart(launch, "正在打开 $appName", false, "“$appName”无法打开。")
    }

    // ---- 导航 / 地图 ----
    private fun doNavigate(a: JSONObject) {
        val dest = a.optString("destination").trim()
        if (dest.isEmpty()) { actionResult("要去哪里呢？", true); return }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest)))
        tryStart(i, "正在打开地图：$dest", false, "手机上没有可用的地图应用。")
    }

    // ---- 网页搜索 ----
    private fun doSearchWeb(a: JSONObject) {
        val q = a.optString("query").trim()
        if (q.isEmpty()) { actionResult("要搜什么呢？", true); return }
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        try {
            startActivity(search)
            actionResult("正在搜索：$q", false)
        } catch (_: Exception) {
            // 兜底：直接用浏览器打开搜索结果页
            val url = "https://www.bing.com/search?q=" + Uri.encode(q)
            tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "正在搜索：$q", false, "无法打开浏览器。")
        }
    }

    // ---- 加日历（打开日历新建事件，用户确认保存）----
    private fun doAddCalendar(a: JSONObject) {
        val title = a.optString("title").trim()
        if (title.isEmpty()) { actionResult("这个日程叫什么呢？", true); return }
        val i = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
        val hour = a.optInt("hour", -1)
        if (hour in 0..23) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, a.optInt("minute", 0))
                set(Calendar.SECOND, 0)
                // 若时间已过则顺延到明天
                if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
            }
            i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
            i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cal.timeInMillis + 60 * 60 * 1000)
        }
        tryStart(i, "已为你新建日程“$title”，确认后保存。", true, "手机上没有可用的日历应用。")
    }

    // ---- 调音量（媒体音量，无需权限）----
    // 支持 level（0~100）或 action: up / down / mute
    private fun doSetVolume(a: JSONObject) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        val flags = AudioManager.FLAG_SHOW_UI
        when {
            a.has("level") -> {
                val level = a.optInt("level").coerceIn(0, 100)
                am.setStreamVolume(stream, Math.round(level / 100.0 * max).toInt(), flags)
                actionResult("音量已调到 $level%", false)
            }
            a.optString("action") == "mute" -> {
                am.setStreamVolume(stream, 0, flags); actionResult("已静音", false)
            }
            a.optString("action") == "up" -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags); actionResult("已调高音量", false)
            }
            a.optString("action") == "down" -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags); actionResult("已调低音量", false)
            }
            else -> actionResult("音量指令没听清。", true)
        }
    }

    // ---- 调屏幕亮度（需"修改系统设置"权限）----
    private fun doSetBrightness(a: JSONObject) {
        if (!Settings.System.canWrite(this)) {
            pendingAction = a
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))
            }
            actionResult("请允许“修改系统设置”后返回，再说一次调节亮度。", true)
            return
        }
        val level = a.optInt("level", -1)
        if (level !in 0..100) { actionResult("亮度没听清（请说 0 到 100）。", true); return }
        // 关闭自动亮度，再设置手动亮度（系统亮度范围 0~255，至少留 1 避免全黑）
        Settings.System.putInt(
            contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        val v = Math.round(level / 100.0 * 255).toInt().coerceIn(1, 255)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
        actionResult("亮度已调到 $level%", false)
    }

    // ---- 开关手电筒（Camera2 手电模式，无需相机权限）----
    private fun doFlashlight(a: JSONObject) {
        val on = a.optBoolean("on", true)
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) { actionResult("这台手机好像没有闪光灯。", true); return }
        cm.setTorchMode(id, on)
        actionResult(if (on) "已打开手电筒" else "已关闭手电筒", false)
    }

    // ---- 联系人查询 ----
    private fun hasContacts() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCallPhone() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    // 按姓名（模糊）查联系人号码；无权限时申请权限并返回 null
    private fun lookupContactNumber(name: String): String? {
        if (!hasContacts()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
            return null
        }
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        // 先精确匹配，取不到再模糊匹配
        for (selectionName in listOf(name, "%$name%")) {
            val sel = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, sel, arrayOf(selectionName), null
            )?.use { c ->
                if (c.moveToFirst()) return c.getString(0)?.replace(" ", "")
            }
        }
        return null
    }

    // 按应用名（先精确后模糊）在已安装应用中找包名
    private fun findPackageByLabel(label: String): String? {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
        var fuzzy: String? = null
        for (app in apps) {
            val appLabel = pm.getApplicationLabel(app).toString()
            if (appLabel.equals(label, ignoreCase = true)) return app.packageName
            if (fuzzy == null && pm.getLaunchIntentForPackage(app.packageName) != null &&
                appLabel.contains(label, ignoreCase = true)
            ) {
                fuzzy = app.packageName
            }
        }
        return fuzzy
    }

    // ---------------- 语音识别（对话） ----------------
    private fun startRecognition() {
        if (!hasMic()) {
            ensureMicPermission()
            dispatch("onSpeechError", "没有麦克风权限，请在系统设置里允许。")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            dispatch("onSpeechError", "系统没有可用的语音识别服务（需安装/启用 Google 语音）。")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = dispatch("onSpeechStart")
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onPartialResults(partial: Bundle?) {
                    firstResult(partial)?.let { dispatch("onSpeechPartial", it) }
                }

                override fun onResults(results: Bundle?) {
                    val text = firstResult(results) ?: ""
                    dispatch("onSpeechResult", text)
                    dispatch("onSpeechEnd")
                }

                override fun onError(error: Int) {
                    dispatch("onSpeechEnd")
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，请再点一次麦克风。"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限。"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别需要联网，请检查网络。"
                        else -> null
                    }
                    if (msg != null) dispatch("onSpeechError", msg)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(intent)
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    // ---------------- 聊天：原生流式请求 ----------------
    private fun runChat(payloadJson: String, seq: Int) {
        fun alive() = seq == chatSeq.get()
        try {
            val p = JSONObject(payloadJson)
            val provider = p.optString("provider", "openai")
            val baseUrl = p.optString("baseUrl").trimEnd('/')
            val apiKey = p.optString("apiKey")
            val model = p.optString("model")
            val system = p.optString("system")
            val messages = p.optJSONArray("messages") ?: JSONArray()

            if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
                if (alive()) dispatch("onChatError", "请先在设置里填写接口地址、密钥和模型。")
                return
            }

            val isAnthropic = provider == "anthropic"
            val url = URL(if (isAnthropic) "$baseUrl/v1/messages" else "$baseUrl/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20000
                readTimeout = 120000
                setRequestProperty("Content-Type", "application/json")
                if (isAnthropic) {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                } else {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            currentConn = conn

            val body = if (isAnthropic) {
                JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 1024)
                    put("stream", true)
                    if (system.isNotEmpty()) put("system", system)
                    put("messages", messages)
                }
            } else {
                val msgs = JSONArray()
                if (system.isNotEmpty()) {
                    msgs.put(JSONObject().put("role", "system").put("content", system))
                }
                for (i in 0 until messages.length()) msgs.put(messages.get(i))
                JSONObject().apply {
                    put("model", model)
                    put("stream", true)
                    put("messages", msgs)
                }
            }

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (alive()) dispatch("onChatError", "接口返回 $code：${err.take(300)}")
                return
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (!alive()) return
                    val t = line!!.trim()
                    if (!t.startsWith("data:")) continue
                    val data = t.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (!isAnthropic && data == "[DONE]") break
                    val delta = if (isAnthropic) parseAnthropic(data) else parseOpenAI(data)
                    if (!delta.isNullOrEmpty()) dispatch("onChatDelta", delta)
                }
            }
            if (alive()) dispatch("onChatDone")
        } catch (e: Exception) {
            if (alive()) dispatch("onChatError", "请求失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            currentConn = null
        }
    }

    private fun parseOpenAI(data: String): String? = try {
        JSONObject(data).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("delta")?.optString("content")
    } catch (_: Exception) { null }

    private fun parseAnthropic(data: String): String? = try {
        val obj = JSONObject(data)
        if (obj.optString("type") == "content_block_delta") {
            val d = obj.optJSONObject("delta")
            if (d?.optString("type") == "text_delta") d.optString("text") else null
        } else null
    } catch (_: Exception) { null }

    // ---------------- 拉取模型列表 ----------------
    // OpenAI 兼容：GET {baseUrl}/models；Anthropic：GET {baseUrl}/v1/models；两者返回都形如 {"data":[{"id":...}]}
    private fun runListModels(payloadJson: String) {
        try {
            val p = JSONObject(payloadJson)
            val provider = p.optString("provider", "openai")
            val baseUrl = p.optString("baseUrl").trimEnd('/')
            val apiKey = p.optString("apiKey")
            if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                dispatch("onModelsError", "请先填写接口地址和密钥。"); return
            }
            val isAnthropic = provider == "anthropic"
            val conn = (URL(if (isAnthropic) "$baseUrl/v1/models" else "$baseUrl/models")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                if (isAnthropic) {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                } else {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream))
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                dispatch("onModelsError", "接口返回 $code：${text.take(200)}"); return
            }
            val ids = JSONArray()
            JSONObject(text).optJSONArray("data")?.let { data ->
                for (i in 0 until data.length()) {
                    data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { ids.put(it) }
                }
            }
            if (ids.length() == 0) dispatch("onModelsError", "接口没有返回模型列表，请手动填写模型名。")
            else dispatch("onModelsResult", ids.toString())
        } catch (e: Exception) {
            dispatch("onModelsError", "拉取失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------------- 测试连接 ----------------
    // 用当前配置发一条极短的请求，验证地址 / 密钥 / 模型是否都可用
    private fun runTest(payloadJson: String) {
        fun result(ok: Boolean, msg: String) =
            dispatch("onTestResult", JSONObject().put("ok", ok).put("msg", msg).toString())
        try {
            val p = JSONObject(payloadJson)
            val provider = p.optString("provider", "openai")
            val baseUrl = p.optString("baseUrl").trimEnd('/')
            val apiKey = p.optString("apiKey")
            val model = p.optString("model")
            if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
                result(false, "请先填写接口地址、密钥和模型。"); return
            }
            val isAnthropic = provider == "anthropic"
            val conn = (URL(if (isAnthropic) "$baseUrl/v1/messages" else "$baseUrl/chat/completions")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Content-Type", "application/json")
                if (isAnthropic) {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                } else {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 8)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "你好")))
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                result(true, "连接正常，模型「$model」可用 ✓")
            } else {
                val err = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
                result(false, "接口返回 $code：${err.take(200)}")
            }
        } catch (e: Exception) {
            result(false, "连接失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------------- 调用网页里的回调 ----------------
    private fun dispatch(func: String, arg: String? = null) {
        val call = if (arg == null) "window.$func && window.$func();"
        else "window.$func && window.$func(${JSONObject.quote(arg)});"
        main.post { webView.evaluateJavascript(call, null) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        // 授予通讯录/电话权限后，自动把之前挂起的动作再执行一次
        if (granted && (requestCode == REQ_CONTACTS || requestCode == REQ_CALL)) {
            pendingAction?.let { a ->
                pendingAction = null
                main.post { runActions(JSONArray().put(a).toString()) }
            }
        }
    }

    override fun onBackPressed() {
        webView.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false") { result ->
            if (result != "true") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        currentConn?.disconnect()
        net.shutdownNow()
        super.onDestroy()
    }
}
