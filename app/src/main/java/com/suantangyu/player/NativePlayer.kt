// NativePlayer.kt — 逐面原生化二期:播放页全复刻(3.84.0;3.84.1 按
// cheng 截图对照批次对齐:工具格 (100%-6)/5.28、图标 24/描边 2.15/
// 透明色、卡圆角 18、背景换网页原图模糊、整列按网页间距重排、
// 控制键 56/40 分大小号;3.84.2 修背景糊度(中间图 72→20px)+
// 亮度矩阵只乘对角发灰的 bug)。
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
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.nativeCanvas
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin

data class PlayerLine(val t: Long, val x: String, val tr: String = "")

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
    var pageDark by mutableStateOf(false)
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
    "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.6l-1-1a5.5 5.5 0 0 0-7.8 7.8l1 1L12 21l7.8-7.6 1-1a5.5 5.5 0 0 0 0-7.8z"
private const val P_COMMENT =
    "M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z M8.3 10.2h7.4 M8.3 13.6h4.6"
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

// 播放页歌词行(3.84.24 照网页 .ly 三档复刻):当前 22px/800、相邻
// 15px、远行 14px;远行带 0.5px 模糊与 0.94 缩放、相邻 0.97;字号用
// 网页同款弹簧曲线 .28s 涨落、颜色 .25s 过渡;长行换行不截断;
// 开译时主行下跟 12px 译文行(网页 .ly-trans)
@Composable
private fun PlayerLyricLine(
    line: PlayerLine, tier: Int, lyOn: Color, base: Color,
    showTr: Boolean, onClick: () -> Unit
) {
    val targetSize = when (tier) { 2 -> 22f; 1 -> 15f; else -> 14f }
    val animSize by animateFloatAsState(
        targetSize,
        tween(280, easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)),
        label = "lySize"
    )
    val targetColor = when (tier) {
        2 -> lyOn
        1 -> base.copy(alpha = 0.75f)
        else -> base.copy(alpha = 0.42f)
    }
    val animColor by animateColorAsState(targetColor, tween(250), label = "lyColor")
    val targetScale = when (tier) { 2 -> 1f; 1 -> 0.97f; else -> 0.94f }
    val animScale by animateFloatAsState(targetScale, tween(250), label = "lyScale")
    Column(
        Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .graphicsLayer { scaleX = animScale; scaleY = animScale }
            .then(if (tier == 0) Modifier.blur(0.5.dp) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            line.x,
            color = animColor,
            fontSize = animSize.sp,
            fontWeight = if (tier == 2) FontWeight.ExtraBold else FontWeight.Medium,
            letterSpacing = if (tier == 2) (-0.03f * animSize).sp else 0.sp,
            lineHeight = (animSize * 1.4f).sp,
            textAlign = TextAlign.Center
        )
        if (showTr && line.tr.isNotEmpty()) {
            Text(
                line.tr,
                color = base.copy(alpha = 0.45f),
                fontSize = 12.sp,
                lineHeight = 16.8.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
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
                Modifier.fillMaxWidth().height(44.dp),
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
                Modifier.fillMaxWidth().height(44.dp),
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
            Canvas(Modifier.fillMaxWidth().height(36.dp)) {
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
    on: Boolean, cellW: Dp, onClick: () -> Unit
) {
    val tint = if (on) onColor else fg
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(cellW)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 5.dp)
    ) {
        if (kind == "sleep") SvgIcon(P_MOON, 24.dp, tint) else ToolIcon(kind, tint)
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 11.sp, maxLines = 1)
    }
}

