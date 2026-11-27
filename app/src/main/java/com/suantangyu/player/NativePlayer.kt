// NativePlayer.kt — 逐面原生化二期:播放页全复刻(3.84.0)。
// 氛围底+明暗自适应(网页取色算法)+标题/歌手+原封面+译+整行歌词+
// 歌曲故事/喜欢/评论+四种进度样式(orbit 星轨/heart 心跳波/bar/glow,
// 跟随网页 aq_np_seek)+模式/上下首/播放/队列+工具卡全套(倍速/定时
// 原生直改;音质/海报/漫游/弹幕/音效/封面/跟唱/切源切网页播放页执行,
// 网页 1_179 的 __aqPlayerCmd tool:*)+网页版入口。
// 状态读脑 playerUiSnapshot(标题/封面/播放/进度锚点/时长/模式/倍速);
// 歌词与喜欢由网页 __aqPlayerPush 经 postPlayerData 推(壳开页后 2 秒轮询要)。
// 开合=弹簧整页平移(Compose 合成器执行,不是 WebView 的 JS 动画,不受
// 1_173/1_174 那条结案影响);顶部区可下拉关闭,松手弹簧吸附。
package com.suantangyu.player

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin

data class PlayerLine(val t: Long, val x: String)

object NativePlayerStore {
    var visible by mutableStateOf(false)
    var hideSignal by mutableStateOf(0)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var cover by mutableStateOf("")
    var quality by mutableStateOf("")
    var fav by mutableStateOf(false)
    var lyrics by mutableStateOf<List<PlayerLine>>(emptyList())
    var playing by mutableStateOf(false)
    var mode by mutableStateOf("list")
    var rate by mutableStateOf(1f)
    var anchorPosMs by mutableStateOf(0L)
    var anchorAtMs by mutableStateOf(0L)
    var durMs by mutableStateOf(0L)
    var sleepLabel by mutableStateOf("定时")
    var npAccent by mutableStateOf(Color(0xFFB83A4E))
    var seekStyle by mutableStateOf("orbit")
    var storyOn by mutableStateOf(false)
    var commentOn by mutableStateOf(true)
    var dmOn by mutableStateOf(false)
    var ktvOn by mutableStateOf(false)
    var transOn by mutableStateOf(false)
    var composeBeat by mutableStateOf(0)
    var accent by mutableStateOf(Color(0xFFE4002B))

    fun livePosMs(): Long {
        var p = anchorPosMs
        if (playing && anchorAtMs > 0) {
            p += ((SystemClock.elapsedRealtime() - anchorAtMs) * rate).toLong()
        }
        if (durMs > 0) p = p.coerceIn(0, durMs)
        return maxOf(0, p)
    }
}

private const val P_PLAY = "M8 5v14l11-7z"
private const val P_PAUSE = "M6 5h4v14H6zM14 5h4v14h-4z"
private const val P_PREV = "M6 6h2v12H6zm3.5 6l8.5 6V6z"
private const val P_NEXT = "M6 18l8.5-6L6 6v12zM16 6h2v12h-2z"
private const val P_REPEAT = "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"
private const val P_SHUFFLE =
    "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.83l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z"
private const val P_HEART =
private const val P_COMMENT =
    "M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z M8.3 10.2h7.4 M8.3 13.6h4.6"
    "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.6l-1-1a5.5 5.5 0 0 0-7.8 7.8l1 1L12 21l7.8-7.6 1-1a5.5 5.5 0 0 0 0-7.8z"
private const val P_LIST = "M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"
private const val P_MOON = "M21 14.3A8.5 8.5 0 1 1 9.7 3 7 7 0 0 0 21 14.3z"
private const val P_GRID = "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"

