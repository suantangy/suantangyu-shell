package com.suantangyu.player

import org.json.JSONObject
import java.net.URLEncoder

/**
 * 3.3.0 原生解析大脑：把网页播放器的 resolvePlayWithQ → resolvePlayAtQuality
 * → resolveBySource 整条链逐分支移植到 Kotlin，在前台服务里独立运行。
 * 覆盖的源类型与网页完全一致：淡月疏星（直链拼接）、雾隐青山（POST 接口）、
 * LX 脚本源（走 LxEngine 的 QuickJS）、玉宁熙（四平台接口）、松间听雨（直链
 * 拼接）、通用 API 源（/url/{plat}/{id}/{q} 等常见形态）。
 * 与网页的已知差异：不做「同平台全败后搜 QQ 同名曲」回退（那需要搜索链，
 * 留待后续）；网页请求里的 CORS 代理腿在原生侧无意义，已略去。
 */
object LxResolver {

    class SrcCfg(
        val idx: Int,
        val id: String,
        val name: String,
        val type: String,
        val url: String,
        val script: String,
        val platforms: List<String>,
        val extra: String
    ) {
        fun key(): String = "cfg$idx"
        fun isScript(): Boolean = script.length >= 30
    }

    private class R(val url: String, val lx: Boolean)

    fun parseSources(configJson: String): List<SrcCfg> {
        val out = ArrayList<SrcCfg>()
        try {
            val arr = JSONObject(configJson).optJSONArray("sources") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val plats = ArrayList<String>()
                val pa = o.optJSONArray("platforms")
                if (pa != null) {
                    for (j in 0 until pa.length()) {
                        val p = pa.optString(j)
                        if (p.isNotEmpty()) plats.add(p)
                    }
                }
                out.add(
                    SrcCfg(
                        i,
                        o.optString("id"),
                        o.optString("name"),
                        o.optString("type"),
                        o.optString("url"),
                        o.optString("script"),
                        plats,
                        (o.optString("jsUrl") + " " + o.optString("fromFile")).trim()
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    // ---------- 与网页逐字对齐的基础函数 ----------

    private val QUALITY_ORDER = listOf("master", "hires", "flac24bit", "flac", "320k", "192k", "128k", "64k")

    private fun qualityRank(q: String): Int {
        val i = QUALITY_ORDER.indexOf(q)
        return if (i < 0) 999 else i
    }

    private fun qualityFallbacks(fromQ: String): List<String> {
        var start = QUALITY_ORDER.indexOf(fromQ)
        if (start < 0) start = QUALITY_ORDER.indexOf("flac")
        if (start < 0) start = 0
        return QUALITY_ORDER.subList(start, minOf(start + 3, QUALITY_ORDER.size))
    }

    private fun qualityMeets(actual: String?, wanted: String): Boolean {
        if (wanted.isEmpty()) return true
        if (actual.isNullOrEmpty()) return false
        return qualityRank(actual) <= qualityRank(wanted)
    }

    fun normPlat(p: String): String {
        return when (p.lowercase()) {
            "wy", "netease" -> "netease"
            "kw", "kuwo" -> "kuwo"
            "kg", "kugou" -> "kugou"
            "mg", "migu" -> "migu"
            "tx" -> "qq"
            else -> p.lowercase().ifEmpty { "qq" }
        }
    }

    private fun platToLxCode(p: String): String {
        return when (normPlat(p)) {
            "netease" -> "wy"
            "kugou" -> "kg"
            "kuwo" -> "kw"
            "migu" -> "mg"
            else -> "tx"
        }
    }

    private fun toLxQuality(q: String): String {
        return when (q) {
            "64k" -> "128k"
            "192k" -> "320k"
            "hires" -> "flac24bit"
            else -> q
        }
    }

    fun cleanPlayMid(song: JSONObject, midIn: String? = null): String {
        var mid = (midIn ?: song.optString("songmid")
            .ifEmpty { song.optString("mid") }
            .ifEmpty { song.optString("id") }).trim()
        mid = mid.replace(Regex("^(kw_|kg_|ne_|mg_|qq_|tx_|wy_)", RegexOption.IGNORE_CASE), "")
        val rid = song.optString("rid")
        if (rid.isNotEmpty()) {
            mid = rid.replace(Regex("^MUSIC_", RegexOption.IGNORE_CASE), "")
        }
        val hash = song.optString("hash")
        if (hash.isNotEmpty()) mid = hash.lowercase()
        val neId = song.optString("neteaseId")
        if (neId.isNotEmpty()) mid = neId
        return mid
    }

    fun keyOfJson(song: JSONObject): String {
        val a = song.optString("songmid").ifEmpty { song.optString("id") }
        return a + "|" + song.optString("name")
    }

    private fun tagPlayUrl(url: String?, q: String?): String? {
        if (url.isNullOrEmpty() || q.isNullOrEmpty()) return url
        val s = url.trim()
        if (!s.startsWith("http:", true) && !s.startsWith("https:", true)) return s
        if (Regex("[?&#](?:aq_q|lx_q)=", RegexOption.IGNORE_CASE).containsMatchIn(s)) return s
        return if (s.contains("#")) s + "&aq_q=" + enc(q) else s + "#aq_q=" + enc(q)
    }

    private fun apiLevelToPlayQ(level: String?): String? {
        val lv = (level ?: "").lowercase()
        if (lv.isEmpty()) return null
        if (Regex("jymaster|master|clear|臻品|dolby|atmos").containsMatchIn(lv) &&
            !Regex("exhigh|standard").containsMatchIn(lv)
        ) {
            if (lv == "atmos") return "hires"
            return "master"
        }
        if (Regex("hires|2599|flac24|24bit|4000k").containsMatchIn(lv)) return "hires"
        if (Regex("lossless|flac|sq|2000k").containsMatchIn(lv)) return "flac"
        if (Regex("exhigh|320").containsMatchIn(lv)) return "320k"
        if (Regex("higher|192").containsMatchIn(lv)) return "192k"
        if (Regex("standard|128").containsMatchIn(lv)) return "128k"
        if (lv.contains("64")) return "64k"
        return null
    }

    private fun detectQualityFromUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val u = url.lowercase()
        val mTag = Regex("[?&#](?:aq_q|lx_q)=([a-z0-9_]+)").find(u)
        if (mTag != null) {
            when (mTag.groupValues[1]) {
                "master", "jymaster" -> return "master"
                "hires", "flac24bit" -> return "hires"
                "flac", "lossless" -> return "flac"
                "320k", "exhigh" -> return "320k"
                "192k" -> return "192k"
                "128k", "standard" -> return "128k"
                "64k" -> return "64k"
            }
        }
        val mLevel = Regex("[?&]level=([a-z0-9_]+)").find(u)
        if (mLevel != null) {
            when (mLevel.groupValues[1]) {
                "lossless", "flac" -> return "flac"
                "exhigh", "320", "320k" -> return "320k"
                "standard", "128", "128k" -> return "128k"
                "higher" -> return "192k"
                "hires", "master", "jymaster" -> return "flac"
            }
        }
        if (Regex("ai0[0-9]|aim0|jymaster|dolby|atmos").containsMatchIn(u)) return "master"
        if (Regex("[/_-]master|master\\.|quality=master").containsMatchIn(u)) return "master"
        if (Regex("hires|flac24|24bit|\\.mflac|rs01|r000").containsMatchIn(u)) return "hires"
        if (Regex("f000|f0m0|\\.flac(\\?|$)|lossless|/sq").containsMatchIn(u)) return "flac"
        if (Regex("m800|320k|exhigh|/hq").containsMatchIn(u)) return "320k"
        if (Regex("m6[0-9]{2}|192k").containsMatchIn(u)) return "192k"
        if (Regex("m500|m400|128k|standard").containsMatchIn(u)) return "128k"
        if (Regex("m200|64k").containsMatchIn(u)) return "64k"
        if (Regex("\\.flac(\\?|$)").containsMatchIn(u)) return "flac"
        if (Regex("\\.mp3(\\?|$)").containsMatchIn(u)) return "128k"
        return null
    }

    private fun nxLevel(qIn: String, platIn: String): String {
        val q = qIn.ifEmpty { "flac" }
        val plat = normPlat(platIn)
        val base = when (q) {
            "64k" -> "standard"
            "128k" -> "standard"
            "192k" -> "exhigh"
            "320k" -> "exhigh"
            "flac" -> "lossless"
            "flac24bit" -> "hires"
            "hires" -> "hires"
            "master" -> "master"
            else -> "lossless"
        }
        return when (plat) {
            "migu" -> if (base == "master") "hires" else base
            "kugou" -> if (base == "hires") "master" else base
            else -> base
        }
    }

    private fun ynxType(q: String): String {
        return when (q) {
            "64k", "128k" -> "低品质"
            "192k", "320k" -> "中品质"
            "flac" -> "SQ无损"
            "flac24bit", "hires", "master" -> "臻品母带"
            else -> "SQ无损"
        }
    }

    private fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    private fun httpsUp(s: String): String =
        s.trim().replace(Regex("^http://", RegexOption.IGNORE_CASE), "https://")

    private fun codeIs0(res: JSONObject): Boolean = res.optString("code") == "0"
    private fun codeIs(res: JSONObject, v: Int): Boolean = res.optString("code") == v.toString()

    // ---------- 主链（对齐 resolvePlayWithQ / resolvePlayAtQuality） ----------

    fun resolve(
        song: JSONObject,
        sources: List<SrcCfg>,
        wantQ: String,
        autoDown: Boolean,
        engine: LxEngine?,
        budgetMs: Long = 90000L,
        log: (String) -> Unit
    ): String? {
        // 3.12.0：解析分段记录（脚本日志搬进脑子）——一进一出包一段，
        // 段头/段尾写进独立脚本日志，解析本体一字不改
        ScriptSeg.begin(song)
        var out: String? = null
        try {
            out = resolveBody(song, sources, wantQ, autoDown, engine, budgetMs, log)
        } finally {
            ScriptSeg.end(out)
        }
        return out
    }

    private fun resolveBody(
        song: JSONObject,
        sources: List<SrcCfg>,
        wantQ: String,
        autoDown: Boolean,
        engine: LxEngine?,
        budgetMs: Long = 90000L,
        log: (String) -> Unit
    ): String? {
        // 3.3.1：单曲解析总时限（默认 90 秒，脚本源慢调用由引擎 30 秒/次封顶，
        // 这里再封整曲上限，防一首坏歌把单线程解析队列拖死、后面全断）；
        // 3.5.4：换源路径传小预算（5 秒总限的剩余量），到点就收，不再逐档磨
        val deadline = System.currentTimeMillis() + budgetMs
        val tiers = if (autoDown) qualityFallbacks(wantQ) else listOf(wantQ)
        for (q in tiers) {
            if (System.currentTimeMillis() > deadline) {
                log("引擎：单曲解析超时跳过 " + song.optString("name"))
                ScriptSeg.note("引擎：单曲解析超时跳过 " + song.optString("name"))
                return null
            }
            val url = resolveAtQuality(song, q, sources, engine, log, deadline)
            if (url != null) return url
        }
        return null
    }

    private fun resolveAtQuality(
        song: JSONObject,
        q: String,
        sources: List<SrcCfg>,
        engine: LxEngine?,
        log: (String) -> Unit,
        deadline: Long = Long.MAX_VALUE
    ): String? {
        val mid = cleanPlayMid(song)
        if (mid.isEmpty()) return null
        val songPlat = normPlat(song.optString("source").ifEmpty { "qq" })
        val chain = sources.filter { s ->
            s.platforms.isEmpty() || s.platforms.contains("all") || s.platforms.contains(songPlat)
        }
        for (src in chain) {
            if (System.currentTimeMillis() > deadline) return null
            val r = try {
                resolveBySource(src, song, q, mid, engine, log)
            } catch (e: Exception) {
                null
            } ?: continue
            if (r.lx) return r.url
            val det = detectQualityFromUrl(r.url)
            if (det == null || qualityMeets(det, q)) return r.url
            // 与网页一致：首个出址的源音质不达标时本档判败（网页转 QQ 回退，引擎直接进下一档）
            return null
        }
        return null
    }

    // ---------- resolveBySource 逐分支移植 ----------

    private fun resolveBySource(
        src: SrcCfg,
        song: JSONObject,
        forceQ: String,
        midIn: String,
        engine: LxEngine?,
        log: (String) -> Unit
    ): R? {
        val q = forceQ.ifEmpty { "flac" }
        val mid = cleanPlayMid(song, midIn)
        if (mid.isEmpty()) return null
        val songPlat = normPlat(song.optString("source").ifEmpty { "qq" })
        val srcPlats = src.platforms
        if (srcPlats.isNotEmpty() && !srcPlats.contains("all") && !srcPlats.contains(songPlat)) {
            if (srcPlats.size == 1) return null
        }
        val hay = src.name + src.url + src.extra
        val isYuxi = src.id == "yuxi" ||
            Regex("屿溪|雾隐青山|yuxi|新裤子|终章|musicserver\\.haitangw\\.cc", RegexOption.IGNORE_CASE)
                .containsMatchIn(hay)
        val isChangqing = !isYuxi && (src.id == "changqing" ||
            Regex("长青|淡月疏星|changqing|元力菌|yinyue\\.haitangw\\.net", RegexOption.IGNORE_CASE)
                .containsMatchIn(hay))

        // —— 淡月疏星：直链拼接（host 写死，与网页一致） ——
        if (isChangqing) {
            val cqPlat = when (songPlat) {
                "netease" -> "wy"; "kugou" -> "kg"; "kuwo" -> "kw"; "migu" -> "mg"; else -> "qq"
            }
            val cqMapAll = mapOf(
                "qq" to mapOf("64k" to "standard", "128k" to "standard", "192k" to "exhigh", "320k" to "exhigh", "flac" to "lossless", "flac24bit" to "jymaster", "hires" to "jymaster", "master" to "jymaster"),
                "wy" to mapOf("64k" to "standard", "128k" to "standard", "192k" to "exhigh", "320k" to "exhigh", "flac" to "lossless", "flac24bit" to "hires", "hires" to "hires", "master" to "jymaster"),
                "kg" to mapOf("64k" to "standard", "128k" to "standard", "192k" to "exhigh", "320k" to "exhigh", "flac" to "lossless", "flac24bit" to "master", "hires" to "master", "master" to "master"),
                "kw" to mapOf("64k" to "standard", "128k" to "standard", "192k" to "exhigh", "320k" to "exhigh", "flac" to "lossless", "flac24bit" to "master", "hires" to "master", "master" to "master"),
                "mg" to mapOf("64k" to "standard", "128k" to "standard", "192k" to "exhigh", "320k" to "exhigh", "flac" to "lossless", "flac24bit" to "hires", "hires" to "hires", "master" to "hires")
            )
            val cqLevel = (cqMapAll[cqPlat] ?: cqMapAll["qq"]!!)[q] ?: "lossless"
            val cqPath = when (cqPlat) {
                "wy" -> "/wy/wy.php"; "kg" -> "/kg/kg_song_kw.php"; "kw" -> "/kw/kw.php"; "mg" -> "/mg/migu.php"; else -> "/qq/qq_kw.php"
            }
            var cqId = mid
            if (cqPlat == "kg") {
                cqId = song.optString("hash").ifEmpty { song.optString("FileHash") }
                    .ifEmpty { song.optString("songmid") }.ifEmpty { mid }.lowercase()
            }
            val cqUrl = "https://yinyue.haitangw.net" + cqPath +
                "?type=mp3&id=" + enc(cqId) + "&level=" + enc(cqLevel)
            val cqQ = apiLevelToPlayQ(cqLevel) ?: (if (q == "master") "master" else q)
            return R(tagPlayUrl(cqUrl, cqQ) ?: cqUrl, false)
        }

        // —— 雾隐青山：POST /v1/music/resolve-url，level 链最多试 4 档 ——
        if (isYuxi) {
            val yxPlat = when (songPlat) {
                "netease" -> "wy"; "kugou" -> "kg"; "kuwo" -> "kw"; "migu" -> "mg"; else -> "tx"
            }
            val yxMapAll = mapOf(
                "tx" to mapOf("64k" to "standard", "128k" to "standard", "192k" to "exhigh", "320k" to "exhigh", "flac" to "lossless", "flac24bit" to "2599", "hires" to "2599", "master" to "jymaster"),
                "wy" to mapOf("64k" to "standard", "128k" to "standard", "192k" to "exhigh", "320k" to "exhigh", "flac" to "lossless", "flac24bit" to "hires", "hires" to "hires", "master" to "jymaster"),
                "kw" to mapOf("64k" to "standard", "128k" to "standard", "192k" to "exhigh", "320k" to "exhigh", "flac" to "lossless", "flac24bit" to "atmos", "hires" to "atmos", "master" to "master"),
                "kg" to mapOf("64k" to "standard", "128k" to "standard", "192k" to "exhigh", "320k" to "exhigh", "flac" to "lossless", "flac24bit" to "hires", "hires" to "hires", "master" to "clear"),
                "mg" to mapOf("64k" to "128k", "128k" to "128k", "192k" to "320k", "320k" to "320k", "flac" to "flac", "flac24bit" to "flac24bit", "hires" to "hires", "master" to "hires")
            )
            val yxLevel = (yxMapAll[yxPlat] ?: yxMapAll["tx"]!!)[q] ?: "exhigh"
            var yxId = mid
            if (yxPlat == "kg") {
                yxId = song.optString("hash").ifEmpty { song.optString("FileHash") }
                    .ifEmpty { song.optString("songmid") }.ifEmpty { mid }.lowercase()
            }
            var yxBase = src.url.trimEnd('/')
            if (!yxBase.contains("musicserver.haitangw.cc", ignoreCase = true)) {
                yxBase = "https://musicserver.haitangw.cc"
            }
            val yxUrl = yxBase + "/v1/music/resolve-url"
            val yxFall = mapOf(
                "jymaster" to listOf("2599", "lossless", "exhigh", "standard"),
                "2599" to listOf("lossless", "exhigh", "standard"),
                "hires" to listOf("lossless", "exhigh", "standard"),
                "master" to listOf("atmos", "lossless", "exhigh", "standard"),
                "atmos" to listOf("lossless", "exhigh", "standard"),
                "clear" to listOf("hires", "lossless", "exhigh", "standard"),
                "lossless" to listOf("exhigh", "standard"),
                "flac24bit" to listOf("flac", "320k", "128k"),
                "flac" to listOf("320k", "128k"),
                "exhigh" to listOf("standard"),
                "320k" to listOf("128k")
            )
            val yxTry = ArrayList<String>()
            yxTry.add(yxLevel)
            for (lv in (yxFall[yxLevel] ?: listOf("exhigh", "standard"))) {
                if (!yxTry.contains(lv)) yxTry.add(lv)
            }
            var tried = 0
            for (lv in yxTry) {
                if (tried++ >= 4) break
                val body = JSONObject()
                    .put("source", yxPlat)
                    .put("rid", yxId)
                    .put("level", lv)
                    .toString()
                val res = LxHttp.fetchJson(
                    yxUrl, "POST",
                    mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                    body, 8
                ) ?: continue
                var music: String? = null
                if (codeIs0(res)) {
                    val d = res.optJSONObject("data")
                    if (d != null && d.optString("url").isNotEmpty()) music = d.optString("url")
                    else if (res.optString("url").isNotEmpty()) music = res.optString("url")
                }
                if (music == null) {
                    val dAny = res.opt("data")
                    if (dAny is String && dAny.startsWith("http", true)) music = dAny
                }
                if (music != null && music.startsWith("http", true)) {
                    val out = httpsUp(music)
                    val yxQ = apiLevelToPlayQ(lv) ?: q
                    return R(tagPlayUrl(out, yxQ) ?: out, false)
                }
            }
            return null
        }

        // —— LX 脚本源：QuickJS 运行时 ——
        if (src.isScript() && engine != null) {
            val u = runLxScript(src, song, q, engine, log)
            if (u != null) return R(httpsUp(u), true)
        }

        // —— 玉宁熙（星河入梦）：四平台接口 ——
        if (src.id == "ynx" ||
            Regex("玉宁熙|星河入梦|yuafeng", RegexOption.IGNORE_CASE).containsMatchIn(src.name + src.url)
        ) {
            return ynxResolve(src, song, q, mid)
        }

        // —— 松间听雨（念心）：直链拼接 ——
        if (src.id == "nianxin" ||
            Regex("念心|松间听雨|nianxin", RegexOption.IGNORE_CASE).containsMatchIn(src.name + src.url)
        ) {
            if (srcPlats.isNotEmpty() && !srcPlats.contains(songPlat) && !srcPlats.contains("all")) {
                if (songPlat != "qq") return null
            }
            val nxFile = when (songPlat) {
                "netease" -> "wy.php"; "kuwo" -> "kw.php"; "kugou" -> "kg.php"; "migu" -> "mg.php"; else -> "tx.php"
            }
            var level = nxLevel(q, songPlat)
            if (level.isEmpty() || level == "undefined") level = "lossless"
            val baseNx = src.url.ifEmpty { "https://mcp.nianxinxz.com" }.trimEnd('/')
            val idCandidates = ArrayList<String>()
            when (songPlat) {
                "kugou" -> {
                    val h = song.optString("hash").ifEmpty { song.optString("FileHash") }
                        .ifEmpty { song.optString("songmid") }.ifEmpty { mid }.lowercase()
                    if (h.isNotEmpty()) idCandidates.add(h)
                }
                "migu" -> {
                    for (x in listOf(
                        song.optString("miguSongId"), song.optString("copyrightId"),
                        mid, song.optString("contentId")
                    )) {
                        val v = x.trim()
                        if (v.isNotEmpty() && !idCandidates.contains(v)) idCandidates.add(v)
                    }
                }
                else -> idCandidates.add(mid)
            }
            if (idCandidates.isEmpty()) return null
            val nxPlayUrl = baseNx.replace(Regex("^http:", RegexOption.IGNORE_CASE), "https:") +
                "/share/ceshi/" + nxFile +
                "?id=" + enc(idCandidates[0]) +
                "&level=" + enc(level) +
                "&type=mp3"
            val nxQ = apiLevelToPlayQ(level) ?: when (level) {
                "master" -> "master"; "hires" -> "hires"; "lossless" -> "flac"; else -> null
            }
            return R(nxQ?.let { tagPlayUrl(nxPlayUrl, it) } ?: nxPlayUrl, false)
        }

        // —— 通用 API 源：常见路径形态挨个试（最多 4 个） ——
        if (src.url.isNotEmpty() && (src.type == "api" || src.type == "js" || src.type.isEmpty())) {
            return genericApiResolve(src, song, q, mid, songPlat, srcPlats)
        }
        return null
    }

    private const val YNX_KEY = "ak_7cf9da787d54a3a12484fc8c92b2796eb5348c3e76cff8ca"

    private fun ynxResolve(src: SrcCfg, song: JSONObject, q: String, mid: String): R? {
        val songPlat = normPlat(song.optString("source").ifEmpty { "qq" })
        when (songPlat) {
            "qq" -> {
                var baseY = src.url.ifEmpty { "https://v.yuafeng.cn" }.trimEnd('/')
                if (!baseY.contains("yuafeng.cn", ignoreCase = true)) baseY = "https://v.yuafeng.cn"
                val typeNames = ArrayList<String>()
                typeNames.add(ynxType(q))
                when (q) {
                    "128k", "64k" -> typeNames.add("低品质")
                    "320k", "192k" -> typeNames.add("中品质")
                    "flac" -> typeNames.add("SQ无损")
                    "hires", "flac24bit", "master" -> typeNames.add("臻品母带")
                }
                val seenT = HashSet<String>()
                for (tn in typeNames) {
                    if (tn.isEmpty() || !seenT.add(tn)) continue
                    val yurl = baseY + "/api/qqmusic?type=" + enc(tn) +
                        "&mid=" + enc(mid) + "&apikey=" + enc(YNX_KEY)
                    val res = LxHttp.fetchJson(
                        yurl, "GET", mapOf("Authorization" to "Bearer $YNX_KEY"), null, 6
                    ) ?: continue
                    var music: String? = null
                    if (codeIs0(res)) {
                        val d = res.optJSONObject("data")
                        if (d != null) {
                            if (d.optString("music").isNotEmpty()) music = d.optString("music")
                            else if (d.optString("url").isNotEmpty()) music = d.optString("url")
                        }
                        if (music == null && res.optString("music").isNotEmpty()) music = res.optString("music")
                        if (music == null && res.optString("url").isNotEmpty()) music = res.optString("url")
                    }
                    if (music == null) {
                        val t = res.optString("_text").trim()
                        if (t.startsWith("http", true)) music = t
                    }
                    if (music != null) {
                        val mu = httpsUp(music)
                        if (tn == "臻品母带" || q == "master" || q == "hires") {
                            var tq = if (q == "master" || tn == "臻品母带") {
                                if (q == "hires") "hires" else "master"
                            } else q
                            if (q == "master") tq = "master"
                            else if (q == "hires" || q == "flac24bit") tq = "hires"
                            return R(tagPlayUrl(mu, tq) ?: mu, false)
                        }
                        return R(mu, false)
                    }
                }
                return null
            }
            "netease" -> {
                fun pickWyPack(res: JSONObject?): Pair<String, String?>? {
                    if (res == null) return null
                    var url: String? = null
                    var qRet: String? = null
                    if (codeIs(res, 200) && res.optString("url").startsWith("http", true)) {
                        url = httpsUp(res.optString("url"))
                        qRet = res.optString("quality").ifEmpty { null }
                    } else {
                        val d = res.optJSONObject("data")
                        if (d != null && d.optString("url").startsWith("http", true)) {
                            url = httpsUp(d.optString("url"))
                            qRet = d.optString("quality").ifEmpty { res.optString("quality") }.ifEmpty { null }
                        } else if (res.optString("_text").isNotEmpty()) {
                            try {
                                val j = JSONObject(res.optString("_text").replace(Regex("^[^\\{]*"), ""))
                                if (j.optString("url").startsWith("http", true)) {
                                    url = httpsUp(j.optString("url"))
                                    qRet = j.optString("quality").ifEmpty { null }
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (url == null) return null
                    var qn = (qRet ?: "").lowercase()
                    qn = when (qn) {
                        "jymaster", "master" -> "master"
                        "hires", "flac24bit" -> "hires"
                        "lossless", "sq", "flac" -> "flac"
                        "exhigh", "320", "320k" -> "320k"
                        else -> if (qn.isEmpty()) "" else qn
                    }
                    return Pair(url, qn.ifEmpty { null })
                }
                fun tryWyQuality(qq: String): Pair<String, String?>? {
                    val body = JSONObject()
                        .put("source", "wy")
                        .put("musicId", mid)
                        .put("quality", qq)
                        .toString()
                    val res = LxHttp.fetchJson(
                        "https://c.wwwweb.top/music/url", "POST",
                        mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                        body, 5
                    )
                    return pickWyPack(res)
                }
                val wyTry = when (q) {
                    "master" -> listOf("master", "hires", "flac")
                    "hires", "flac24bit" -> listOf("hires", "flac")
                    "flac" -> listOf("flac", "320k")
                    else -> listOf(q)
                }
                val seenW = HashSet<String>()
                for (wq in wyTry) {
                    if (wq.isEmpty() || !seenW.add(wq)) continue
                    val pack = tryWyQuality(wq) ?: continue
                    val tagged = pack.second ?: wq
                    val tagQ = if (q == "master" && (tagged == "master" || wq == "master")) "master" else tagged
                    return R(tagPlayUrl(pack.first, tagQ) ?: pack.first, false)
                }
                return null
            }
            "kuwo" -> {
                val kwBr = when (q) {
                    "64k", "128k" -> "128kmp3"
                    "192k", "320k" -> "320kmp3"
                    "flac" -> "2000kflac"
                    "flac24bit", "hires", "master" -> "4000kflac"
                    else -> "2000kflac"
                }
                val uid = (Math.random() * 0xffffff).toInt().toString()
                val kwUrl = "https://nmobi.kuwo.cn/mobi.s?f=web&source=kwplayercar_ar_6.0.0.9_B_jiakong_vh.apk" +
                    "&type=convert_url_with_sign&rid=" + enc(mid) +
                    "&br=" + enc(kwBr) + "&user=" + uid + "&loginUid=" + uid
                val res = LxHttp.fetchJson(kwUrl, "GET", emptyMap(), null, 5)
                var kwPlay = ""
                if (res != null) {
                    val d = res.optJSONObject("data")
                    kwPlay = if (d != null && d.optString("url").isNotEmpty()) d.optString("url")
                    else res.optString("url")
                }
                if (kwPlay.isNotEmpty() && kwPlay != "None" && kwPlay.startsWith("http", true)) {
                    kwPlay = httpsUp(kwPlay.split("?")[0])
                    val kwQ = when {
                        kwBr.contains("4000k") -> if (q == "master") "master" else "hires"
                        Regex("2000k|flac").containsMatchIn(kwBr) -> "flac"
                        kwBr.contains("320") -> "320k"
                        else -> "128k"
                    }
                    return R(tagPlayUrl(kwPlay, kwQ) ?: kwPlay, false)
                }
                return null
            }
            "kugou" -> {
                val mainHash = song.optString("hash").ifEmpty { song.optString("FileHash") }
                    .ifEmpty { song.optString("songmid") }.ifEmpty { mid }.uppercase()
                val kgMid = song.optString("songmid").ifEmpty { song.optString("id") }.ifEmpty { mid }
                val albumId = song.optString("albumId").ifEmpty { song.optString("album_id") }
                val kgUrl = "https://yy.zddyr.top/lx/api/?source=kg&quality=" + enc(q) +
                    "&mainHash=" + enc(mainHash) +
                    "&songmid=" + enc(kgMid) +
                    "&albumId=" + enc(albumId)
                val res = LxHttp.fetchJson(kgUrl, "GET", emptyMap(), null, 5)
                if (res != null && codeIs(res, 200) && res.optString("url").startsWith("http", true)) {
                    val out = httpsUp(res.optString("url"))
                    val kgQ = when (q) {
                        "master" -> "master"
                        "hires", "flac24bit" -> "hires"
                        else -> q.ifEmpty { "flac" }
                    }
                    return R(tagPlayUrl(out, kgQ) ?: out, false)
                }
                return null
            }
            else -> return null // 咪咕：网页端同样交其它源
        }
    }

    private fun genericApiResolve(
        src: SrcCfg,
        song: JSONObject,
        q: String,
        mid: String,
        songPlat: String,
        srcPlats: List<String>
    ): R? {
        val base = src.url.trimEnd('/')
        val platCode = mapOf("qq" to "tx", "netease" to "wy", "kugou" to "kg", "kuwo" to "kw", "migu" to "mg")
        val codes = ArrayList<String>()
        fun pushCode(p: String) {
            val c = platCode[p] ?: return
            if (!codes.contains(c)) codes.add(c)
        }
        val declared = srcPlats.filter { it != "all" }
        if (declared.isNotEmpty()) {
            if (declared.contains(songPlat) || srcPlats.contains("all")) pushCode(songPlat)
            declared.forEach { pushCode(it) }
        } else {
            pushCode(songPlat)
            for (c in listOf("tx", "wy", "kg", "kw", "mg")) {
                if (!codes.contains(c)) codes.add(c)
            }
        }
        if (codes.isEmpty()) codes.add(platCode[songPlat] ?: "tx")
        val tryUrls = ArrayList<String>()
        for (code in codes) {
            tryUrls.add(base + "/url/" + code + "/" + enc(mid) + "/" + enc(q))
            tryUrls.add(base + "/url/" + code + "/" + enc(mid) + "/" + enc(nxLevel(q, songPlat)))
            tryUrls.add(base + "/" + code + "/url?id=" + enc(mid) + "&quality=" + enc(q))
        }
        if (songPlat == "qq") {
            tryUrls.add(base + "/api/qqmusic?mid=" + enc(mid) + "&type=" + enc(ynxType(q)))
            tryUrls.add(base + "/api/qqmusic?type=" + enc(ynxType(q)) + "&mid=" + enc(mid))
            tryUrls.add(base + "?id=" + enc(mid) + "&level=" + enc(nxLevel(q, songPlat)))
        }
        if (songPlat == "netease") {
            val body = JSONObject()
                .put("source", "wy")
                .put("musicId", mid)
                .put("quality", q)
                .toString()
            val impWy = LxHttp.fetchJson(
                base + "/music/url", "POST",
                mapOf("Content-Type" to "application/json"), body, 5
            )
            if (impWy != null && codeIs(impWy, 200) && impWy.optString("url").startsWith("http", true)) {
                val impU = httpsUp(impWy.optString("url"))
                var impQ = impWy.optString("quality").ifEmpty { q }.lowercase()
                if (impQ == "jymaster") impQ = "master"
                if (impQ == "lossless") impQ = "flac"
                return R(tagPlayUrl(impU, impQ.ifEmpty { q }) ?: impU, false)
            }
        }
        tryUrls.add(base + "/url?id=" + enc(mid) + "&quality=" + enc(q) + "&type=" + (platCode[songPlat] ?: "tx"))
        tryUrls.add(base + "/url?id=" + enc(mid) + "&quality=" + enc(q) + "&source=" + (platCode[songPlat] ?: "tx"))
        var tried = 0
        for (u in tryUrls) {
            if (tried++ >= 4) break
            val d = LxHttp.fetchJson(u, "GET", emptyMap(), null, 4) ?: continue
            val urlField = d.optString("url")
            if (urlField.startsWith("http", true)) return R(httpsUp(urlField), false)
            val dAny = d.opt("data")
            if (dAny is JSONObject) {
                if (dAny.optString("music").startsWith("http", true)) {
                    return R(httpsUp(dAny.optString("music")), false)
                }
                if (dAny.optString("url").startsWith("http", true)) {
                    return R(httpsUp(dAny.optString("url")), false)
                }
            } else if (dAny is String && dAny.startsWith("http", true)) {
                return R(httpsUp(dAny), false)
            }
            val t = d.optString("_text").trim()
            if (t.startsWith("http", true)) return R(httpsUp(t), false)
        }
        return null
    }

    // ---------- LX 脚本调用（对齐 runLxPluginMusicUrl） ----------

    private fun runLxScript(
        src: SrcCfg,
        song: JSONObject,
        forceQ: String,
        engine: LxEngine,
        log: (String) -> Unit
    ): String? {
        val mid = cleanPlayMid(song)
        if (mid.isEmpty()) return null
        val code = platToLxCode(song.optString("source").ifEmpty { "qq" })
        val musicInfo = JSONObject()
        musicInfo.put("songmid", mid)
        musicInfo.put("mid", mid)
        musicInfo.put("id", mid)
        musicInfo.put(
            "hash",
            song.optString("hash").ifEmpty { song.optString("FileHash") }.ifEmpty { mid }.lowercase()
        )
        musicInfo.put("name", song.optString("name"))
        musicInfo.put("songname", song.optString("name"))
        musicInfo.put("singer", song.optString("artist"))
        musicInfo.put("singername", song.optString("artist"))
        musicInfo.put("albumName", song.optString("album"))
        musicInfo.put("albumname", song.optString("album"))
        musicInfo.put(
            "albumId",
            song.optString("albummid").ifEmpty { song.optString("albumId") }
                .ifEmpty { song.optString("album_id") }
        )
        musicInfo.put("albummid", song.optString("albummid").ifEmpty { song.optString("albumId") })
        musicInfo.put("strMediaMid", mid)
        musicInfo.put("interval", song.optString("interval"))
        musicInfo.put("img", song.optString("cover"))
        val q0 = toLxQuality(forceQ)
        val attempts = ArrayList<String>()
        for (t in listOf(q0, "flac", "320k", "128k")) {
            if (!attempts.contains(t)) attempts.add(t)
        }
        for (tq in attempts) {
            val payload = JSONObject()
                .put("action", "musicUrl")
                .put("source", code)
                .put(
                    "info", JSONObject()
                        .put("musicInfo", musicInfo)
                        .put("type", tq)
                )
            val url = engine.callMusicUrlBlocking(src.key(), payload.toString(), 30000L)
            if (url != null && url.trim().startsWith("http", true)) {
                return url.trim()
            }
        }
        return null
    }
}

/** 脚本日志分段（3.12.0）：resolve 一进一出记一段——段头（歌名-歌手）、
 * 段尾（解析成功 → host / 解析失败）；段进行中的脚本行由服务侧在引擎
 * 日志回调里按 depth 镜像（见 KeepAliveService）。sink 由服务在
 * onCreate 挂上（写 lx_script.log 独立文件）；后台日志的记录内容与
 * 文件一概不经此处。并发解析（补仓与换源同刻）时两段会在文件里
 * 交错——与旧网页脚本日志的已知串段上限同级，如实记录不做隔离。 */
object ScriptSeg {
    @Volatile
    var depth = 0

    @Volatile
    var sink: ((String) -> Unit)? = null

    fun begin(song: JSONObject) {
        depth++
        val name = song.optString("name")
        val artist = song.optString("artist")
        sink?.invoke(
            if (artist.isEmpty()) "▶ 解析: " + name
            else "▶ 解析: " + name + " - " + artist
        )
    }

    fun note(msg: String) {
        if (depth > 0) sink?.invoke(msg)
    }

    fun end(url: String?) {
        if (url != null) {
            var host = ""
            try {
                host = java.net.URL(url).host ?: ""
            } catch (_: Exception) {
            }
            sink?.invoke(if (host.isNotEmpty()) "▶ 解析成功 → " + host else "▶ 解析成功")
        } else {
            sink?.invoke("▶ 解析失败")
        }
        if (depth > 0) depth--
    }
}