// 工具卡图标:照网页版 SVG 的描边画法(24 网格、圆头描边)
@Composable
private fun ToolIcon(kind: String, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val sc = size.width / 24f
        val sw = 2.15.dp.toPx()
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
// 明暗判定(网页 syncNpUiContrast):lum = 0.65*lum(c1) + 0.35*lum(c2),
// c1/c2 是提亮后的氛围色对(未过 sat14),lum>88 → 深字浅底
class CoverPalette(
    val darkUi: Boolean, val lyOn: Color, val c1: Color, val c2: Color,
    val c1Raw: Color, val c2Raw: Color
)

private class PalBucket { var n = 0; var r = 0L; var g = 0L; var b = 0L; var sat = 0.0 }

private fun mixColor(c1: Color, c2: Color, t: Float): Color = Color(
    red = c1.red + (c2.red - c1.red) * t,
    green = c1.green + (c2.green - c1.green) * t,
    blue = c1.blue + (c2.blue - c1.blue) * t
)

fun sampleCoverPalette(bmp: Bitmap): CoverPalette? {
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
        // 明暗判定必须用提亮后的 c1/c2(网页 syncNpUiContrast 同口径,
        // c1/c2 取未过 sat14 的原对):早先用原始主色/鲜艳色判,落日类
        // 封面被误判成深底白字(3.84.7 真机与网页版对照实锤)
        fun punchRaw(v: Double, lift: Double) = minOf(255.0, v * 0.85 + lift)
        val pc1r = punchRaw(fr, 70.0); val pc1g = punchRaw(fg2, 40.0); val pc1b = punchRaw(fb, 50.0)
        val pc2r = minOf(255.0, fr * 0.5 + 120)
        val pc2g = minOf(255.0, fg2 * 0.5 + 110)
        val pc2b = minOf(255.0, fb * 0.5 + 120)
        val lum = (0.299 * pc1r + 0.587 * pc1g + 0.114 * pc1b) * 0.65 +
            (0.299 * pc2r + 0.587 * pc2g + 0.114 * pc2b) * 0.35
        val darkUi = lum > 88
        val lyOn = if (darkUi) mixColor(base, Color.Black, 0.42f) else mixColor(base, Color.White, 0.12f)
        // 网页 rgbToAmbientPair 经典分支:punch 提亮得 c1、半量提亮得 c2;
        // 再过一遍 backdrop 的 saturate(1.4)(与网页 ::after 同步作用于
        // 染色层;饱和矩阵系数同 Android ColorMatrix:0.213/0.715/0.072)
        fun punch(v: Double, lift: Double) = minOf(255.0, v * 0.85 + lift).toInt()
        fun sat14(r: Int, g: Int, b: Int): Color {
            val s = 1.4; val i = 1 - s
            val rr = (0.213 * i + s) * r + 0.715 * i * g + 0.072 * i * b
            val gg = 0.213 * i * r + (0.715 * i + s) * g + 0.072 * i * b
            val bb = 0.213 * i * r + 0.715 * i * g + (0.072 * i + s) * b
            return Color(
                rr.toInt().coerceIn(0, 255),
                gg.toInt().coerceIn(0, 255),
                bb.toInt().coerceIn(0, 255)
            )
        }
        val c1ri = punch(fr, 70.0); val c1gi = punch(fg2, 40.0); val c1bi = punch(fb, 50.0)
        val c2ri = minOf(255.0, fr * 0.5 + 120).toInt()
        val c2gi = minOf(255.0, fg2 * 0.5 + 110).toInt()
        val c2bi = minOf(255.0, fb * 0.5 + 120).toInt()
        val c1 = sat14(c1ri, c1gi, c1bi)
        val c2 = sat14(c2ri, c2gi, c2bi)
        // 原始 c1/c2(未过 sat14):浅底新栈的染色层用原色,饱和由
        // 整叠合成后的统一 saturate(1.4) 施加(网页 backdrop 同序)
        val c1Raw = Color(c1ri, c1gi, c1bi)
        val c2Raw = Color(c2ri, c2gi, c2bi)
        return CoverPalette(darkUi, lyOn, c1, c2, c1Raw, c2Raw)
    } catch (e: Exception) {
        return null
    }
}


// 浅底背景绘制用饱和画笔:整叠(底+染色+图)合成后统一 saturate
// (1.4),对应网页 ::after 的 backdrop-filter;白罩在其外、不参与
private val npBgSatPaint = android.graphics.Paint().apply {
    colorFilter = android.graphics.ColorMatrixColorFilter(
        android.graphics.ColorMatrix().apply { setSaturation(1.4f) }
    )
    isFilterBitmap = true
}