@Composable
private fun SvgIcon(pathData: String, size: Dp, color: Color, modifier: Modifier = Modifier) {
    // 缩放必须预先烘进路径坐标:drawContext.transform.scale 的支点是画布
    // 中心,24 单位的小路径会被整体推出画布外——3.82.0/3.82.1 真机所有
    // 图标 invisible 的真凶(文字/图片/色块不走这条路,故只有图标全灭)
    val density = LocalDensity.current
    val px = with(density) { size.toPx() }
    val path: Path = remember(pathData, px) {
        val p = PathParser().parsePathString(pathData).toPath()
        val m = Matrix()
        m.scale(px / 24f, px / 24f)
        p.transform(m)
        p
    }
    Canvas(modifier.size(size)) { drawPath(path, color) }
}

@Composable
private fun SvgIconStroke(pathData: String, size: Dp, color: Color, strokeDp: Dp) {
    val density = LocalDensity.current
    val px = with(density) { size.toPx() }
    val path: Path = remember(pathData, px) {
        val p = PathParser().parsePathString(pathData).toPath()
        val m = Matrix()
        m.scale(px / 24f, px / 24f)
        p.transform(m)
        p
    }
    Canvas(Modifier.size(size)) {
        drawPath(
            path, color,
            style = Stroke(
                width = strokeDp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round
            )
        )
    }
}

// 网页版 #npWave 的 42 根预设高度(heart 样式),逐值照搬
private val WAVE_H = intArrayOf(
    44, 99, 70, 47, 11, 20, 39, 75, 18, 32, 70, 30, 99, 95, 44, 100, 28, 32,
    65, 66, 73, 48, 77, 82, 19, 67, 66, 67, 29, 44, 97, 67, 28, 19, 58, 72,
    15, 76, 43, 19, 33, 40
)

@Composable
private fun SeekVisual(
    style: String, frac: Float, phase: Float, amp: Float,
    darkUi: Boolean, npAccent: Color, fg: Color
) {
    when (style) {
        "heart" -> {
            val onN = (frac * WAVE_H.size).roundToInt().coerceIn(0, WAVE_H.size)
            Row(
                Modifier.fillMaxWidth().height(36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WAVE_H.forEachIndexed { i, h ->
                    val played = i < onN
                    val hp = if (played) h.coerceIn(16, 100) / 100f
                    else (h * 0.28f).coerceIn(8f, 26f) / 100f
                    val pulse = if (amp > 0.05f) {
                        1f + 0.13f * sin(phase * 2f + i * 0.55f) * (amp / 2.4f)
                    } else 1f
                    Box(
                        Modifier.width(2.5.dp)
                            .fillMaxHeight((hp * pulse).coerceIn(0.05f, 1f))
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (played) npAccent
                                else fg.copy(alpha = if (darkUi) 0.30f else 0.42f)
                            )
                    )
                }
            }
        }
        "bar" -> {
            Box(
                Modifier.fillMaxWidth().height(28.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    Modifier.fillMaxWidth().height(3.5.dp)
                        .clip(RoundedCornerShape(2.dp)).background(fg.copy(alpha = 0.22f))
                )
                Box(
                    Modifier.fillMaxWidth(frac).height(3.5.dp)
                        .clip(RoundedCornerShape(2.dp)).background(npAccent)
                )
                Box(Modifier.fillMaxWidth(frac), contentAlignment = Alignment.CenterEnd) {
                    Box(
                        Modifier.offset { IntOffset(5.dp.roundToPx(), 0) }
                            .size(11.dp).clip(CircleShape).background(npAccent)
                    )
                }
            }
        }
        "glow" -> {
            Box(
                Modifier.fillMaxWidth().height(28.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    Modifier.fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(fg.copy(alpha = 0.18f))
                )
                Box(
                    Modifier.fillMaxWidth(frac).height(10.dp)
                        .clip(RoundedCornerShape(5.dp)).background(npAccent.copy(alpha = 0.30f))
                )
                Box(
                    Modifier.fillMaxWidth(frac).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(npAccent)
                )
                Box(Modifier.fillMaxWidth(frac), contentAlignment = Alignment.CenterEnd) {
                    Box(
                        Modifier.offset { IntOffset(5.dp.roundToPx(), 0) }
                            .size(10.dp).clip(CircleShape).background(npAccent)
                    )
                }
            }
        }
        else -> {
            // orbit 星轨(网页默认):暂停直线、播放起浪——18 周期正弦、
            // 波幅最大 2.4/48、相位 2π/4.5s 右→左推进,已播段与圆钮用主题深色
            Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                val w = size.width
                val mid = size.height / 2f
                val ampPx = amp / 48f * size.height
                val dim = if (darkUi) Color(0xFF1C1C1E).copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.32f)
                val sw = 1.6.dp.toPx()
                val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                fun yAt(t: Float): Float =
                    mid + sin(t * 18f * 2f * Math.PI.toFloat() + phase) * ampPx
                val dimPath = Path()
                val steps = 140
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val x = t * w
                    val y = yAt(t)
                    if (i == 0) dimPath.moveTo(x, y) else dimPath.lineTo(x, y)
                }
                drawPath(dimPath, dim, style = stroke)
                if (frac > 0.0008f) {
                    val litPath = Path()
                    val litSteps = maxOf(2, (steps * frac).toInt())
                    for (i in 0..litSteps) {
                        val t = frac * i / litSteps
                        val x = t * w
                        val y = yAt(t)
                        if (i == 0) litPath.moveTo(x, y) else litPath.lineTo(x, y)
                    }
                    drawPath(litPath, npAccent, style = stroke)
                }
                drawCircle(npAccent, radius = 4.dp.toPx(), center = Offset(frac * w, yAt(frac)))
            }
        }
    }
}

