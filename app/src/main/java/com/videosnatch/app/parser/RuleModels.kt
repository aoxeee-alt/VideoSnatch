package com.videosnatch.app.parser

import org.json.JSONArray
import org.json.JSONObject

/** 一个解析步骤。所有字段都来自 rules.json，改规则不用改代码。 */
data class Step(
    val type: String = "",
    val url: String? = null,
    val method: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val followRedirects: Boolean = true,
    val save: String? = null,
    val from: String? = null,
    val patterns: List<String> = emptyList(),
    val group: Int = 1,
    val saves: List<String> = emptyList(),
    val to: String? = null,
    val path: String? = null,
    val codec: String? = null,
    val value: String? = null,
    val whenEmpty: Boolean = false,
    val optional: Boolean = false,
    val key: String? = null,
    val take: String = "url",
    val max: Boolean = true
)

data class Output(
    val url: String? = null,
    val title: String? = null,
    val cover: String? = null,
    val downloadHeaders: Map<String, String> = emptyMap()
)

data class Provider(
    val id: String = "",
    val name: String = "",
    val match: List<String> = emptyList(),
    val steps: List<Step> = emptyList(),
    val output: Output? = null
)

data class RuleSet(
    val version: Int = 0,
    val updated: String? = null,
    val note: String? = null,
    val extractUrl: String? = null,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val providers: List<Provider> = emptyList()
) {
    companion object {
        fun parse(text: String): RuleSet {
            val o = JSONObject(text)
            val providers = mutableListOf<Provider>()
            val arr = o.optJSONArray("providers") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val steps = mutableListOf<Step>()
                val sa = p.optJSONArray("steps") ?: JSONArray()
                for (j in 0 until sa.length()) {
                    val s = sa.optJSONObject(j) ?: continue
                    steps.add(
                        Step(
                            type = s.optString("type", ""),
                            url = s.optStringOrNull("url"),
                            method = s.optStringOrNull("method"),
                            headers = toStringMap(s.optJSONObject("headers")),
                            body = s.optStringOrNull("body"),
                            followRedirects = s.optBoolean("followRedirects", true),
                            save = s.optStringOrNull("save"),
                            from = s.optStringOrNull("from"),
                            patterns = toStringList(s.opt("pattern")),
                            group = s.optInt("group", 1),
                            saves = toStringList(s.opt("saves")),
                            to = s.optStringOrNull("to"),
                            path = s.optStringOrNull("path"),
                            codec = s.optStringOrNull("codec"),
                            value = s.optStringOrNull("value"),
                            whenEmpty = s.optBoolean("whenEmpty", false),
                            optional = s.optBoolean("optional", false),
                            key = s.optStringOrNull("key"),
                            take = s.optString("take", "url"),
                            max = s.optBoolean("max", true)
                        )
                    )
                }
                providers.add(
                    Provider(
                        id = p.optString("id", ""),
                        name = p.optString("name", p.optString("id", "")),
                        match = toStringList(p.opt("match")),
                        steps = steps,
                        output = p.optJSONObject("output")?.let {
                            Output(
                                url = it.optStringOrNull("url"),
                                title = it.optStringOrNull("title"),
                                cover = it.optStringOrNull("cover"),
                                downloadHeaders = toStringMap(it.optJSONObject("downloadHeaders"))
                            )
                        }
                    )
                )
            }
            return RuleSet(
                version = o.optInt("version", 0),
                updated = o.optStringOrNull("updated"),
                note = o.optStringOrNull("note"),
                extractUrl = o.optStringOrNull("extractUrl"),
                defaultHeaders = toStringMap(o.optJSONObject("defaultHeaders")),
                providers = providers
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (isNull(key)) return null
            val v = optString(key, "")
            return if (v.isEmpty()) null else v
        }

        private fun toStringList(v: Any?): List<String> = when (v) {
            null -> emptyList()
            is String -> listOf(v)
            is JSONArray -> {
                val out = mutableListOf<String>()
                for (i in 0 until v.length()) {
                    val s = v.optString(i, "")
                    if (s.isNotEmpty()) out.add(s)
                }
                out
            }
            else -> emptyList()
        }

        private fun toStringMap(o: JSONObject?): Map<String, String> {
            if (o == null) return emptyMap()
            val out = mutableMapOf<String, String>()
            for (k in o.keys()) {
                val v = o.optString(k, "")
                if (v.isNotEmpty()) out[k] = v
            }
            return out
        }
    }
}
