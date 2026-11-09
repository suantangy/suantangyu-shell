package com.suantangyu.player

import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 3.3.0 QuickJS 原生解析引擎：把 LX Music 音源脚本跑在前台服务自己的
 * JS 线程里（不经过任何 WebView，界面页被系统冻结也照常工作）。
 *
 * 运行时接口面逐项对齐网页播放器自带的 LX 宿主（buildLxHost）：
 * lx.EVENT_NAMES / version 2.4.0 / env mobile / on('request') /
 * send('inited') / request(url, opts, cb) / utils.buffer / utils.crypto。
 * 差异（均为超集，不弱于网页）：网页版 aes/rsa 是空壳，这里给真实现；
 * 网页请求要绕同源代理防 CORS，原生直连不需要。
 *
 * 线程模型：一个 QuickJSContext 只归属一个脚本，全部 JS 操作只在
 * LxJs 线程发生；HTTP 在线程池跑，完成后把结果投回 JS 线程触发回调
 * （wrapper 的 evaluate/call 会自动抽干 Promise 任务队列）。
 */
class LxEngine(private val log: (String) -> Unit) {

    private val jsThread = HandlerThread("LxJs").apply { start() }
    private val jsHandler = Handler(jsThread.looper)
    private val httpExec = Executors.newCachedThreadPool()
    @Volatile private var loaderReady = false

    private inner class ScriptCtx(val key: String, val name: String) {
        var ctx: QuickJSContext? = null
        var alive = false
        var handlerReady = false
        val timers = HashMap<Int, Runnable>()
    }

    // 仅 JS 线程读写
    private val scripts = LinkedHashMap<String, ScriptCtx>()
    private val pending = HashMap<Int, (String?) -> Unit>()
    private var callSeq = 0

    private fun ensureLoader() {
        if (loaderReady) return
        try {
            QuickJSLoader.init()
        } catch (_: Exception) {
        }
        loaderReady = true
    }

    /** 载入/重载全部脚本源（脚本列表变化时调用；内容未变的脚本跳过重建由调用方按 hash 把关） */
    fun configure(scriptSources: List<LxResolver.SrcCfg>) {
        jsHandler.post {
            ensureLoader()
            // 旧上下文整体销毁重建：LX 宿主语义里脚本是一次性装载的
            for ((_, sc) in scripts) destroyCtx(sc)
            scripts.clear()
            val done = ArrayList<(String?) -> Unit>()
            done.addAll(pending.values)
            pending.clear()
            done.forEach { it(null) }
            var okCount = 0
            for (src in scriptSources) {
                val script = src.script
                if (script.length < 30) continue
                val key = src.key()
                val sc = ScriptCtx(key, src.name)
                scripts[key] = sc
                try {
                    buildCtx(sc, script)
                    if (sc.handlerReady) okCount++
                } catch (e: Exception) {
                    log("引擎：脚本[" + src.name + "]装载失败 " + (e.message ?: ""))
                }
            }
            log("引擎就绪：脚本源 " + okCount + "/" + scriptSources.size + " 可用")
        }
    }

    private fun destroyCtx(sc: ScriptCtx) {
        sc.alive = false
        for ((_, r) in sc.timers) jsHandler.removeCallbacks(r)
        sc.timers.clear()
        try {
            sc.ctx?.destroy()
        } catch (_: Exception) {
        }
        sc.ctx = null
        sc.handlerReady = false
    }

    private fun buildCtx(sc: ScriptCtx, scriptText: String) {
        val ctx = QuickJSContext.create()
        sc.ctx = ctx
        sc.alive = true
        ctx.setConsole(object : QuickJSContext.Console {
            override fun log(info: String?) {
                this@LxEngine.log("[" + sc.name + "] " + (info ?: ""))
            }

            override fun info(info: String?) {
                this@LxEngine.log("[" + sc.name + "] " + (info ?: ""))
            }

            override fun warn(info: String?) {
                this@LxEngine.log("[" + sc.name + "] " + (info ?: ""))
            }

            override fun error(info: String?) {
                this@LxEngine.log("[" + sc.name + "] " + (info ?: ""))
            }
        })
        installNativeHooks(sc, ctx)
        ctx.evaluate(GLUE_JS)
        // currentScriptInfo 必须是脚本自己的头信息（与落雪一致）：有些脚本（如屿溪·沉音）
        // 会校验 homepage 等字段，写死宿主名会让它拒绝初始化
        try {
            ctx.evaluate("globalThis.lx.currentScriptInfo = " + scriptInfoJson(scriptText) + ";")
        } catch (e: Exception) {
        }
        ctx.evaluate(scriptText)
        val t = try {
            ctx.evaluate("typeof globalThis.__lxH")
        } catch (e: Exception) {
            null
        }
        sc.handlerReady = (t == "function")
        if (!sc.handlerReady) {
            log("引擎：脚本[" + sc.name + "]未注册 request 处理函数")
        }
    }