@Composable
private fun ToolItem(
    kind: String, label: String, fg: Color, onColor: Color,
    on: Boolean, onClick: () -> Unit
) {
    val tint = if (on) onColor else fg
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 5.dp)
    ) {
        if (kind == "sleep") SvgIcon(P_MOON, 22.dp, tint) else ToolIcon(kind, tint)
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 11.sp, maxLines = 1)
    }
}

// 工具卡图标:照网页版 SVG 的描边画法(24 网格、圆头描边)
@Composable
private fun ToolIcon(kind: String, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val sc = size.width / 24f
        val sw = 1.9.dp.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun pt(x: Float, y: Float) = Offset(x * sc, y * sc)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, pt(x1, y1), pt(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
        fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float) =
            drawRoundRect(
                color, topLeft = pt(x, y), size = Size(w * sc, h * sc),
                cornerRadius = CornerRadius(r * sc, r * sc), style = stroke
            )
        fun circ(cx: Float, cy: Float, r: Float, fill: Boolean = false) =
            if (fill) drawCircle(color, r * sc, pt(cx, cy))
            else drawCircle(color, r * sc, pt(cx, cy), style = stroke)
        when (kind) {
            "speed" -> {
                drawArc(
                    color, startAngle = 150f, sweepAngle = 300f, useCenter = false,
                    topLeft = pt(3f, 3f), size = Size(18f * sc, 18f * sc), style = stroke
                )
                line(12f, 12f, 16.5f, 9f)
                circ(12f, 12f, 1.3f, fill = true)
            }
            "quality" -> {
                line(3f, 9f, 3f, 15f); line(8f, 5f, 8f, 19f); line(13f, 2f, 13f, 22f)
                line(18f, 6f, 18f, 18f); line(22f, 10.5f, 22f, 13.5f)
            }
            "poster" -> {
                rrect(3f, 3f, 7f, 7f, 1.5f); rrect(14f, 3f, 7f, 7f, 1.5f)
                rrect(3f, 14f, 7f, 7f, 1.5f); rrect(14f, 14f, 7f, 7f, 1.5f)
            }
            "roam" -> {
                circ(12f, 12f, 9f)
                val needle = Path()
                needle.moveTo(15.5f * sc, 8.5f * sc)
                needle.lineTo(13.5f * sc, 13.5f * sc)
                needle.lineTo(8.5f * sc, 15.5f * sc)
                needle.lineTo(10.5f * sc, 10.5f * sc)
                needle.close()
                drawPath(needle, color)
            }
            "danmaku" -> {
                rrect(3f, 5f, 18f, 14f, 3f)
                line(7f, 10f, 13f, 10f); line(7f, 14f, 17f, 14f)
            }
            "eq" -> {
                line(6f, 3f, 6f, 7f); line(6f, 11f, 6f, 21f)
                line(12f, 3f, 12f, 13f); line(12f, 17f, 12f, 21f)
                line(18f, 3f, 18f, 5f); line(18f, 9f, 18f, 21f)
                circ(6f, 9f, 2.2f); circ(12f, 15f, 2.2f); circ(18f, 7f, 2.2f)
            }
            "cover" -> {
                rrect(3f, 3f, 18f, 18f, 2f)
                circ(9f, 9f, 1.8f)
                val m = Path()
                m.moveTo(21f * sc, 16f * sc)
                m.lineTo(15f * sc, 10.5f * sc)
                m.lineTo(5f * sc, 20f * sc)
                drawPath(m, color, style = stroke)
            }
            "ktv" -> {
                rrect(9f, 2f, 6f, 11f, 3f)
                drawArc(
                    color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                    topLeft = pt(5f, 7f), size = Size(14f * sc, 10f * sc), style = stroke
                )
                line(12f, 17.5f, 12f, 21f)
                line(8.5f, 21f, 15.5f, 21f)
            }
            "more" -> {
                line(4f, 8f, 19f, 8f)
                line(15.5f, 4.5f, 19.5f, 8f); line(15.5f, 11.5f, 19.5f, 8f)
                line(20f, 16f, 5f, 16f)
                line(8.5f, 12.5f, 4.5f, 16f); line(8.5f, 19.5f, 4.5f, 16f)
            }
            else -> {
                rrect(4f, 4f, 16f, 16f, 3f)
            }
        }
    }
}