/** 浅底氛围底离线合成(半分辨率,网页 .np-bg 层序):底 #FAFAFC →
 *  c1 径向染色(中心 (50%,-10%)、有效半径 (0.66W, 0.44H)、alpha
 *  .28、原始色)→ c2 径向(中心 (100%,40%)、有效半径 (0.45W,
 *  0.275H)、alpha .22)→ 中央窗强模糊图(alpha .80、sat 1.25、
 *  亮 1.12)。整叠的 saturate(1.4) 在绘制本位图时由 npBgSatPaint
 *  施加。参数出自 21 首网页版截图离线全栈拟合(条带均差 8.2) */
fun composeLightBg(blur: Bitmap, pa: CoverPalette, w: Int, h: Int): Bitmap {
    // 3.84.22:1/6 分辨率——产物是 σ≈50 的扩散糊图,细节早被糊
    // 掉,拉伸回全屏与半分辨率版看不出差别;像素量降到约 1/9,
    // 合成只要几十毫秒,播放页得以同步算完、首帧即定版背景
    val W = maxOf(2, w / 6); val H = maxOf(2, h / 6)
    val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(out)
    cv.drawColor(android.graphics.Color.rgb(250, 250, 252))
    fun radial(cx: Float, cy: Float, rx: Float, ry: Float, col: Color, a: Float) {
        val argb = android.graphics.Color.argb(
            (a * 255).toInt().coerceIn(0, 255),
            (col.red * 255).toInt().coerceIn(0, 255),
            (col.green * 255).toInt().coerceIn(0, 255),
            (col.blue * 255).toInt().coerceIn(0, 255)
        )
        val sh = android.graphics.RadialGradient(
            cx, cy, rx, intArrayOf(argb, 0), floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        val m = android.graphics.Matrix()
        m.setScale(1f, ry / rx, cx, cy)
        sh.setLocalMatrix(m)
        val p = android.graphics.Paint().apply { shader = sh; isAntiAlias = true }
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
    }
    radial(W * 0.5f, -H * 0.10f, W * 0.66f, H * 0.44f, pa.c1Raw, 0.28f)
    radial(W.toFloat(), H * 0.40f, W * 0.45f, H * 0.275f, pa.c2Raw, 0.22f)
    val cm = android.graphics.ColorMatrix()
    cm.setSaturation(1.25f)
    val arr = cm.array
    for (r in 0..2) for (c in 0..2) arr[r * 5 + c] = arr[r * 5 + c] * 1.12f
    val ip = android.graphics.Paint().apply {
        alpha = 204
        colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        isFilterBitmap = true
    }
    cv.drawBitmap(blur, null, android.graphics.Rect(0, 0, W, H), ip)
    return stackBlur(out)
}

/** 整叠扩散(网页 ::after 的 backdrop-filter: blur(12px)):底+染色
 *  +图合成后还要再过一遍约 σ50 CSS px 的高斯扩散,颜色才「摊开」
 *  而不是各层聚在自己的区域里(3.84.9 漏了这层,cheng 对照
 *  Got It 13 等多首指出网页版颜色扩散、App 版集聚)。这里用三遍
 *  可分离盒式模糊近似:半分辨率下半径 ≈ 宽×0.125(σ≈50),边缘钳制。
 *  3.84.10 的 σ≈13 在真机剖面上没登记出变化(与 3.84.9 逐点相同);
 *  Cherry Candy 对照剖面:网页版全宽缓坡、App 版高原+43 点陡降,
 *  离线验算 σ≈50 可把陡降压到约 18、形状贴网页版,故 3.84.11 加到此档 */
private fun stackBlur(src: Bitmap): Bitmap {
    val w = src.width; val h = src.height
    val radius = maxOf(3, (w * 0.125f).roundToInt())
    var cur = IntArray(w * h)
    src.getPixels(cur, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    fun blurPassH(r: Int) {
        for (y in 0 until h) {
            var sr = 0L; var sg = 0L; var sb = 0L
            val row = y * w
            for (i in -r..r) {
                val c = cur[row + i.coerceIn(0, w - 1)]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            val n = (2 * r + 1).toLong()
            for (x in 0 until w) {
                tmp[row + x] = android.graphics.Color.rgb(
                    (sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt()
                )
                val co = cur[row + (x - r).coerceIn(0, w - 1)]
                val ci = cur[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += ((ci shr 16) and 0xFF) - ((co shr 16) and 0xFF)
                sg += ((ci shr 8) and 0xFF) - ((co shr 8) and 0xFF)
                sb += (ci and 0xFF) - (co and 0xFF)
            }
        }
        System.arraycopy(tmp, 0, cur, 0, cur.size)
    }
    fun blurPassV(r: Int) {
        for (x in 0 until w) {
            var sr = 0L; var sg = 0L; var sb = 0L
            for (i in -r..r) {
                val c = cur[i.coerceIn(0, h - 1) * w + x]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            val n = (2 * r + 1).toLong()
            for (y in 0 until h) {
                tmp[y * w + x] = android.graphics.Color.rgb(
                    (sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt()
                )
                val co = cur[(y - r).coerceIn(0, h - 1) * w + x]
                val ci = cur[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += ((ci shr 16) and 0xFF) - ((co shr 16) and 0xFF)
                sg += ((ci shr 8) and 0xFF) - ((co shr 8) and 0xFF)
                sb += (ci and 0xFF) - (co and 0xFF)
            }
        }
        System.arraycopy(tmp, 0, cur, 0, cur.size)
    }
    repeat(3) { blurPassH(radius); blurPassV(radius) }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(cur, 0, w, 0, 0, w, h)
    return out
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
    // 页面淡入淡出(3.84.17 对齐网页版 .np:opacity .36s ease):
    // 打开 0→1、关闭 1→0、拖动跟手溶解,与位移补间并行
    val pageAlpha = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    // 关闭:定长补间,到点即结束——和网页版收回同一个机制(网页版
    // 实测 3 帧加速出画、无尾巴)。旧版弹簧(ζ0.92、目标=屏高)在最
    // 后一段渐近爬行:原始帧率录屏实测主滑行后还有约 0.25s 低速
    // 蠕动(帧间差 22→9.7→4.3→2.0→1.2 缓降),页面顶条在屏底慢慢
    // 蹭完最后一程(cheng:「快回到底部了还停一下才彻底收回」)。
    // 3.84.17 起参数与网页版 .np 逐项对齐:位移 .45s
    // cubic-bezier(0.32,0.72,0,1)、淡入淡出 .36s ease,均按剩余
    // 距离等比时长(iv 仅保留签名,时长不再按速度缩短)
    fun close(iv: Float = 0f) {
        if (closing) return
        closing = true
        scope.launch {
            try {
                val frac = ((screenHPx - offsetY.value) / screenHPx).coerceIn(0f, 1f)
                val dur = (450 * frac).toInt().coerceIn(140, 450)
                launch {
                    pageAlpha.animateTo(
                        0f,
                        tween(
                            durationMillis = (360 * frac).toInt().coerceIn(120, 360),
                            easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
                        )
                    )
                }
                offsetY.animateTo(
                    screenHPx,
                    tween(durationMillis = dur, easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f))
                )
                st.visible = false
                onClose()
            } catch (e: CancellationException) {
                // 被新拖动打断:不算关闭,交还给手势
                closing = false
            }
        }
    }
    // hideSignal 跨开关持久累加:以本次合成时的值为基线,否则重开瞬间
    // 旧信号会被 LaunchedEffect 重放、页面刚合成就自我关闭(3.82.0/3.82.1
    // 「第一次能弹、后面弹不出」的真凶)
    val openSignal = remember { st.hideSignal }
    LaunchedEffect(st.hideSignal) { if (st.hideSignal > openSignal) close() }
    val dragMod = Modifier.pointerInput(Unit) {
        val tracker = VelocityTracker()
        var dragPos = 0f
        fun rebound(v: Float) {
            // 回位也走补间(3.84.16:播放页与队列开/关/回位全改滑、
            // 两面统一):时长按当前位移等比,上甩够快时缩短
            scope.launch {
                val frac = (offsetY.value / screenHPx).coerceIn(0f, 1f)
                val dur = (450 * frac).toInt().coerceIn(120, 450)
                launch {
                    pageAlpha.animateTo(
                        1f,
                        tween(
                            durationMillis = (360 * frac).toInt().coerceIn(100, 360),
                            easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
                        )
                    )
                }
                offsetY.animateTo(
                    0f, tween(durationMillis = dur, easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f))
                )
            }
        }
        detectVerticalDragGestures(
            onDragStart = {
                tracker.resetTracking()
                dragPos = offsetY.value
                scope.launch { offsetY.stop() }
            },
            onVerticalDrag = { change, dy ->
                dragPos = (dragPos + dy).coerceAtLeast(0f)
                tracker.addPosition(change.uptimeMillis, Offset(0f, dragPos))
                scope.launch {
                    offsetY.snapTo(dragPos)
                    pageAlpha.snapTo(1f - (dragPos / screenHPx).coerceIn(0f, 1f))
                }
            },
            onDragEnd = {
                val v = tracker.calculateVelocity().y
                when {
                    v < -1500f -> rebound(v) // 向上甩够快=想留下
                    offsetY.value > 130.dp.toPx() || v > 1500f -> close(v)
                    else -> rebound(v)
                }
            },
            onDragCancel = { rebound(0f) }
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
    // 切歌氛围黏性(3.84.24):网页版新封面没就绪前一直用上一首的氛围,
    // 齐了才整体换。原生此前新歌数据一到就切,而取色/合成还没好,
    // darkUi 默认 false 把整页塌成黑底白字一闪(cheng:切歌闪屏、
    // 没网页自然)。shownCover 黏住上一首,等新歌取色+(浅底时)合成
    // 背景都进缓存才整帧换过去;2.5s 兜底防死等
    var shownCover by remember { mutableStateOf(st.cover) }
    LaunchedEffect(st.cover) {
        val target = st.cover
        if (target != shownCover) {
            val deadline = SystemClock.elapsedRealtime() + 2500
            while (SystemClock.elapsedRealtime() < deadline) {
                val pa = CoverCache.paletteOf(target)
                if (pa != null && (!pa.darkUi || CoverCache.composedOf(target) != null)) break
                delay(50)
            }
            shownCover = target
        }
    }
    // 明暗自适应(网页版同规则):封面取色 lum>88 → 深字浅底,否则白字深底
    val fullBmp = CoverBitmap(shownCover)
    val asyncPalette = remember(fullBmp) { fullBmp?.let { sampleCoverPalette(it) } }
    // 切歌时已预合成取色(postTrack → CoverCache.precompute):命中
    // 缓存时首帧即有取色,不必等封面异步加载(3.84.18)
    val palette = CoverCache.paletteOf(shownCover) ?: asyncPalette
    val darkUi = palette?.darkUi ?: false
    val lyOn = if (darkUi) mixColor(st.npAccent, Color.Black, 0.28f) else st.npAccent
    val wht = if (darkUi) Color(0xFF1C1C1E) else Color.White
    val w70 = wht.copy(alpha = if (darkUi) 0.62f else 0.72f)
    val w55 = wht.copy(alpha = if (darkUi) 0.48f else 0.55f)
    val w50 = wht.copy(alpha = 0.5f)

    // 封面取色就绪标记:fullBmp 到手的那次合成里 palette 已同步算出
    var artReady by remember { mutableStateOf(CoverCache.paletteOf(shownCover) != null) }
    LaunchedEffect(fullBmp) { if (fullBmp != null) artReady = true }
    LaunchedEffect(Unit) {
        st.composeBeat++
        onComposed()
        // 等取色就绪再滑入(250ms 超时兜底,封面拉不到照常开):取色
        // 未出时 darkUi 默认 false、页底纯黑,滑入时黑色页头会
        // 先在屏底露一截(cheng 实录:第一次点迷你条,底部先出黑色
        // 条块,然后才弹出播放页)。切歌时 postTrack 已预加载封面,
        // 常态下这里零等待
        if (st.cover.isNotEmpty() && !artReady) {
            withTimeoutOrNull(250) { snapshotFlow { artReady }.first { it } }
        }
        launch {
            pageAlpha.animateTo(
                1f,
                tween(durationMillis = 360, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))
            )
        }
        offsetY.animateTo(0f, tween(durationMillis = 450, easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)))
    }

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .alpha(pageAlpha.value)
            .background(if (darkUi) Color(0xFFFAFAFC) else Color.Black)
    ) {
        // 氛围底(3.84.9 按网页 .np-bg 的几何与层序重做):网页背景
        // 是封面 145% 居中 cover 铺放,屏幕只见封面中央窗,位置对
        // 位置;层序=底 #FAFAFC → c1/c2 径向染色(原始色 .28/.22)
        // → 图(中央窗强模糊、sat 1.25、亮 1.12、alpha .80)→
        // 整叠先过 ::after 的 backdrop blur(12px) 扩散、再
        // saturate(1.4)(npBgSatPaint)→ 白罩 .35
        // (页面深主题 .32)。参数由 21 首网页版截图离线全栈模拟
        // 拟合(条带均差 8.2,旧栈真机约 14)。浅底走 composeLightBg
        // 离线合成 + npBgSatPaint 绘制;深底仍走旧路径
        val blurBmp = CoverBitmap(shownCover, blurred = true)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val bw = constraints.maxWidth
            val bh = constraints.maxHeight
            val pa = palette
            // 3.84.9 起此处条件曾写反(!darkUi):浅底页(darkUi=true,
            // 绝大多数歌)一直走下面的旧路径,composeLightBg 只在深底页
            // 跑过——3.84.10/3.84.11 调的扩散对浅底页全程没生效,真机
            // 剖面逐点不变就是这个原因。3.84.12 改回 darkUi。
            if (darkUi && pa != null && blurBmp != null) {
                // 合成背景优先读切歌时预合成的缓存;未命中才后台现算
                // (3.84.18:此前在 remember 里同步现算,stackBlur 在主
                //  线程阻塞滑动帧——真机表现为迟滞+闪屏)
                // 3.84.22:合成改同步——composeLightBg 已降到 1/6 分辨
                // 率(内容是 σ≈50 的扩散糊图,拉伸回全屏看不出差别),
                // 一次几十毫秒,remember 里当场算完,首帧就是定版背景。
                // 此前 produceState 异步现算:约 0.2s 才换上,封面上下
                // 两条背景带一步跳变(cheng 两段录屏逐帧实锤);异步到
                // 达这件事本身就是闪的来源,不再依赖缓存命中与否
                val comp = remember(blurBmp, pa, bw, bh, shownCover) {
                    CoverCache.composedOf(shownCover) ?: runCatching {
                        composeLightBg(blurBmp, pa, bw, bh)
                    }.getOrNull()?.also { CoverCache.putComposed(shownCover, it) }
                }
                if (comp != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawContext.canvas.nativeCanvas.drawBitmap(
                            comp, null,
                            android.graphics.RectF(0f, 0f, size.width, size.height),
                            npBgSatPaint
                        )
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Color.White.copy(alpha = if (st.pageDark) 0.32f else 0.35f)
                        )
                    )
                } else {
                    // 预合成未就绪:先用强模糊图本体顶上(经同款饱和漆
                    // 绘制+同罩),与合成背景只差染色与扩散,合成图到达
                    // 时是小步切换而非整片闪变(3.84.21:录屏实锤开页
                    // 约 0.2s 时背景一步跳变,封面上下两条背景带最扎眼
                    // ——此前拿纯平浅底垫场,那一步跳变最大)
                    Canvas(Modifier.fillMaxSize()) {
                        drawContext.canvas.nativeCanvas.drawBitmap(
                            blurBmp, null,
                            android.graphics.RectF(0f, 0f, size.width, size.height),
                            npBgSatPaint
                        )
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Color.White.copy(alpha = if (st.pageDark) 0.32f else 0.35f)
                        )
                    )
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(if (darkUi) Color(0xFFFAFAFC) else Color.Black)
                        if (pa != null) {
                            drawRect(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        pa.c1.copy(alpha = if (darkUi) 0.28f else 0.36f),
                                        Color.Transparent
                                    ),
                                    center = Offset(bw * 0.5f, -bh * 0.10f),
                                    radius = bw * 0.66f
                                )
                            )
                            drawRect(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        pa.c2.copy(alpha = if (darkUi) 0.22f else 0.23f),
                                        Color.Transparent
                                    ),
                                    center = Offset(bw.toFloat(), bh * 0.40f),
                                    radius = bw * 0.45f
                                )
                            )
                        }
                    }
                    if (blurBmp != null) {
                        val imgMatrix = remember(darkUi) {
                            ColorMatrix().apply {
                                setToSaturation(if (darkUi) 1.4f else 1.45f)
                                val b = if (darkUi) 1.16f else 0.7f
                                for (r in 0..2) {
                                    for (c in 0..2) {
                                        this[r, c] = this[r, c] * b
                                    }
                                }
                            }
                        }
                        Image(
                            blurBmp.asImageBitmap(), null,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                            alpha = if (darkUi) 0.9f else 0.85f,
                            colorFilter = ColorFilter.colorMatrix(imgMatrix)
                        )
                    }
                }
                Box(
                    Modifier.fillMaxSize().background(
                        if (darkUi) {
                            Color.White.copy(alpha = if (st.pageDark) 0.32f else 0.48f)
                        } else {
                            Color.Black.copy(alpha = 0.35f)
                        }
                    )
                )
            }
        }

        val safeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val safeBot = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.fillMaxSize()) {
            // 抓手(网页 .np-grab:margin-top = 10px + 安全区,36x5)
            Spacer(Modifier.height(safeTop + 10.dp))
            Box(
                Modifier.fillMaxWidth().then(dragMod),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(width = 36.dp, height = 5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(wht.copy(alpha = 0.28f))
                )
            }
            // 信息区(网页 .np-info:padding 8/28/4;可拖关闭)
            Column(
                Modifier.fillMaxWidth().then(dragMod)
                    .padding(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 4.dp),
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
            // 封面(网页 .np-art-wrap:margin-top 10、padding 8/36/12、
            // margin-bottom 4;.np-art = min(72vw, 320px)、圆角 16;可拖关闭)
            Spacer(Modifier.height(10.dp))
            val artSize = minOf(conf.screenWidthDp * 0.72f, 320f).dp
            Box(
                Modifier.fillMaxWidth().then(dragMod)
                    .padding(start = 36.dp, end = 36.dp, top = 8.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                val bmp = CoverBitmap(shownCover)
                Box(Modifier.size(artSize)) {
                    // 手绘柔影代替系统 elevation 阴影(3.84.23):系统阴影
                    // 在整页淡入的离屏层里渲染更深、淡完层一撤就变浅,
                    // 开页约 0.36s 时封面下方那块阴影一步跳变(cheng 指
                    // 认「封面下面歌词上面那块」;逐帧实测:跳变只落在
                    // 封面正下方的阴影 footprint 内、左右纯背景纹丝不
                    // 动)。BlurMaskFilter 是普通绘制,层内外一个样
                    Box(
                        Modifier.fillMaxSize().drawBehind {
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.argb(54, 0, 0, 0)
                                maskFilter = android.graphics.BlurMaskFilter(
                                    24.dp.toPx(),
                                    android.graphics.BlurMaskFilter.Blur.NORMAL
                                )
                            }
                            val dy = 6.dp.toPx()
                            val r = 16.dp.toPx()
                            drawContext.canvas.nativeCanvas.drawRoundRect(
                                0f, dy, size.width, size.height + dy, r, r, paint
                            )
                        }
                    )
                    Box(
                        Modifier.fillMaxSize()
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
            }
            Spacer(Modifier.height(4.dp))
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
                            val vh = listState.layoutInfo.viewportSize.height
                            val off = if (vh > 0) (-(vh * 0.42f).toInt() + 150) else -120
                            listState.animateScrollToItem(curIdx, scrollOffset = off)
                        } catch (_: Exception) {
                        }
                    }
                }
                // 译(与网页版同位:歌词区上方、右对齐;开时用主题色)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.End
                ) {
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
                        PlayerLyricLine(
                            line = line,
                            tier = when {
                                i == curIdx -> 2
                                kotlin.math.abs(i - curIdx) == 1 -> 1
                                else -> 0
                            },
                            lyOn = lyOn, base = wht, showTr = st.transOn,
                            onClick = { onSeek(line.t) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 歌曲故事(左)/喜欢+评论(右):进度条上方一行,与网页版同位同款
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
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
            // 盒高对齐网页 .np-seek(3.84.24):orbit 36、heart/glow 44;
            // 此前 orbit 48 把时间/控制行整体顶低约 14dp(cheng 实测
            // 网页进度条偏上)
            val seekH = when (st.seekStyle) {
                "orbit" -> 36.dp
                "heart" -> 44.dp
                "glow" -> 44.dp
                else -> 28.dp
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(seekH)
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text(fmtTime(shownPos), color = w55, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(fmtTime(st.durMs), color = w55, fontSize = 11.sp)
            }
            // 控制行(网页 .np-controls:padding 8/18/10、gap 26)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val sideTint = wht.copy(alpha = 0.88f)
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { onMode() },
                    contentAlignment = Alignment.Center
                ) {
                    when (st.mode) {
                        "shuffle" -> SvgIcon(P_SHUFFLE, 22.dp, sideTint)
                        "single" -> Box(contentAlignment = Alignment.Center) {
                            SvgIcon(P_REPEAT, 22.dp, sideTint)
                            Text(
                                "1", color = sideTint, fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.offset(y = 1.dp)
                            )
                        }
                        else -> SvgIcon(P_REPEAT, 22.dp, sideTint)
                    }
                }
                Box(
                    Modifier.size(56.dp).clip(CircleShape).clickable { onPrev() },
                    contentAlignment = Alignment.Center
                ) { SvgIcon(P_PREV, 36.dp, wht) }
                Box(
                    Modifier.size(56.dp).clip(CircleShape).clickable { onToggle() },
                    contentAlignment = Alignment.Center
                ) { SvgIcon(if (st.playing) P_PAUSE else P_PLAY, 34.dp, wht) }
                Box(
                    Modifier.size(56.dp).clip(CircleShape).clickable { onNext() },
                    contentAlignment = Alignment.Center
                ) { SvgIcon(P_NEXT, 36.dp, wht) }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { onQueue() },
                    contentAlignment = Alignment.Center
                ) { SvgIconStroke("M4 6h16M4 12h16M4 18h10", 22.dp, sideTint, 2.2.dp) }
            }
            // 工具卡(网页 .np-tools 同款:margin 4/18/(18+安全区)、圆角 18、
            // padding 6;每格宽 = (卡内宽 - 6) / 5.28,一屏正好露 5.28 个;
            // 倍速/定时原生直改,其余经 onTool 切网页播放页执行)
            Spacer(Modifier.height(4.dp))
            val toolFg = if (darkUi) {
                Color(0xFF1C1C1E).copy(alpha = 0.72f)
            } else {
                Color.White.copy(alpha = 0.78f)
            }
            val rateText = if (st.rate == st.rate.toLong().toFloat()) {
                "${st.rate.toLong()}.0x"
            } else "${st.rate}x"
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                val cellW = (maxWidth - 12.dp - 6.dp) / 5.28f
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (darkUi) Color.White.copy(alpha = 0.55f)
                            else Color(0xFF1C1C1E).copy(alpha = 0.55f)
                        )
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolItem("speed", rateText, toolFg, accent, false, cellW) {
                        val steps = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                        val cur = steps.minByOrNull { kotlin.math.abs(it - st.rate) } ?: 1f
                        onRate(steps[(steps.indexOf(cur) + 1) % steps.size])
                    }
                    ToolItem("quality", "音质", toolFg, accent, false, cellW) { onTool("quality") }
                    ToolItem("sleep", st.sleepLabel, toolFg, accent, false, cellW) {
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
                    ToolItem("poster", "海报", toolFg, accent, false, cellW) { onTool("poster") }
                    ToolItem("roam", "漫游", toolFg, accent, false, cellW) { onTool("roam") }
                    ToolItem("danmaku", "弹幕", toolFg, accent, st.dmOn, cellW) { onTool("danmaku") }
                    ToolItem("eq", "音效", toolFg, accent, false, cellW) { onTool("eq") }
                    ToolItem("cover", "封面", toolFg, accent, false, cellW) { onTool("cover") }
                    ToolItem("ktv", "跟唱", toolFg, accent, st.ktvOn, cellW) { onTool("ktv") }
                    ToolItem("more", "切源", toolFg, accent, false, cellW) { onTool("more") }
                    // 壳专属:回网页播放页(网页版工具卡无此项,原生需要这个出口)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(cellW)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenWeb() }
                            .padding(vertical = 5.dp)
                    ) {
                        SvgIcon(P_GRID, 24.dp, toolFg)
                        Spacer(Modifier.height(3.dp))
                        Text("网页版", color = toolFg, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(18.dp + safeBot))


        }
    }
}
