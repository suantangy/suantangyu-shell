package com.suantangyu.player

import org.json.JSONObject
import java.net.URLEncoder

/**
 * K歌伴奏查找（3.22.0）：与网页数据层（KARA.findAccompaniment）同构的脑内实现——
 * 熄屏时页面冻结，伴奏查找必须归脑子。瀑布：酷狗搜索（标题带「伴奏」+歌手对
 * 得上+时长最贴，拼成酷狗描述符走现行解析链）→ 5sing bz 直链。挑法与网页侧
 * 一字不差：时长差为主轴（diff*10），形态只破近局（正形 0 / 其他 +2 /
 * 自制扒带 +3 / Live 现场 +4），时长差 >8 秒不收。
 */
object KaraokeFinder {

    sealed class Result {
        data class KgDesc(val desc: JSONObject) : Result()
        data class DirectUrl(val url: String, val name: String, val artist: String) : Result()
        object None : Result()
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun getJson(url: String, referer: String? = null): JSONObject? {
        val headers = if (referer != null) mapOf("Referer" to referer) else emptyMap()
        val r = try {
            LxHttp.raw(url, "GET", headers, null, 10)
        } catch (_: Exception) {
            return null
        }
        if (r.status !in 200..299) return null
        return try {
            JSONObject(r.bodyText)
        } catch (_: Exception) {
            null
        }
    }

    private fun firstSinger(a: String): String =
        a.split(Regex("[/、,&·]")).firstOrNull()?.trim()?.lowercase() ?: ""

    fun find(song: JSONObject): Result {
        val name = song.optString("name").trim()
        if (name.isEmpty()) return Result.None
        val artist = song.optString("artist").trim()
        val interval = song.optInt("interval", 0)
        try {
            val kg = kgAccompaniment(name, artist, interval)
            if (kg != null) return Result.KgDesc(kg)
        } catch (_: Exception) {
        }
        try {
            val fs = fiveSingAccompaniment(name, artist)
            if (fs != null) return fs
        } catch (_: Exception) {
        }
        return Result.None
    }

    private data class Cand(
        val nm: String, val sg: String, val hash: String,
        val dur: Int, val album: String, val rank: Int
    )

    private fun kgAccompaniment(name: String, artist: String, interval: Int): JSONObject? {
        val queries = ArrayList<String>()
        val first = artist.split(Regex("[/、,&·]")).firstOrNull()?.trim().orEmpty()
        if (first.isNotEmpty()) queries.add("$first $name 伴奏")
        queries.add("$name 伴奏")
        val wantN = name.lowercase()
        val wantA = firstSinger(artist)
        val cleanA = name.replace(" ", "") + "(伴奏)"
        val cleanB = name.replace(" ", "") + "（伴奏）"
        for (q in queries) {
            val url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=" +
                enc(q) + "&page=1&pagesize=30&showtype=1"
            val d = getJson(url, "https://www.kugou.com/") ?: continue
            val info = d.optJSONObject("data")?.optJSONArray("info") ?: continue
            var best: Cand? = null
            for (i in 0 until info.length()) {
                val it = info.optJSONObject(i) ?: continue
                val nm = (it.optString("songname").ifEmpty { it.optString("SongName") })
                    .replace(Regex("<[^>]+>"), "")
                val sg = it.optString("singername").ifEmpty { it.optString("SingerName") }
                val hash = (it.optString("hash").ifEmpty { it.optString("FileHash") }).lowercase()
                var dur = it.optInt("duration", 0)
                if (dur > 10000) dur = dur / 1000
                if (nm.isEmpty() || hash.isEmpty()) continue
                if (!nm.contains("伴奏")) continue
                val base = nm.replace(Regex("[（(]?\\s*伴奏\\s*[）)]?"), "")
                    .replace("伴奏", "").trim().lowercase()
                if (base.isEmpty() || (!base.contains(wantN) && !wantN.contains(base))) continue
                val gotA = sg.lowercase()
                val singerOk = wantA.isEmpty() || gotA.isEmpty() ||
                    gotA.contains(wantA) || wantA.contains(gotA)
                if (!singerOk) continue
                val diff = if (dur > 0 && interval > 0) kotlin.math.abs(dur - interval) else 99
                if (diff > 8) continue
                val noSpace = nm.replace(" ", "")
                val cleanForm = noSpace == cleanA || noSpace == cleanB
                val rank = diff * 10 + (if (cleanForm) 0 else 2) +
                    (if (Regex("自制|DIY|消音|扒带|翻制", RegexOption.IGNORE_CASE).containsMatchIn(nm)) 3 else 0) +
                    (if (Regex("Live|现场|演唱会", RegexOption.IGNORE_CASE).containsMatchIn(nm)) 4 else 0)
                val cand = Cand(nm, sg, hash, dur,
                    it.optString("album_name").ifEmpty { it.optString("AlbumName") }, rank)
                if (best == null || cand.rank < best.rank) best = cand
            }
            val b = best
            if (b != null) {
                return JSONObject()
                    .put("name", b.nm)
                    .put("artist", b.sg.ifEmpty { "未知" })
                    .put("songmid", b.hash)
                    .put("id", b.hash)
                    .put("hash", b.hash)
                    .put("albummid", "")
                    .put("album", b.album)
                    .put("cover", "")
                    .put("interval", if (b.dur > 0) b.dur else interval)
                    .put("source", "kugou")
            }
        }
        return null
    }