    private fun installNativeHooks(sc: ScriptCtx, ctx: QuickJSContext) {
        val g = ctx.globalObject
        g.setProperty("__natLog", JSCallFunction { args ->
            val m = args.getOrNull(0)?.toString() ?: ""
            log("[" + sc.name + "] " + m)
            null
        })
        g.setProperty("__natInited", JSCallFunction { args ->
            try {
                val data = JSONObject(args.getOrNull(0)?.toString() ?: "{}")
                val sources = data.optJSONObject("sources")
                if (sources != null) {
                    val keys = ArrayList<String>()
                    val it = sources.keys()
                    while (it.hasNext()) keys.add(it.next())
                    log("引擎：脚本[" + sc.name + "]声明平台 " + keys.joinToString(","))
                }
            } catch (_: Exception) {
            }
            null
        })
        g.setProperty("__natDone", JSCallFunction { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: -1
            val url = args.getOrNull(1)?.toString() ?: ""
            val err = args.getOrNull(2)?.toString() ?: ""
            val cb = pending.remove(id)
            if (cb != null) {
                if (err.isNotEmpty()) {
                    log("引擎：脚本[" + sc.name + "]解析报错 " + err.take(120))
                }
                cb(if (url.isEmpty()) null else url)
            }
            null
        })
        g.setProperty("__natHttp", JSCallFunction { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: -1
            val url = args.getOrNull(1)?.toString() ?: ""
            val optsJson = args.getOrNull(2)?.toString() ?: "{}"
            if (id >= 0 && url.isNotEmpty()) doHttp(sc, ctx, id, url, optsJson)
            null
        })
        g.setProperty("__natTimer", JSCallFunction { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: -1
            val ms = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
            val repeat = args.getOrNull(2) as? Boolean ?: false
            if (id >= 0) {
                val delay = ms.coerceIn(0L, 24L * 3600 * 1000)
                val r = object : Runnable {
                    override fun run() {
                        if (!sc.alive) return
                        try {
                            ctx.evaluate("__lxTimerFire($id)")
                        } catch (_: Exception) {
                        }
                        if (repeat && sc.timers.containsKey(id)) {
                            jsHandler.postDelayed(this, delay.coerceAtLeast(10L))
                        }
                    }
                }
                sc.timers[id] = r
                jsHandler.postDelayed(r, delay)
            }
            null
        })
        g.setProperty("__natTimerClear", JSCallFunction { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: -1
            val r = sc.timers.remove(id)
            if (r != null) jsHandler.removeCallbacks(r)
            null
        })
        g.setProperty("__natMd5Str", JSCallFunction { args ->
            md5Hex((args.getOrNull(0)?.toString() ?: "").toByteArray(Charsets.UTF_8))
        })
        g.setProperty("__natMd5B64", JSCallFunction { args ->
            try {
                md5Hex(Base64.decode(args.getOrNull(0)?.toString() ?: "", Base64.DEFAULT))
            } catch (e: Exception) {
                ""
            }
        })
        g.setProperty("__natAes", JSCallFunction { args ->
            aes(
                (args.getOrNull(0) as? Number)?.toInt() == 1,
                args.getOrNull(1)?.toString() ?: "",
                args.getOrNull(2)?.toString() ?: "",
                args.getOrNull(3)?.toString() ?: "",
                args.getOrNull(4)?.toString() ?: ""
            )
        })
        g.setProperty("__natRsa", JSCallFunction { args ->
            rsaEncrypt(args.getOrNull(0)?.toString() ?: "", args.getOrNull(1)?.toString() ?: "")
        })
        g.setProperty("__natRandom", JSCallFunction { args ->
            val n = ((args.getOrNull(0) as? Number)?.toInt() ?: 16).coerceIn(1, 1024)
            val b = ByteArray(n)
            SecureRandom().nextBytes(b)
            Base64.encodeToString(b, Base64.NO_WRAP)
        })
    }

