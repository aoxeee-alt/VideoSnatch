package com.videosnatch.app.parser

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 规则仓库：内置一份兜底，另外可以从网上（你的 GitHub）拉最新版并缓存。 */
class RuleRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val cacheFile: File
        get() = File(context.filesDir, "rules.json")

    fun builtIn(): RuleSet =
        RuleSet.parse(context.assets.open("rules.json").bufferedReader().use { it.readText() })

    fun cached(): RuleSet? = try {
        if (cacheFile.exists()) RuleSet.parse(cacheFile.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun load(): RuleSet = cached() ?: builtIn()

    @Throws(Exception::class)
    fun fetch(url: String): RuleSet {
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("下载规则失败：HTTP ${resp.code}")
            val text = resp.body?.string() ?: throw RuntimeException("规则内容为空")
            val parsed = RuleSet.parse(text)
            cacheFile.writeText(text)
            return parsed
        }
    }

    fun clearCache() {
        if (cacheFile.exists()) cacheFile.delete()
    }
}