    private fun fiveSingAccompaniment(name: String, artist: String): Result.DirectUrl? {
        val wantN = name.lowercase()
        val wantA = firstSinger(artist)
        data class Pick(val id: String, val nm: String, val sg: String)
        val picks = ArrayList<Pick>()
        for (page in 1..3) {
            if (picks.size >= 4) break
            val url = "http://search.5sing.kugou.com/home/json?keyword=" +
                enc(name) + "&page=" + page + "&filterType=3"
            val d = getJson(url) ?: break
            val list = d.optJSONArray("list") ?: break
            if (list.length() == 0) break
            for (i in 0 until list.length()) {
                val it = list.optJSONObject(i) ?: continue
                val type = it.optInt("type", -1)
                val te = it.optString("typeEname").ifEmpty { it.optString("typeName") }
                if (!(type == 3 || te == "bz" || te.contains("伴奏"))) continue
                val nm = it.optString("songName").ifEmpty { it.optString("name") }
                    .replace(Regex("<[^>]+>"), "")
                val sg = it.optString("singerName").ifEmpty { it.optString("singer") }
                val orig = it.optString("originSinger")
                val base = nm.replace("伴奏", "")
                    .replace(Regex("[（(][^）)]*[）)]"), "").trim().lowercase()
                if (base.isEmpty() || (!base.contains(wantN) && !wantN.contains(base))) continue
                val aOk = wantA.isEmpty() ||
                    (sg.isNotEmpty() && (sg.lowercase().contains(wantA) || wantA.contains(sg.lowercase()))) ||
                    (orig.isNotEmpty() && (orig.lowercase().contains(wantA) || wantA.contains(orig.lowercase())))
                if (!aOk) continue
                val id = it.optString("songId").ifEmpty { it.optString("songid") }
                    .ifEmpty { it.optString("id") }
                if (id.isNotEmpty()) picks.add(Pick(id, nm, sg.ifEmpty { orig }))
            }
        }
        for (p in picks) {
            val u = "http://mobileapi.5sing.kugou.com/song/getSongUrl?songtype=bz&songid=" + enc(p.id)
            val r = getJson(u) ?: continue
            val dd = r.optJSONObject("data") ?: r
            val play = dd.optString("hqurl").ifEmpty { dd.optString("url") }
                .ifEmpty { dd.optString("lqurl") }
            if (play.startsWith("http")) return Result.DirectUrl(play, p.nm, p.sg)
        }
        return null
    }
}