    private fun doHttp(sc: ScriptCtx, ctx: QuickJSContext, id: Int, url: String, optsJson: String) {
        var method = "GET"
        var timeoutSec = 15
        var body: String? = null
        val headers = HashMap<String, String>()
        try {
            val opts = JSONObject(optsJson)
            method = opts.optString("method", "GET").uppercase()
            val timeoutMs = opts.optLong("timeout", 0L)
            if (timeoutMs > 0) timeoutSec = (timeoutMs / 1000).toInt().coerceIn(3, 30)
            val hdrs = opts.optJSONObject("headers")
            if (hdrs != null) {
                val it = hdrs.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    headers[k] = hdrs.optString(k)
                }
            }
            if (opts.has("body") && !opts.isNull("body")) {
                body = opts.optString("body")
            } else if (opts.has("form") && !opts.isNull("form")) {
                val form = opts.optJSONObject("form")
                if (form != null) {
                    val parts = ArrayList<String>()
                    val it = form.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        parts.add(
                            java.net.URLEncoder.encode(k, "UTF-8") + "=" +
                                java.net.URLEncoder.encode(form.optString(k), "UTF-8")
                        )
                    }
                    body = parts.joinToString("&")
                    if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                        headers["Content-Type"] = "application/x-www-form-urlencoded"
                    }
                }
            }
            if (body != null && method == "GET") method = "POST"
            if (body != null && headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                headers["Content-Type"] = "application/json"
            }
        } catch (_: Exception) {
        }
        httpExec.execute {
            val result = JSONObject()
            try {
                val r = LxHttp.raw(url, method, headers, body, timeoutSec)
                result.put("status", r.status)
                result.put("bodyText", r.bodyText)
                val hdrs = JSONObject()
                for ((k, v) in r.headers) hdrs.put(k, v)
                result.put("headers", hdrs)
            } catch (e: Exception) {
                result.put("err", (e.message ?: "network").take(120))
            }
            jsHandler.post {
                if (!sc.alive) return@post
                try {
                    ctx.evaluate("__lxHttpDone(" + id + ", " + JSONObject.quote(result.toString()) + ")")
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * 同步调用某脚本的 musicUrl 处理函数（在调用方线程阻塞等待）。
     * payloadJson 为页面同款事件对象 {action, source, info:{musicInfo, type}}。
     */
    fun callMusicUrlBlocking(scriptKey: String, payloadJson: String, timeoutMs: Long): String? {
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<String>(1)
        jsHandler.post {
            val sc = scripts[scriptKey]
            if (sc == null || !sc.handlerReady || sc.ctx == null) {
                latch.countDown()
                return@post
            }
            val id = ++callSeq
            pending[id] = { url ->
                box[0] = url
                latch.countDown()
            }
            try {
                sc.ctx?.evaluate("__lxCall(" + id + ", " + JSONObject.quote(payloadJson) + ")")
            } catch (e: Exception) {
                pending.remove(id)
                latch.countDown()
                return@post
            }
            jsHandler.postDelayed({
                val cb = pending.remove(id)
                if (cb != null) cb(null)
            }, timeoutMs)
        }
        try {
            latch.await(timeoutMs + 5000, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
        return box[0]
    }

    fun shutdown() {
        jsHandler.post {
            for ((_, sc) in scripts) destroyCtx(sc)
            scripts.clear()
            pending.clear()
        }
        try {
            jsThread.quitSafely()
        } catch (_: Exception) {
        }
        httpExec.shutdownNow()
    }

    companion object {
        /** 从脚本头部注释解析 @name/@version/@description/@author/@homepage，拼成 currentScriptInfo 的 JSON。 */
        private fun scriptInfoJson(scriptText: String): String {
            val head = scriptText.substring(0, minOf(3000, scriptText.length))
            fun grab(key: String): String {
                val m = Regex("@" + key + "\\s+(.+)").find(head) ?: return ""
                return m.groupValues[1].trim()
            }
            val o = JSONObject()
            o.put("name", grab("name"))
            o.put("version", grab("version"))
            o.put("description", grab("description"))
            o.put("author", grab("author"))
            o.put("homepage", grab("homepage"))
            return o.toString()
        }

        private fun md5Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("MD5").digest(data)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xff
                if (v < 16) sb.append('0')
                sb.append(v.toString(16))
            }
            return sb.toString()
        }

        /** mode 形如 aes-128-cbc / aes-256-ecb；失败返回空串（JS 侧回退为原样返回，与网页空壳行为一致） */
        private fun aes(encrypt: Boolean, mode: String, keyB64: String, ivB64: String, dataB64: String): String {
            return try {
                val m = mode.lowercase()
                val trans = when {
                    m.contains("ecb") -> "AES/ECB/PKCS5Padding"
                    m.contains("cfb") -> "AES/CFB/NoPadding"
                    m.contains("ofb") -> "AES/OFB/NoPadding"
                    else -> "AES/CBC/PKCS5Padding"
                }
                val key = Base64.decode(keyB64, Base64.DEFAULT)
                val data = Base64.decode(dataB64, Base64.DEFAULT)
                val cipher = Cipher.getInstance(trans)
                val keySpec = SecretKeySpec(key, "AES")
                if (trans.contains("ECB")) {
                    cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec)
                } else {
                    val iv = Base64.decode(ivB64, Base64.DEFAULT)
                    cipher.init(
                        if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                        keySpec, IvParameterSpec(iv)
                    )
                }
                Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
            } catch (e: Exception) {
                ""
            }
        }

        private fun rsaEncrypt(dataB64: String, pubKeyText: String): String {
            return try {
                val cleaned = pubKeyText
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("-----BEGIN RSA PUBLIC KEY-----", "")
                    .replace("-----END RSA PUBLIC KEY-----", "")
                    .replace(Regex("\\s"), "")
                if (cleaned.isEmpty()) return ""
                val keyBytes = Base64.decode(cleaned, Base64.DEFAULT)
                val key = KeyFactory.getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(keyBytes))
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                Base64.encodeToString(
                    cipher.doFinal(Base64.decode(dataB64, Base64.DEFAULT)),
                    Base64.NO_WRAP
                )
            } catch (e: Exception) {
                ""
            }
        }

        // LX 宿主垫层（JS）：字节编解码、定时器、lx 对象、调用分发。
        // 与网页 buildLxHost 的字段/行为逐项对齐；不使用模板字符串以便嵌进 Kotlin 原始串。
        private const val GLUE_JS = """
(function () {
  var g = globalThis;
  function utf8Encode(str) {
    var out = [];
    str = String(str);
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c < 0x80) { out.push(c); }
      else if (c < 0x800) { out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f)); }
      else if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
        var c2 = str.charCodeAt(i + 1);
        if (c2 >= 0xdc00 && c2 <= 0xdfff) {
          var cp = 0x10000 + ((c - 0xd800) * 0x400) + (c2 - 0xdc00);
          out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
          i++;
        } else { out.push(0xef, 0xbf, 0xbd); }
      } else { out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f)); }
    }
    return new Uint8Array(out);
  }
  function utf8Decode(bytes) {
    var out = '';
    var i = 0;
    while (i < bytes.length) {
      var b = bytes[i];
      var cp;
      if (b < 0x80) { cp = b; i += 1; }
      else if (b >= 0xc2 && b < 0xe0) { cp = ((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f); i += 2; }
      else if (b >= 0xe0 && b < 0xf0) { cp = ((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f); i += 3; }
      else if (b >= 0xf0 && b < 0xf5) { cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f); i += 4; }
      else { cp = 0xfffd; i += 1; }
      if (cp < 0x10000) { out += String.fromCharCode(cp); }
      else { cp -= 0x10000; out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff)); }
    }
    return out;
  }
  var B64T = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  function b64Encode(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i += 3) {
      var b0 = bytes[i];
      var b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
      var b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
      out += B64T.charAt(b0 >> 2);
      out += B64T.charAt(((b0 & 3) << 4) | (b1 >> 4));
      out += i + 1 < bytes.length ? B64T.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=';
      out += i + 2 < bytes.length ? B64T.charAt(b2 & 63) : '=';
    }
    return out;
  }
  function b64Decode(str) {
    var rev = {};
    for (var i = 0; i < B64T.length; i++) rev[B64T.charAt(i)] = i;
    var out = [];
    var acc = 0, nbits = 0;
    str = String(str);
    for (var j = 0; j < str.length; j++) {
      var ch = str.charAt(j);
      if (ch === '=') break;
      var v = rev[ch];
      if (v == null) continue;
      acc = (acc << 6) | v;
      nbits += 6;
      if (nbits >= 8) { nbits -= 8; out.push((acc >> nbits) & 0xff); }
    }
    return new Uint8Array(out);
  }
  function hexEncode(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i++) {
      var h = bytes[i].toString(16);
      out += h.length < 2 ? '0' + h : h;
    }
    return out;
  }
  function hexDecode(str) {
    str = String(str);
    var out = [];
    for (var i = 0; i + 1 < str.length; i += 2) out.push(parseInt(str.substr(i, 2), 16) || 0);
    return new Uint8Array(out);
  }
  function toU8(data) {
    if (data instanceof Uint8Array) return data;
    if (data && typeof data === 'object' && typeof data.length === 'number' && typeof data.byteLength !== 'number') {
      var a = new Uint8Array(data.length);
      for (var i = 0; i < data.length; i++) a[i] = data[i] & 0xff;
      return a;
    }
    if (data && data.buffer instanceof ArrayBuffer && typeof data.byteLength === 'number') {
      return new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength);
    }
    return null;
  }
  function bytesOf(data, enc) {
    if (typeof data === 'string') {
      if (enc === 'base64') return b64Decode(data);
      if (enc === 'hex') return hexDecode(data);
      if (enc === 'latin1' || enc === 'binary') {
        var a = new Uint8Array(data.length);
        for (var i = 0; i < data.length; i++) a[i] = data.charCodeAt(i) & 0xff;
        return a;
      }
      return utf8Encode(data);
    }
    var u = toU8(data);
    if (u) return u;
    return utf8Encode(data == null ? '' : String(data));
  }
  g.__lxTimers = {};
  g.__lxTimerSeq = 0;
  g.setTimeout = function (fn, ms) {
    var id = ++g.__lxTimerSeq;
    g.__lxTimers[id] = { fn: fn, repeat: 0 };
    g.__natTimer(id, ms || 0, false);
    return id;
  };
  g.setInterval = function (fn, ms) {
    var id = ++g.__lxTimerSeq;
    g.__lxTimers[id] = { fn: fn, repeat: ms || 0 };
    g.__natTimer(id, ms || 0, true);
    return id;
  };
  g.clearTimeout = function (id) {
    if (id != null) { delete g.__lxTimers[id]; g.__natTimerClear(id); }
  };
  g.clearInterval = g.clearTimeout;
  g.__lxTimerFire = function (id) {
    var t = g.__lxTimers[id];
    if (!t) return;
    if (!t.repeat) delete g.__lxTimers[id];
    try { t.fn(); } catch (e) { g.__natLog('timer: ' + (e && e.message || e)); }
  };
  g.__lxHttpCbs = {};
  g.__lxHttpSeq = 0;
  g.__lxHttpDone = function (id, json) {
    var cb = g.__lxHttpCbs[id];
    delete g.__lxHttpCbs[id];
    if (typeof cb !== 'function') return;
    var r;
    try { r = JSON.parse(json); } catch (e) { r = { err: 'bad-result' }; }
    if (r.err) {
      try { cb(new Error(String(r.err)), { body: '', bodyText: '', statusCode: 0, headers: {} }); } catch (e2) {}
      return;
    }
    var bodyVal = r.bodyText || '';
    if (bodyVal.length > 1 && (bodyVal.charAt(0) === '{' || bodyVal.charAt(0) === '[')) {
      try { bodyVal = JSON.parse(bodyVal); } catch (e3) {}
    }
    try { cb(null, { body: bodyVal, bodyText: r.bodyText || '', statusCode: r.status || 0, headers: r.headers || {} }); } catch (e4) {}
  };
  g.__lxCall = function (id, json) {
    var ev;
    try { ev = JSON.parse(json); } catch (e) { g.__natDone(id, '', 'bad-json'); return; }
    var h = g.__lxH;
    if (typeof h !== 'function') { g.__natDone(id, '', 'no-handler'); return; }
    var p;
    try { p = h(ev); } catch (e) { g.__natDone(id, '', String(e && e.message || e)); return; }
    Promise.resolve(p).then(function (u) {
      if (u && typeof u === 'object' && u.url) u = u.url;
      g.__natDone(id, u == null ? '' : String(u), '');
    }, function (e) { g.__natDone(id, '', String(e && e.message || e)); });
  };
  if (typeof g.console === 'undefined') {
    var mk = function (lv) {
      return function () {
        var parts = [];
        for (var i = 0; i < arguments.length; i++) {
          var a = arguments[i];
          try { parts.push(typeof a === 'object' ? JSON.stringify(a) : String(a)); } catch (e) { parts.push(String(a)); }
        }
        g.__natLog(lv + ': ' + parts.join(' '));
      };
    };
    g.console = { log: mk('log'), info: mk('info'), warn: mk('warn'), error: mk('error'), debug: mk('debug') };
  }
  g.__lxH = null;
  var lx = {
    EVENT_NAMES: { inited: 'inited', request: 'request', updateAlert: 'updateAlert' },
    version: '2.4.0',
    env: 'mobile',
    quality: { list: ['128k', '320k', 'flac', 'flac24bit', 'hires', 'atmos', 'master'] },
    currentScriptInfo: { name: 'suantangyu-shell', version: '1.0.0', description: '' },
    on: function (ev, cb) { if (ev === 'request' && typeof cb === 'function') g.__lxH = cb; },
    send: function (ev, data) { if (ev === 'inited') { try { g.__natInited(JSON.stringify(data || {})); } catch (e) {} } },
    request: function (url, options, callback) {
      options = options || {};
      var id = ++g.__lxHttpSeq;
      if (typeof callback === 'function') g.__lxHttpCbs[id] = callback;
      var opts = { method: String(options.method || 'GET'), headers: options.headers || {}, timeout: options.timeout || 0 };
      if (options.body != null) opts.body = (typeof options.body === 'string') ? options.body : JSON.stringify(options.body);
      if (options.form && typeof options.form === 'object') opts.form = options.form;
      if (options.formData && typeof options.formData === 'object') opts.form = options.formData;
      try {
        g.__natHttp(id, String(url), JSON.stringify(opts));
      } catch (e) {
        var cb2 = g.__lxHttpCbs[id];
        delete g.__lxHttpCbs[id];
        if (cb2) { try { cb2(new Error('http-bridge'), { body: '', bodyText: '', statusCode: 0, headers: {} }); } catch (e2) {} }
      }
    },
    utils: {
      buffer: {
        from: function (data, encoding) { return bytesOf(data, encoding); },
        bufToString: function (buf, encoding) {
          var u = toU8(buf);
          if (!u) return String(buf == null ? '' : buf);
          if (encoding === 'base64') return b64Encode(u);
          if (encoding === 'hex') return hexEncode(u);
          if (encoding === 'latin1' || encoding === 'binary') {
            var s = '';
            for (var i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
            return s;
          }
          return utf8Decode(u);
        }
      },
      crypto: {
        md5: function (data) {
          if (typeof data === 'string') return g.__natMd5Str(data);
          var u = toU8(data);
          if (u) return g.__natMd5B64(b64Encode(u));
          return g.__natMd5Str(String(data == null ? '' : data));
        },
        aesEncrypt: function (data, mode, key, iv) {
          var r = g.__natAes(1, String(mode || 'aes-128-cbc'), b64Encode(bytesOf(key)), b64Encode(bytesOf(iv)), b64Encode(bytesOf(data)));
          return r ? b64Decode(r) : data;
        },
        aesDecrypt: function (data, mode, key, iv) {
          var r = g.__natAes(0, String(mode || 'aes-128-cbc'), b64Encode(bytesOf(key)), b64Encode(bytesOf(iv)), b64Encode(bytesOf(data)));
          return r ? b64Decode(r) : data;
        },
        rsaEncrypt: function (data, key) {
          var ks = '';
          if (typeof key === 'string') ks = key;
          else if (key != null) ks = utf8Decode(bytesOf(key));
          var r = g.__natRsa(b64Encode(bytesOf(data)), ks);
          return r ? b64Decode(r) : data;
        },
        randomBytes: function (size) { return b64Decode(g.__natRandom(size || 16)); }
      }
    }
  };
  g.lx = lx;
  g.window = g;
})();
"""
    }
}
