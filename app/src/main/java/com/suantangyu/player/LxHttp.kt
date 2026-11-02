package com.suantangyu.player

import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 3.3.0 原生解析用的 HTTP 小助手，语义对齐网页 reqFetch：
 * 返回解析后的 JSON 对象并附 _http 状态码；正文不是 JSON 对象时
 * 返回 {_text, _http}；网络硬失败返回 null（调用方按失败处理）。
 * 原生侧没有 CORS 限制，直连即可，不需要网页那套同源代理兜底。
 */
object LxHttp {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    class Raw(val status: Int, val bodyText: String, val headers: Map<String, String>)

    /** 原始请求（LX 脚本的 lx.request 用）：不做 JSON 预解析，正文原样返回 */
    fun raw(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutSec: Int = 15
    ): Raw {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = true
            conn.connectTimeout = (timeoutSec.coerceIn(3, 30)) * 1000
            conn.readTimeout = (timeoutSec.coerceIn(3, 30)) * 1000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json,text/plain,*/*")
            for ((k, v) in headers) {
                if (k.equals("User-Agent", ignoreCase = true)) continue
                conn.setRequestProperty(k, v)
            }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = try {
                conn.inputStream
            } catch (e: Exception) {
                conn.errorStream
            }
            var text = ""
            if (stream != null) {
                text = InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    val sb = StringBuilder()
                    val buf = CharArray(8192)
                    var total = 0
                    while (true) {
                        val n = reader.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > 4_000_000) break
                        sb.append(buf, 0, n)
                    }
                    sb.toString()
                }
            }
            val hdrs = HashMap<String, String>()
            for ((k, v) in conn.headerFields) {
                if (k != null && v != null && v.isNotEmpty()) hdrs[k] = v[0]
            }
            return Raw(status, text, hdrs)
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** reqFetch 语义：JSON 对象则合并 _http/_text；否则 {_text,_http}；硬失败 null */
    fun fetchJson(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutSec: Int = 8
    ): JSONObject? {
        val r = try {
            raw(url, method, headers, body, timeoutSec)
        } catch (e: Exception) {
            return null
        }
        val parsed = try {
            JSONObject(r.bodyText)
        } catch (e: Exception) {
            null
        }
        return if (parsed != null) {
            parsed.put("_http", r.status)
            parsed.put("_text", r.bodyText)
            parsed
        } else {
            JSONObject()
                .put("_http", r.status)
                .put("_text", r.bodyText)
        }
    }
}
