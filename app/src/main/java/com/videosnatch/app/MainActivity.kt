package com.videosnatch.app

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.videosnatch.app.parser.ParseResult
import com.videosnatch.app.parser.RuleEngine
import com.videosnatch.app.parser.RuleRepository
import com.videosnatch.app.parser.RuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var etLink: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvRuleInfo: TextView
    private lateinit var btnParse: Button
    private lateinit var btnDownload: Button

    private lateinit var repo: RuleRepository
    private var rules: RuleSet? = null
    private var result: ParseResult? = null
    private val logText = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etLink = findViewById(R.id.etLink)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        tvRuleInfo = findViewById(R.id.tvRuleInfo)
        btnParse = findViewById(R.id.btnParse)
        btnDownload = findViewById(R.id.btnDownload)
        tvLog.movementMethod = ScrollingMovementMethod()

        repo = RuleRepository(this)
        if (rules == null) rules = repo.load()
        updateRuleInfo()

        findViewById<Button>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.btnRules).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener { copyLog() }
        btnParse.setOnClickListener {
            val text = etLink.text.toString().trim()
            if (text.isEmpty()) toast("先把分享链接粘贴进来") else doParse(text)
        }
        btnDownload.setOnClickListener { doDownload() }

        askNotificationPermission()
        handleIncoming(intent)
        fetchRules(silent = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    // ---------- 解析 ----------

    private fun doParse(text: String) {
        val rs = rules ?: repo.load().also { rules = it }
        btnParse.isEnabled = false
        btnDownload.isEnabled = false
        result = null
        tvStatus.text = "正在解析…"
        logText.clear()
        tvLog.text = ""
        log("开始解析")

        lifecycleScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val engine = RuleEngine { msg -> runOnUiThread { log(msg) } }
                engine.parse(rs, text)
            }
            withContext(Dispatchers.Main) {
                btnParse.isEnabled = true
                res.onSuccess { r ->
                    result = r
                    btnDownload.isEnabled = true
                    tvStatus.text = "解析成功（${r.provider}）\n${r.title.ifBlank { "(无标题)" }}\n${r.url}"
                }.onFailure { e ->
                    tvStatus.text = "解析失败：${e.message}\n\n把日志复制给维护规则的人，改一下 rules.json 就能恢复。"
                }
            }
        }
    }

    // ---------- 下载 ----------

    private fun doDownload() {
        val r = result
        if (r == null) {
            toast("先解析出视频地址")
            return
        }
        val name = safeFileName(r.title) + "_" + System.currentTimeMillis() + ".mp4"
        val request = DownloadManager.Request(Uri.parse(r.url))
        if (r.headers.isNotEmpty()) {
            r.headers.forEach { (k, v) -> request.addRequestHeader(k, v) }
        } else {
            request.addRequestHeader("User-Agent", DEFAULT_UA)
        }
        request.setTitle(if (r.title.isBlank()) name else r.title)
        request.setDescription("VideoSnatch")
        request.setMimeType("video/mp4")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "VideoSnatch/$name")
        request.allowScanningByMediaScanner()

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { dm.enqueue(request) }
            .onSuccess { toast("已开始下载，看通知栏") }
            .onFailure { toast("下载失败：${it.message}") }
        tvStatus.append("\n\n已开始下载 → 相册/Movies/VideoSnatch/$name")
    }

    // ---------- 规则更新 ----------

    private fun rulesUrl(): String {
        val saved = prefs().getString(KEY_URL, null)
        return if (saved.isNullOrBlank()) DEFAULT_RULES_URL else saved
    }

    private fun fetchRules(silent: Boolean) {
        val url = rulesUrl()
        if (!url.startsWith("http") || url.contains("CHANGE_ME")) {
            if (!silent) toast("先填 GitHub 用户名，或填规则文件直链")
            log("未设置远程规则地址，正在用内置规则。点『规则设置』填一下就能自动更新。")
            return
        }
        if (!silent) log("正在更新规则…")
        lifecycleScope.launch(Dispatchers.IO) {
            val res = runCatching { repo.fetch(url) }
            withContext(Dispatchers.Main) {
                res.onSuccess { rs ->
                    rules = rs
                    updateRuleInfo()
                    log("规则已更新：v${rs.version} ${rs.updated ?: ""}")
                }.onFailure { e ->
                    log("规则更新失败：${e.message}（继续用当前规则）")
                }
            }
        }
    }

    private fun showSettings() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val tip = TextView(this).apply {
            text = "填你的 GitHub 用户名就行（推荐）。填好后 App 每次打开都会自动拉取最新的 rules.json，接口失效时改那个文件即可，不用重装 App。"
            textSize = 13f
            setPadding(0, 0, 0, 12)
        }
        val etUser = EditText(this).apply {
            hint = "GitHub 用户名"
            setText(prefs().getString(KEY_USER, "") ?: "")
        }
        val etUrl = EditText(this).apply {
            hint = "规则文件直链（可留空）"
            val cur = rulesUrl()
            setText(if (cur.contains("CHANGE_ME")) "" else cur)
        }
        container.addView(tip)
        container.addView(etUser)
        container.addView(etUrl)

        AlertDialog.Builder(this)
            .setTitle("规则设置")
            .setView(container)
            .setPositiveButton("保存并更新") { _, _ ->
                val user = etUser.text.toString().trim()
                var url = etUrl.text.toString().trim()
                if (url.isEmpty() && user.isNotEmpty()) {
                    url = "https://raw.githubusercontent.com/$user/VideoSnatch/main/rules.json"
                }
                if (url.isNotEmpty()) {
                    prefs().edit().putString(KEY_URL, url).putString(KEY_USER, user).apply()
                }
                updateRuleInfo()
                fetchRules(silent = false)
            }
            .setNeutralButton("用回内置规则") { _, _ ->
                repo.clearCache()
                prefs().edit().remove(KEY_URL).apply()
                rules = repo.builtIn()
                updateRuleInfo()
                log("已恢复内置规则 v${rules?.version}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateRuleInfo() {
        val rs = rules
        tvRuleInfo.text = if (rs == null) "规则未加载" else "规则版本 v${rs.version}　更新于 ${rs.updated ?: "-"}　共 ${rs.providers.size} 条解析方案"
    }

    // ---------- 杂项 ----------

    private fun handleIncoming(intent: Intent?) {
        val shared: String? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!shared.isNullOrBlank()) {
            etLink.setText(shared)
            doParse(shared)
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            toast("剪贴板是空的")
        } else {
            etLink.setText(text)
            doParse(text)
        }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", tvLog.text.toString()))
        toast("日志已复制")
    }

    private fun log(msg: String) {
        logText.append(msg).append("\n")
        tvLog.text = logText.toString()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun prefs() = getSharedPreferences(PREF, MODE_PRIVATE)

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun safeFileName(title: String): String {
        val cleaned = title.replace(Regex("[/\\\\:*?\"<>|\n\r\t]"), "_").trim()
        return if (cleaned.isBlank()) "VideoSnatch" else cleaned.take(40)
    }

    companion object {
        private const val PREF = "videosnatch"
        private const val KEY_URL = "rulesUrl"
        private const val KEY_USER = "ghUser"
        private const val DEFAULT_RULES_URL =
            "https://raw.githubusercontent.com/CHANGE_ME/VideoSnatch/main/rules.json"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