private fun fmtTime(ms: Long): String {
    val sec = (ms / 1000).toInt()
    return "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
}

// ================= 封面取色(网页版同算法) =================
// 48x48 采样:滤太黑/太白/近灰,16 级量化桶取众数色,再按鲜艳色加权混合;
// lum = 0.65*lum(主色) + 0.35*lum(鲜艳色),经典模式 lum>88 → 深字浅底
private class CoverPalette(val darkUi: Boolean, val lyOn: Color)

private class PalBucket { var n = 0; var r = 0L; var g = 0L; var b = 0L; var sat = 0.0 }

private fun mixColor(c1: Color, c2: Color, t: Float): Color = Color(
    red = c1.red + (c2.red - c1.red) * t,
    green = c1.green + (c2.green - c1.green) * t,
    blue = c1.blue + (c2.blue - c1.blue) * t
)

private fun sampleCoverPalette(bmp: Bitmap): CoverPalette? {
    try {
        val w = 48; val h = 48
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== bmp) scaled.recycle()
        val buckets = HashMap<Int, PalBucket>()
        var vividR = 0; var vividG = 0; var vividB = 0; var vividSat = 0.0; var vividScore = -1.0
        var validN = 0
        for (p in px) {
            if (android.graphics.Color.alpha(p) < 150) continue
            val rr = android.graphics.Color.red(p)
            val gg = android.graphics.Color.green(p)
            val bb = android.graphics.Color.blue(p)
            val mx = maxOf(rr, gg, bb); val mn = minOf(rr, gg, bb)
            val lum = 0.299 * rr + 0.587 * gg + 0.114 * bb
            if (lum < 38 || lum > 242) continue
            if (rr > 250 && gg > 250 && bb > 250) continue
            val sat = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            if (sat < 0.12) continue
            validN++
            val key = ((rr shr 4) shl 8) or ((gg shr 4) shl 4) or (bb shr 4)
            val bk = buckets.getOrPut(key) { PalBucket() }
            bk.n++; bk.r += rr; bk.g += gg; bk.b += bb; bk.sat += sat
            if (sat >= 0.22) {
                val score = sat * 1.15 + ((mx - mn) / 255.0) * 0.55 + (lum / 255.0) * 0.35
                if (score > vividScore) {
                    vividScore = score; vividR = rr; vividG = gg; vividB = bb; vividSat = sat
                }
            }
        }
        if (validN == 0) return null
        var dom: PalBucket? = null
        for (b in buckets.values) {
            val cur = dom
            if (cur == null || b.n > cur.n) dom = b
        }
        val d = dom ?: return null
        val domR = d.r.toDouble() / d.n; val domG = d.g.toDouble() / d.n; val domB = d.b.toDouble() / d.n
        val domSat = d.sat / d.n
        var fr = domR; var fg2 = domG; var fb = domB
        if (vividScore >= 0) {
            val domShare = d.n.toDouble() / validN
            var wVivid = 0.18 + minOf(0.2, (vividSat - 0.22) * 0.45)
            if (domShare > 0.22 && domSat >= 0.18) wVivid = minOf(wVivid, 0.22)
            if (domShare > 0.35) wVivid = minOf(wVivid, 0.15)
            fr = domR * (1 - wVivid) + vividR * wVivid
            fg2 = domG * (1 - wVivid) + vividG * wVivid
            fb = domB * (1 - wVivid) + vividB * wVivid
        }
        val base = Color(
            fr.toInt().coerceIn(0, 255), fg2.toInt().coerceIn(0, 255), fb.toInt().coerceIn(0, 255)
        )
        val lumBase = 0.299 * fr + 0.587 * fg2 + 0.114 * fb
        val lumVivid = if (vividScore >= 0) 0.299 * vividR + 0.587 * vividG + 0.114 * vividB else lumBase
        val lum = lumBase * 0.65 + lumVivid * 0.35
        val darkUi = lum > 88
        val lyOn = if (darkUi) mixColor(base, Color.Black, 0.42f) else mixColor(base, Color.White, 0.12f)
        return CoverPalette(darkUi, lyOn)
    } catch (e: Exception) {
        return null
    }
}

