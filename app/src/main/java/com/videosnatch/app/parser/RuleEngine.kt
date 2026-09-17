package com.videosnatch.app.parser

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class ParseResult(
    val url: String,
    val title: String = "",
    val cover: String = "",
    val headers: Map<String, String> = emptyMap(),
    val provider: String = ""
)

/**
 * 通用规则引擎：按 rules.json 里的步骤一步步执行。
 * 支持 http / regex / jsonpath / replace / decode / set / pick 七种步骤。
 */
class RuleEngine(private val log: (String) -> Unit = {}) {

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("Accept-Encoding", "gzip")
                .build()
            chain.proceed(req)
        }
        .build()

    @Throws(Exception::class)
    fun parse(rules: RuleSet, input: String): ParseResult {
        val text = input.trim()
        val link = extractLink(rules, text)
        log("链接：$link")

        val matched = rules.providers.filter { p ->
            p.match.any { pat ->
                runCatching { Regex(pat, RegexOption.IGNORE_CASE).containsMatchIn(text) }.getOrDefault(false)
            }
        }
        if (matched.isEmpty()) {
            throw RuntimeException("这条链接不认识，当前规则只支持：抖音 / 皮皮虾 / X")
        }

        val errors = mutableListOf<String>()
        for (p in matched) {
            log("尝试：${p.name}")
            val ctx = mutableMapOf("input" to text, "link" to link)
            try {
                for (s in p.steps) {
                    try {
                        exec(s, rules, ctx)
                    } catch (e: Exception) {
                        if (s.optional) {
                            log("  · 跳过可选步骤(${s.type})：${e.message}")
                        } else {
                            throw RuntimeException("步骤 ${s.type} 失败：${e.message}")
                        }
                    }
                }
                val out = p.output
                val url = render(out?.url ?: "{videoUrl}", ctx)
                if (!url.startsWith("http")) throw RuntimeException("没拿到视频地址")
                val title = render(out?.title ?: "", ctx)
                val cover = render(out?.cover ?: "", ctx)
                val headers = out?.downloadHeaders?.mapValues { render(it.value, ctx) } ?: emptyMap()
                log("  ✓ 成功：${p.name}")
                return ParseResult(url, title, cover, headers, p.name)
            } catch (e: Exception) {
                errors.add("${p.name}：${e.message}")
                log("  ✗ ${p.name} 失败：${e.message}")
            }
        }
        throw RuntimeException(errors.joinToString(" ｜ "))
    }

    private fun extractLink(rules: RuleSet, text: String): String {
        val pat = rules.extractUrl ?: DEFAULT_URL_PATTERN
        return runCatching { Regex(pat).find(text)?.value }.getOrNull() ?: text
    }

    private fun exec(s: Step, rules: RuleSet, ctx: MutableMap<String, String>) {
        when (s.type.lowercase()) {
            "http" -> execHttp(s, rules, ctx)
            "regex" -> execRegex(s, ctx)
            "jsonpath" -> execJsonPath(s, ctx)
            "replace" -> execReplace(s, ctx)
            "decode" -> execDecode(s, ctx)
            "set" -> execSet(s, ctx)
            "pick" -> execPick(s, ctx)
            else -> throw RuntimeException("不支持的步骤类型：${s.type}")
        }
    }

    private fun execHttp(s: Step, rules: RuleSet, ctx: MutableMap<String, String>) {
        val url = render(s.url ?: "", ctx)
        if (!url.startsWith("http")) throw RuntimeException("URL 无效：$url")
        val builder = Request.Builder().url(url)
        rules.defaultHeaders.forEach { (k, v) -> builder.header(k, render(v, ctx)) }
        s.headers.forEach { (k, v) -> builder.header(k, render(v, ctx)) }
        val method = (s.method ?: "GET").uppercase()
        if (method == "POST") {
            builder.post(render(s.body ?: "", ctx).toRequestBody(null))
        }
        val client = baseClient.newBuilder()
            .followRedirects(s.followRedirects)
            .followSslRedirects(s.followRedirects)
            .build()
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val name = s.save ?: "last"
            ctx[name] = body
            ctx[name + "_url"] = resp.request.url.toString()
            log("  → HTTP ${resp.code}，返回 ${body.length} 字符")
            if (!resp.isSuccessful) throw RuntimeException("服务器返回 ${resp.code}")
        }
    }

    private fun execRegex(s: Step, ctx: MutableMap<String, String>) {
        val src = ctx[s.from ?: ""] ?: ""
        val pats = s.patterns
        if (pats.isEmpty()) throw RuntimeException("缺少 pattern")
        for (pat in pats) {
            val m = Regex(pat, setOf(RegexOption.DOT_MATCHES_ALL)).find(src)
            if (m == null) continue
            if (s.saves.isNotEmpty()) {
                s.saves.forEachIndexed { i, name -> ctx[name] = m.groupValues.getOrNull(i + 1) ?: "" }
            } else {
                val name = s.save ?: throw RuntimeException("缺少 save")
                ctx[name] = m.groupValues.getOrNull(s.group) ?: ""
            }
            log("  → 正则命中 ${pat.take(48)}")
            return
        }
        throw RuntimeException("正则没匹配到：${pats.first().take(48)}")
    }

    private fun execJsonPath(s: Step, ctx: MutableMap<String, String>) {
        val src = ctx[s.from ?: ""] ?: ""
        val value = jsonPath(src, s.path ?: "")
        if (value.isNullOrBlank()) throw RuntimeException("JSON 里找不到 ${s.path}")
        ctx[s.save ?: throw RuntimeException("缺少 save")] = value
        log("  → JSON ${s.path} = ${value.take(60)}")
    }

    private fun execReplace(s: Step, ctx: MutableMap<String, String>) {
        val src = ctx[s.from ?: ""] ?: ""
        val pat = s.patterns.firstOrNull() ?: throw RuntimeException("缺少 pattern")
        val out = Regex(pat).replace(src, s.to ?: "")
        ctx[s.save ?: throw RuntimeException("缺少 save")] = out
    }

    private fun execDecode(s: Step, ctx: MutableMap<String, String>) {
        val src = ctx[s.from ?: ""] ?: ""
        val out = when ((s.codec ?: "json_escape").lowercase()) {
            "url" -> runCatching { URLDecoder.decode(src, "UTF-8") }.getOrDefault(src)
            "unicode", "json_escape" -> unescape(src)
            "base64" -> runCatching {
                String(Base64.decode(src, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault(src)
            else -> src
        }
        ctx[s.save ?: throw RuntimeException("缺少 save")] = out
    }

    private fun execSet(s: Step, ctx: MutableMap<String, String>) {
        val name = s.save ?: throw RuntimeException("缺少 save")
        if (s.whenEmpty && !ctx[name].isNullOrEmpty()) return
        val v = render(s.value ?: "", ctx)
        ctx[name] = v
        log("  → 设 $name = ${v.take(60)}")
    }

    private fun execPick(s: Step, ctx: MutableMap<String, String>) {
        val src = ctx[s.from ?: ""] ?: ""
        val arr = JSONArray(src)
        var best: JSONObject? = null
        var bestValue = if (s.max) Long.MIN_VALUE else Long.MAX_VALUE
        var bestUrl: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.isNull(s.take)) continue
            val raw = o.opt(s.key ?: "bitrate")
            val v = when (raw) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull() ?: continue
                else -> continue
            }
            val better = if (s.max) v > bestValue else v < bestValue
            if (better || best == null) {
                best = o
                bestValue = v
                bestUrl = o.optString(s.take, "")
            }
        }
        if (bestUrl.isNullOrBlank()) throw RuntimeException("清晰度列表为空")
        ctx[s.save ?: throw RuntimeException("缺少 save")] = bestUrl
        log("  → 选定码率 $bestValue 的地址")
    }

    private fun render(tpl: String, ctx: Map<String, String>): String {
        return TEMPLATE.replace(tpl) { m -> ctx[m.groupValues[1]] ?: "" }
    }

    private fun jsonPath(text: String, path: String): String? {
        if (text.isBlank() || path.isBlank()) return null
        val trimmed = text.trim()
        val root: Any? = try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> JSONObject(trimmed)
            }
        } catch (e: Exception) {
            return null
        }
        var cur: Any? = root
        var i = 0
        while (i < path.length && cur != null) {
            when (path[i]) {
                '[' -> {
                    val end = path.indexOf(']', i)
                    if (end < 0) return null
                    val idx = path.substring(i + 1, end).toIntOrNull() ?: return null
                    cur = if (cur is JSONArray && idx in 0 until cur.length()) cur.opt(idx) else null
                    i = end + 1
                }
                '.' -> i++
                else -> {
                    var end = i
                    while (end < path.length && path[end] != '.' && path[end] != '[') end++
                    val key = path.substring(i, end)
                    cur = if (cur is JSONObject && cur.has(key)) cur.opt(key) else null
                    i = end
                }
            }
        }
        if (cur == null || cur == JSONObject.NULL) return null
        return cur.toString()
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when {
                    n == 'u' && i + 6 <= s.length -> {
                        val hex = s.substring(i + 2, i + 6)
                        val cp = hex.toIntOrNull(16)
                        if (cp != null) {
                            out.append(cp.toChar())
                            i += 6
                            continue
                        }
                        out.append(c)
                        i++
                    }
                    n == '/' -> { out.append('/'); i += 2 }
                    n == 'n' -> { out.append('\n'); i += 2 }
                    n == 'r' -> { out.append('\r'); i += 2 }
                    n == 't' -> { out.append('\t'); i += 2 }
                    n == '"' -> { out.append('"'); i += 2 }
                    else -> { out.append(n); i += 2 }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    companion object {
        private val TEMPLATE = Regex("\\{([A-Za-z0-9_]+)\\}")
        private const val DEFAULT_URL_PATTERN =
            "https?://[A-Za-z0-9._~:/?#\\[\\]@!\$&'()*+,;=%-]+"
    }
}