@Composable
fun NativePlayerRoot(
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onMode: () -> Unit,
    onFav: () -> Unit,
    onRate: (Float) -> Unit,
    onSleep: (Int, Boolean, String) -> Unit,
    onQueue: () -> Unit,
    onOpenWeb: () -> Unit,
    onTool: (String) -> Unit,
    onComposed: () -> Unit = {}
) {
    val st = NativePlayerStore
    if (!st.visible) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val conf = LocalConfiguration.current
    val screenHPx = with(density) { conf.screenHeightDp.dp.toPx() }
    val offsetY = remember { Animatable(screenHPx) }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            offsetY.animateTo(screenHPx, spring(dampingRatio = 0.92f, stiffness = 300f))
            st.visible = false
            onClose()
        }
    }
    // hideSignal 跨开关持久累加:以本次合成时的值为基线,否则重开瞬间
    // 旧信号会被 LaunchedEffect 重放、页面刚合成就自我关闭(3.82.0/3.82.1
    // 「第一次能弹、后面弹不出」的真凶)
    val openSignal = remember { st.hideSignal }
    LaunchedEffect(st.hideSignal) { if (st.hideSignal > openSignal) close() }
    LaunchedEffect(Unit) {
        st.composeBeat++
        onComposed()
        offsetY.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 260f))
    }
    val dragMod = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = { scope.launch { offsetY.stop() } },
            onVerticalDrag = { _, dy ->
                scope.launch { offsetY.snapTo((offsetY.value + dy).coerceAtLeast(0f)) }
            },
            onDragEnd = {
                if (offsetY.value > 130.dp.toPx()) close()
                else scope.launch {
                    offsetY.animateTo(0f, spring(dampingRatio = 0.88f, stiffness = 300f))
                }
            },
            onDragCancel = {
                scope.launch { offsetY.animateTo(0f, spring(dampingRatio = 0.88f, stiffness = 300f)) }
            }
        )
    }

    // 显示进度(200ms 一跳;拖动时以拖动值为准)
    var dispPos by remember { mutableStateOf(st.livePosMs()) }
    var seeking by remember { mutableStateOf(false) }
    var seekPos by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!seeking) dispPos = st.livePosMs()
            delay(200)
        }
    }
    val shownPos = if (seeking) seekPos else dispPos
    // 星轨/心跳波动画(网页同参数):播放起浪——波幅以 6.5/s 平滑趋 2.4,
    // 相位 2π/4.5s 推进;暂停收成直线后停帧
    var orbitPhase by remember { mutableStateOf(0f) }
    var orbitAmp by remember { mutableStateOf(0f) }
    LaunchedEffect(st.playing, st.seekStyle) {
        val animated = st.seekStyle == "orbit" || st.seekStyle == "heart"
        var last = 0L
        while (true) {
            var settled = false
            withFrameMillis { ts ->
                if (last == 0L) last = ts
                var dt = (ts - last) / 1000f
                last = ts
                if (dt < 0f) dt = 0f
                if (dt > 0.05f) dt = 0.05f
                val target = if (st.playing && animated) 2.4f else 0f
                val diff = target - orbitAmp
                orbitAmp = if (kotlin.math.abs(diff) > 0.01f) {
                    orbitAmp + diff * minOf(1f, dt * 6.5f)
                } else target
                if (orbitAmp > 0.05f) {
                    orbitPhase += dt * (2f * Math.PI.toFloat() / 4.5f)
                    if (orbitPhase > 2f * Math.PI.toFloat()) {
                        orbitPhase -= 2f * Math.PI.toFloat()
                    }
                }
                settled = !st.playing && orbitAmp <= 0.05f
                if (settled) orbitAmp = 0f
            }
            if (settled) break
        }
    }
    val accent = st.accent
    // 明暗自适应(网页版同规则):封面取色 lum>88 → 深字浅底,否则白字深底
    val fullBmp = CoverBitmap(st.cover)
    val palette = remember(fullBmp) { fullBmp?.let { sampleCoverPalette(it) } }
    val darkUi = palette?.darkUi ?: false
    val lyOn = if (darkUi) mixColor(st.npAccent, Color.Black, 0.28f) else st.npAccent
    val wht = if (darkUi) Color(0xFF1C1C1E) else Color.White
    val w70 = wht.copy(alpha = if (darkUi) 0.62f else 0.72f)
    val w55 = wht.copy(alpha = if (darkUi) 0.48f else 0.55f)
    val w50 = wht.copy(alpha = 0.5f)

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .background(if (darkUi) Color(0xFFF4F1EC) else Color(0xFF101013))
    ) {
        // 氛围底:封面低清图放大铺满(天然模糊)+暗罩,与网页 np-bg 同思路
        val blurBmp = CoverBitmap(st.cover, tiny = true)
        if (blurBmp != null) {
            Image(
                blurBmp.asImageBitmap(), null,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                if (darkUi) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.38f)
            )
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(26.dp))
            // 抓手
            Box(
                Modifier.fillMaxWidth().then(dragMod).padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(width = 38.dp, height = 5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(wht.copy(alpha = 0.28f))
                )
            }
            // 信息区(可拖关闭)
            Column(
                Modifier.fillMaxWidth().then(dragMod),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    st.title.ifEmpty { "—" }, color = wht, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                )
                Text(
                    st.artist, color = w70, fontSize = 16.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            // 封面(原封面;72vw 上限 320dp、圆角 16——与网页 .np-art 一致;可拖关闭)
            val artSize = minOf(conf.screenWidthDp * 0.72f, 320f).dp
            Box(Modifier.fillMaxWidth().then(dragMod), contentAlignment = Alignment.Center) {
                val bmp = CoverBitmap(st.cover)
                Box(
                    Modifier.size(artSize)
                        .shadow(28.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF2A2A2E))
                ) {
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(), null,
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // 歌词(整行;点行 seek;自动跟当前行,手动滚动后 3 秒内不抢)
            val lines = st.lyrics
            val curIdx = remember(shownPos, lines) {
                var idx = -1
                for (i in lines.indices) {
                    if (lines[i].t <= shownPos) idx = i else break
                }
                idx
            }
            if (lines.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("暂无歌词", color = w50, fontSize = 14.sp)
                }
            } else {
                val listState = rememberLazyListState()
                var holdUntil by remember { mutableStateOf(0L) }
                LaunchedEffect(listState.isScrollInProgress) {
                    if (listState.isScrollInProgress) {
                        holdUntil = SystemClock.elapsedRealtime() + 3000
                    }
                }
                LaunchedEffect(curIdx) {
                    if (curIdx >= 0 && SystemClock.elapsedRealtime() > holdUntil) {
                        try {
                            listState.animateScrollToItem(curIdx, scrollOffset = -120)
                        } catch (_: Exception) {
                        }
                    }
                }
                // 译(与网页版同位:歌词区上方、右对齐;开时用主题色)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "译",
                        color = if (st.transOn) accent else w70,
                        fontSize = 13.sp,
                        fontWeight = if (st.transOn) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { onTool("trans") }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent, 0.14f to Color.Black,
                                    0.86f to Color.Black, 1f to Color.Transparent
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                ) {
                    itemsIndexed(lines, key = { i, l -> "$i|${l.t}" }) { i, line ->
                        val on = i == curIdx
                        Text(
                            line.x,
                            color = if (on) lyOn else w55,
                            fontSize = if (on) 16.sp else 14.5.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onSeek(line.t) }
                                .padding(vertical = 5.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 歌曲故事(左)/喜欢+评论(右):进度条上方一行,与网页版同位同款
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (st.storyOn) {
                    Text(
                        "歌曲故事", color = w70, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { onTool("story") }
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(38.dp).clip(CircleShape)
                        .clickable { onFav() },
                    contentAlignment = Alignment.Center
                ) {
                    if (st.fav) SvgIcon(P_HEART, 23.dp, accent)
                    else SvgIconStroke(P_HEART, 23.dp, wht, 1.8.dp)
                }
                if (st.commentOn) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .clickable { onTool("comment") },
                        contentAlignment = Alignment.Center
                    ) {
                        SvgIconStroke(P_COMMENT, 23.dp, wht, 1.8.dp)
                    }
                }
            }
            // 进度(四种样式与网页版一致:orbit 星轨/heart 心跳波/
            // bar 线条/glow 流光,跟随网页设置 aq_np_seek)
            val dur = maxOf(st.durMs, 1L)
            val seekFrac = (shownPos.toFloat() / dur).coerceIn(0f, 1f)
            val seekH = when (st.seekStyle) {
                "orbit" -> 48.dp
                "heart" -> 36.dp
                else -> 28.dp
            }
            Box(
                Modifier.fillMaxWidth().height(seekH)
                    .pointerInput(dur) {
                        fun fracOf(x: Float) = (x / size.width).coerceIn(0f, 1f)
                        detectDragGestures(
                            onDragStart = { o ->
                                seeking = true
                                seekPos = (dur * fracOf(o.x)).toLong()
                            },
                            onDrag = { change, _ ->
                                seekPos = (dur * fracOf(change.position.x)).toLong()
                            },
                            onDragEnd = { onSeek(seekPos); seeking = false },
                            onDragCancel = { seeking = false }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                SeekVisual(
                    st.seekStyle, seekFrac, orbitPhase, orbitAmp,
                    darkUi, st.npAccent, wht
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Text(fmtTime(shownPos), color = w55, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(fmtTime(st.durMs), color = w55, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            // 控制行
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).clickable { onMode() },
                    contentAlignment = Alignment.Center
                ) {
                    when (st.mode) {
                        "shuffle" -> SvgIcon(P_SHUFFLE, 25.dp, wht)
                        "single" -> Box(contentAlignment = Alignment.Center) {
                            SvgIcon(P_REPEAT, 25.dp, wht)
                            Text(
                                "1", color = wht, fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.offset(y = 1.dp)
                            )
                        }
                        else -> SvgIcon(P_REPEAT, 25.dp, wht)
                    }
                }
                Box(
                    Modifier.size(52.dp).clip(CircleShape).clickable { onPrev() },
                    contentAlignment = Alignment.Center
                ) { SvgIcon(P_PREV, 32.dp, wht) }
                Box(
                    Modifier.size(68.dp).clip(CircleShape).clickable { onToggle() },
                    contentAlignment = Alignment.Center
                ) { SvgIcon(if (st.playing) P_PAUSE else P_PLAY, 38.dp, wht) }
                Box(
                    Modifier.size(52.dp).clip(CircleShape).clickable { onNext() },
                    contentAlignment = Alignment.Center
                ) { SvgIcon(P_NEXT, 32.dp, wht) }
                Box(
                    Modifier.size(46.dp).clip(CircleShape).clickable { onQueue() },
                    contentAlignment = Alignment.Center
                ) { SvgIcon(P_LIST, 25.dp, wht) }
            }
            Spacer(Modifier.height(14.dp))
            Spacer(Modifier.height(12.dp))
            // 工具卡(与网页版全套一致:倍速/音质/定时/海报/漫游/弹幕/音效/
            // 封面/跟唱/切源,末尾加壳专属的网页版入口;倍速/定时原生直改,
            // 其余切到网页播放页打开对应面板/功能)
            val toolFg = if (darkUi) Color(0xFF1C1C1E) else Color.White
            val rateText = if (st.rate == st.rate.toLong().toFloat()) {
                "${st.rate.toLong()}.0x"
            } else "${st.rate}x"
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        if (darkUi) Color.White.copy(alpha = 0.55f)
                        else Color(0xFF1C1C1E).copy(alpha = 0.55f)
                    )
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolItem("speed", rateText, toolFg, accent, false) {
                    val steps = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                    val cur = steps.minByOrNull { kotlin.math.abs(it - st.rate) } ?: 1f
                    onRate(steps[(steps.indexOf(cur) + 1) % steps.size])
                }
                ToolItem("quality", "音质", toolFg, accent, false) { onTool("quality") }
                ToolItem("sleep", st.sleepLabel, toolFg, accent, false) {
                    val opts = listOf(
                        Triple(0, false, "定时"),
                        Triple(15, false, "15分"),
                        Triple(30, false, "30分"),
                        Triple(60, false, "60分"),
                        Triple(0, true, "本首后")
                    )
                    val idx = opts.indexOfFirst { it.third == st.sleepLabel }
                    val next = opts[(idx + 1) % opts.size]
                    onSleep(next.first, next.second, next.third)
                }
                ToolItem("poster", "海报", toolFg, accent, false) { onTool("poster") }
                ToolItem("roam", "漫游", toolFg, accent, false) { onTool("roam") }
                ToolItem("danmaku", "弹幕", toolFg, accent, st.dmOn) { onTool("danmaku") }
                ToolItem("eq", "音效", toolFg, accent, false) { onTool("eq") }
                ToolItem("cover", "封面", toolFg, accent, false) { onTool("cover") }
                ToolItem("ktv", "跟唱", toolFg, accent, st.ktvOn) { onTool("ktv") }
                ToolItem("more", "切源", toolFg, accent, false) { onTool("more") }
                // 壳专属:回网页播放页(网页版工具卡无此项,原生需要这个出口)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenWeb() }
                        .padding(vertical = 5.dp)
                ) {
                    SvgIcon(P_GRID, 22.dp, toolFg)
                    Spacer(Modifier.height(3.dp))
                    Text("网页版", color = toolFg, fontSize = 11.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(20.dp))

        }
    }
}
