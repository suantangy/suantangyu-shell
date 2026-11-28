package com.suantangyu.player

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

// ===== 3.85.0 原生 Dock（三期：迷你条＋底部导航）=====
// 照网页 1_180 的 #dock 全栈复刻（审计 dock_audit_1_180.md）：三套
// 样式（琉璃光 liuli／网易风 netease／黑胶盘 heijiao）、琉璃光收起
// 态、详情页态、迷你歌词句、进度填充＋粒子（琉璃光）、进度环（网易
// 风／黑胶盘）、封面旋转。状态（当前页/收起/详情/样式/深色/主题色）
// 由网页 1_181 经 BaoBus.postDockState 推进来（网页的滚动收起状态
// 机、go() 切页都留在网页，壳只镜像＋把点击送回去）；歌曲内容走脑
// 快照（KeepAliveService.dockSnapshot，同播放页数据源）。
// 已知平台不等价：网页毛玻璃是 backdrop-filter（真背景模糊），原生
// 盖在 WebView 上做不到，只用同值半透明渐变近似（页面底多为浅色，
// 观感接近）——同播放页整页淡的账，交付时已向 cheng 明说。

object NativeDockStore {
    var enabled by mutableStateOf(false)
    var page by mutableStateOf("home")
    var collapsed by mutableStateOf(false)
    var detail by mutableStateOf(false)
    var style by mutableStateOf("liuli")
    var dark by mutableStateOf(false)
    var accent by mutableStateOf(Color(0xFFFA2D48))
    var accentHex by mutableStateOf("#fa2d48")
    var npAccent by mutableStateOf(Color(0xFFFA2D48))
    var imeOpen by mutableStateOf(false)
    var hasTrack by mutableStateOf(false)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var cover by mutableStateOf("")
    var lyric by mutableStateOf("")
    var playing by mutableStateOf(false)
    var anchorPosMs by mutableStateOf(0L)
    var anchorAtMs by mutableStateOf(0L)
    var durMs by mutableStateOf(0L)
    var rate by mutableStateOf(1f)

    fun livePosMs(): Long {
        var p = anchorPosMs
        if (playing && anchorAtMs > 0) {
            p += ((SystemClock.elapsedRealtime() - anchorAtMs) * rate).toLong()
        }
        if (durMs > 0) p = p.coerceIn(0, durMs)
        return maxOf(0, p)
    }
}

private const val D_PLAY = "M8 5v14l11-7z"
private const val D_PAUSE = "M6 5h4v14H6zM14 5h4v14h-4z"
private const val D_NEXT = "M6 18l8.5-6L6 6v12zM16 6h2v12h-2z"
private const val D_HOME = "M12 3.2L4 10v10h5v-6h6v6h5V10l-8-6.8z"
private const val D_LIB = "M4 5h16v2H4V5zm0 6h16v2H4v-2zm0 6h10v2H4v-2z"
private const val D_GEAR =
    "M19.1 12.9a7.4 7.4 0 0 0 0-1.8l2-1.6a.5.5 0 0 0 .1-.6l-1.9-3.3a.5.5 0 0 0-.6-.2l-2.4 1a7 7 0 0 0-1.6-1l-.4-2.5a.5.5 0 0 0-.5-.4h-3.8a.5.5 0 0 0-.5.4l-.4 2.5a7 7 0 0 0-1.6 1l-2.4-1a.5.5 0 0 0-.6.2L2.7 8.9a.5.5 0 0 0 .1.6l2 1.6a7.4 7.4 0 0 0 0 1.8l-2 1.6a.5.5 0 0 0-.1.6l1.9 3.3a.5.5 0 0 0 .6.2l2.4-1a7 7 0 0 0 1.6 1l.4 2.5a.5.5 0 0 0 .5.4h3.8a.5.5 0 0 0 .5-.4l.4-2.5a7 7 0 0 0 1.6-1l2.4 1a.5.5 0 0 0 .6-.2l1.9-3.3a.5.5 0 0 0-.1-.6l-2-1.6zM12 15.5A3.5 3.5 0 1 1 12 8.5a3.5 3.5 0 0 1 0 7z"
private const val D_QUEUE = "M4 6h16M4 12h16M4 18h10"
private const val D_SEARCH_LINE = "M21 21l-4.3-4.3"

private val DockEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
private fun DockSvg(pathData: String, size: Dp, color: Color, modifier: Modifier = Modifier) {
    // 同播放页 SvgIcon：缩放预先烘进路径坐标（支点坑，见 NativePlayer）
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
private fun DockSvgStroke(pathData: String, size: Dp, color: Color, strokeDp: Dp) {
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
            style = Stroke(width = strokeDp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun DockSearchIcon(size: Dp, color: Color) {
    // 网页搜索图标＝描边圆＋斜柄（分开画，避免 arc 路径解析差异）
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = this.size.minDimension
            drawCircle(
                color = color,
                radius = s * 7f / 24f,
                center = Offset(s * 11f / 24f, s * 11f / 24f),
                style = Stroke(width = s * 2f / 24f, cap = StrokeCap.Round)
            )
        }
        DockSvgStroke(D_SEARCH_LINE, size, color, (size.value * 2f / 24f).dp)
    }
}

@Composable
private fun Modifier.dockPress(scaleTo: Float = 0.85f, onClick: () -> Unit): Modifier {
    // 同播放页 aqClickable：关水波纹，按压本体缩放（网页 :active 同款）
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scaleTo else 1f, tween(120), label = "dkS")
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = src, indication = null) { onClick() }
}

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** 玻璃面（网页 backdrop 毛玻璃的近似：同值半透明渐变＋边＋软影） */
@Composable
private fun Modifier.dockGlass(shape: RoundedCornerShape, dark: Boolean, shown: Boolean): Modifier {
    val bg = if (dark) {
        if (shown) Color(0xC72C2C2E) else Color(0xD11C1C1E)
    } else {
        Color.White
    }
    val borderC = if (dark) Color.White.copy(alpha = if (shown) 0.10f else 0.08f)
    else Color.White.copy(alpha = if (shown) 0.85f else 0.55f)
    val shadowA = if (dark) 0.45f else 0.10f
    var m = this.shadow(
        10.dp, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = shadowA),
        spotColor = Color.Black.copy(alpha = shadowA)
    )
    m = if (dark) m.background(bg, shape)
    else m.background(
        Brush.verticalGradient(listOf(Color.White.copy(0.78f), Color.White.copy(0.62f))),
        shape
    )
    return m.border(0.5.dp, borderC, shape)
}

private class DockParticle(
    val startX: Float, val endX: Float, val y0: Float, val dy: Float,
    val durMs: Long, val s0: Float, val bornAt: Long
)

private val ParticleEase = CubicBezierEasing(0.25f, 0.5f, 0.35f, 1f)

/** 迷你歌词句：旧句左滑淡出、新句右入（网页 .32s/.28s）；超长单次跑马灯 */
@Composable
private fun DockLyricLine(text: String, color: Color) {
    val density = LocalDensity.current
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInHorizontally(tween(320, easing = DockEase)) {
                (28 * density.density).toInt()
            } + fadeIn(tween(280))) togetherWith
                (slideOutHorizontally(tween(320, easing = DockEase)) {
                    -(18 * density.density).toInt()
                } + fadeOut(tween(280)))
        },
        label = "dkLy"
    ) { t ->
        var boxW by remember { mutableStateOf(0) }
        var textW by remember { mutableStateOf(0) }
        val anim = remember(t) { Animatable(0f) }
        LaunchedEffect(t, boxW, textW) {
            anim.snapTo(0f)
            val over = textW - boxW
            if (over > 0 && boxW > 0) {
                delay(550)
                val dur = (over / density.density / 55f * 1000).toInt().coerceIn(1500, 9000)
                anim.animateTo(-over.toFloat(), tween(dur, easing = LinearEasing))
            }
        }
        Box(
            Modifier.fillMaxWidth().height(20.dp).clipToBounds()
                .onSizeChanged { boxW = it.width }
        ) {
            Box(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                    .offset { IntOffset(anim.value.roundToInt(), 0) }
                    .onSizeChanged { textW = it.width }
            ) {
                Text(
                    t, color = color, fontSize = 16.5.sp,
                    fontWeight = FontWeight.W600, maxLines = 1,
                    softWrap = false, lineHeight = 20.sp
                )
            }
        }
    }
}

/** 进度环（网易风/黑胶盘）：36px 画布、r16、描边 2.4、-90° 起、圆头 */
@Composable
private fun DockRing(frac: Float, accent: Color, dark: Boolean) {
    Canvas(Modifier.size(36.dp)) {
        val stroke = 2.4.dp.toPx()
        val r = 16.dp.toPx()
        val topLeft = Offset(size.width / 2 - r, size.height / 2 - r)
        val sz = androidx.compose.ui.geometry.Size(r * 2, r * 2)
        drawCircle(
            if (dark) Color.White.copy(0.16f) else Color.Black.copy(0.08f),
            radius = r, style = Stroke(stroke)
        )
        if (frac > 0.001f) {
            drawArc(
                accent, startAngle = -90f, sweepAngle = 360f * frac,
                useCenter = false, topLeft = topLeft, size = sz,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
    }
}

/** 黑胶盘：Ø76 径向黑盘＋同心纹＋光扫＋中心孔，封面 44 居中，整盘转 */
@Composable
private fun DockDisc(spin: Float, bmp: Bitmap?, dark: Boolean) {
    Box(
        Modifier.size(76.dp).graphicsLayer { rotationZ = spin },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFF2A2A30), Color(0xFF060608)),
                    center = center, radius = r
                ),
                radius = r
            )
            var rr = r * 0.97f
            val step = 3.dp.toPx()
            while (rr > r * 0.32f) {
                drawCircle(Color.White.copy(0.05f), radius = rr, style = Stroke(1f))
                rr -= step
            }
            drawArc(Color.White.copy(0.13f), startAngle = 210f, sweepAngle = 18f, useCenter = true)
            drawArc(Color.White.copy(0.08f), startAngle = 15f, sweepAngle = 23f, useCenter = true)
            drawCircle(Color(0xFF0B0B0D), radius = 3.5.dp.toPx())
            drawCircle(
                Color.White.copy(0.4f), radius = 3.5.dp.toPx(),
                style = Stroke(1.5.dp.toPx())
            )
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (dark) Color(0xFF2C2C2E) else Color(0xFFEEEEEE)),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

@Composable
private fun DockCover(sizeDp: Float, spin: Float, bmp: Bitmap?, dark: Boolean) {
    Box(
        Modifier.size(sizeDp.dp).graphicsLayer { rotationZ = spin }
            .clip(CircleShape)
            .background(if (dark) Color(0xFF2C2C2E) else Color(0xFFEEEEEE)),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun DockSideBtn(
    home: Boolean, sizeDp: Float, on: Boolean, dark: Boolean,
    accent: Color, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(percent = 50)
    val col = if (on) accent
    else if (dark) Color(235, 235, 245).copy(0.68f)
    else Color(60, 60, 67).copy(0.55f)
    Box(
        Modifier.size(sizeDp.dp).dockGlass(shape, dark, shown = false)
            .clip(shape).dockPress(0.9f) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (sizeDp > 30f) {
            if (home) DockSvg(D_HOME, 24.dp, col) else DockSearchIcon(24.dp, col)
        }
    }
}

@Composable
private fun DockMiniBar(
    isLiuli: Boolean, isNetease: Boolean, isHeijiao: Boolean,
    dark: Boolean, accent: Color, lyricColor: Color,
    miniH: Float, coverS: Float, nameSp: Float, nameWeight: FontWeight,
    nextW: Float, nextAlpha: Float, artistAlpha: Float,
    discOff: Float, discSpacerW: Float, detailT: Float,
    frac: Float, spin: Float, bmp: Bitmap?,
    onToggle: () -> Unit, onNext: () -> Unit,
    onOpenPlayer: () -> Unit, onQueue: () -> Unit
) {
    val st = NativeDockStore
    val btnColor = if (dark) Color.White else Color(0xFF1C1C1E)
    val nameColor = if (dark) Color.White else Color(0xFF1C1C1E)
    val artistColor = when {
        isHeijiao && dark -> Color.White.copy(0.55f)
        isHeijiao -> Color(0xFF8A8A92)
        dark -> Color(235, 235, 245).copy(0.68f)
        else -> Color(60, 60, 67).copy(0.72f)
    }
    val shape = if (isNetease) RoundedCornerShape(18.dp) else RoundedCornerShape(percent = 50)
    var barWpx by remember { mutableStateOf(0) }
    var barHpx by remember { mutableStateOf(0) }
    val dens = LocalDensity.current.density
    // —— 琉璃光粒子：播时从未播区飞向进度前沿（网页 spawnMiniParticles）——
    val particles = remember { mutableStateListOf<DockParticle>() }
    var particleFrame by remember { mutableStateOf(0L) }
    LaunchedEffect(st.playing, isLiuli, st.hasTrack) {
        if (!st.playing || !isLiuli || !st.hasTrack) {
            particles.clear()
            return@LaunchedEffect
        }
        var lastSpawn = 0L
        while (true) {
            withFrameMillis { fm ->
                particleFrame = fm
                particles.removeAll { fm - it.bornAt > it.durMs + 80 }
                val fracNow = if (st.durMs > 0) st.livePosMs().toFloat() / st.durMs else 0f
                if (fm - lastSpawn >= 110 && barWpx > 0 &&
                    fracNow > 0.001f && fracNow < 0.999f
                ) {
                    lastSpawn = fm
                    val edge = barWpx * fracNow
                    val unplayed = maxOf(20f * dens, barWpx - edge)
                    repeat(2 + Random.nextInt(2)) {
                        val startX = edge + unplayed * (0.2f + Random.nextFloat() * 0.8f)
                        val into = minOf(
                            edge * 0.55f,
                            (56f + Random.nextFloat() * 40f) * dens
                        )
                        val endX = maxOf(
                            8f * dens,
                            edge - into - Random.nextFloat() * 16f * dens
                        )
                        val y0 = (10f + Random.nextFloat() *
                            maxOf(24f, barHpx / dens - 20f)) * dens
                        val dyv = (Random.nextFloat() - 0.5f) * 36f * dens
                        particles.add(
                            DockParticle(
                                startX, endX, y0, dyv,
                                (2000 + Random.nextFloat() * 1300).toLong(),
                                0.7f + Random.nextFloat() * 0.6f, fm
                            )
                        )
                    }
                    while (particles.size > 28) particles.removeAt(0)
                }
            }
        }
    }
    Box(
        Modifier.fillMaxWidth().height(miniH.dp)
            .onSizeChanged { barWpx = it.width; barHpx = it.height }
    ) {
        Box(
            Modifier.fillMaxSize().dockGlass(shape, dark, shown = true).clip(shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onOpenPlayer() }
        ) {
            if (isLiuli && frac > 0f) {
                val stops = if (dark) arrayOf(
                    0f to accent.copy(0.32f), 0.55f to accent.copy(0.20f),
                    0.82f to accent.copy(0.08f), 0.94f to accent.copy(0.02f),
                    1f to Color.Transparent
                ) else arrayOf(
                    0f to accent.copy(0.26f), 0.55f to accent.copy(0.18f),
                    0.82f to accent.copy(0.08f), 0.94f to accent.copy(0.02f),
                    1f to Color.Transparent
                )
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(frac)
                        .background(Brush.horizontalGradient(colorStops = stops))
                )
            }
            if (isLiuli) {
                Canvas(Modifier.fillMaxSize()) {
                    val fm = particleFrame
                    for (p in particles) {
                        val prog = ((fm - p.bornAt).toFloat() / p.durMs).coerceIn(0f, 1f)
                        val e = ParticleEase.transform(prog)
                        val x = p.startX + (p.endX - p.startX) * e
                        val y = p.y0 + p.dy * e
                        val a = if (prog < 0.12f) 0.85f * (prog / 0.12f)
                        else 0.85f * (1f - (prog - 0.12f) / 0.88f)
                        val s = p.s0 + (0.35f - p.s0) * prog
                        val base = if (dark) androidx.compose.ui.graphics.lerp(
                            accent, Color.White, 0.30f
                        ) else accent
                        val alphaBase = if (dark) 1f else 0.75f
                        drawCircle(
                            base.copy(alpha = alphaBase * a.coerceIn(0f, 1f)),
                            radius = 1.75.dp.toPx() * s,
                            center = Offset(x, y)
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxSize()
                    .padding(start = if (isHeijiao) 0.dp else 8.dp, end = if (isHeijiao) 10.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isHeijiao) {
                    Spacer(Modifier.width(discSpacerW.dp))
                } else {
                    DockCover(coverS, spin, bmp, dark)
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        st.title, color = nameColor, fontSize = nameSp.sp,
                        fontWeight = nameWeight, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = (nameSp * 1.2f).sp,
                        letterSpacing = (-0.14).sp
                    )
                    Box(Modifier.fillMaxWidth().height(20.dp)) {
                        if (st.lyric.isNotEmpty()) {
                            DockLyricLine(st.lyric, lyricColor)
                        } else {
                            Text(
                                st.artist, color = artistColor, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                lineHeight = (12 * 1.35f).sp,
                                modifier = Modifier.alpha(artistAlpha)
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).dockPress(0.85f) { onToggle() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isNetease || isHeijiao) DockRing(frac, accent, dark)
                        DockSvg(if (st.playing) D_PAUSE else D_PLAY, 22.dp, btnColor)
                    }
                    if (nextW > 0.5f) {
                        Box(Modifier.width(nextW.dp).clipToBounds()) {
                            Box(
                                Modifier.size(40.dp).alpha(nextAlpha)
                                    .dockPress(0.85f) { onNext() },
                                contentAlignment = Alignment.Center
                            ) {
                                DockSvg(D_NEXT, 22.dp, btnColor)
                            }
                        }
                    }
                    if (isNetease) {
                        Box(
                            Modifier.size(40.dp).dockPress(0.85f) { onQueue() },
                            contentAlignment = Alignment.Center
                        ) {
                            DockSvgStroke(D_QUEUE, 22.dp, btnColor, 1.8.dp)
                        }
                    }
                }
            }
        }
        if (isHeijiao) {
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = discOff.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onOpenPlayer() }
            ) {
                DockDisc(spin, bmp, dark)
            }
        }
    }
}

@Composable
private fun DockTabBar(
    isNetease: Boolean, isHeijiao: Boolean, dark: Boolean, accent: Color,
    page: String, tabH: Float, navInset: Float,
    onTab: (String) -> Unit
) {
    val offColor = if (dark) Color(235, 235, 245).copy(0.72f)
    else Color(60, 60, 67).copy(0.78f)
    val shape = when {
        isNetease -> RoundedCornerShape(0.dp)
        isHeijiao -> RoundedCornerShape(percent = 50)
        else -> RoundedCornerShape(28.dp)
    }
    Box(Modifier.fillMaxWidth().height(tabH.dp)) {
        val inner = if (isNetease) {
            // 网易风通栏：无整圈边框/阴影，只有顶边线；内容坐在上方 60dp
            if (dark) Modifier.fillMaxSize().background(Color(0xD11C1C1E), shape)
            else Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(0.78f), Color.White.copy(0.62f))
                ),
                shape
            )
        } else {
            Modifier.fillMaxSize().dockGlass(shape, dark, shown = false)
        }
        Box(inner.clip(shape)) {
            if (isNetease) {
                Box(
                    Modifier.fillMaxWidth().height(0.5.dp).align(Alignment.TopCenter)
                        .background(
                            if (dark) Color.White.copy(0.08f) else Color.Black.copy(0.08f)
                        )
                )
            }
            Row(
                Modifier.fillMaxWidth()
                    .height(if (isNetease) 60.dp else tabH.dp)
                    .padding(
                        horizontal = if (isHeijiao) 8.dp else 0.dp,
                        vertical = if (isHeijiao) 4.dp else 0.dp
                    )
                    .align(Alignment.TopCenter)
            ) {
                val tabs = listOf(
                    Triple("home", "主页", 0),
                    Triple("search", "搜索", 1),
                    Triple("lib", "资料库", 2),
                    Triple("set", "设置", 3)
                )
                for ((p, label, _) in tabs) {
                    val on = page == p
                    val col = if (on) accent else offColor
                    val iconScale by animateFloatAsState(
                        if (on) 1.12f else 1f,
                        spring(dampingRatio = 0.5f, stiffness = 480f),
                        label = "tabIc"
                    )
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .dockPress(0.82f) { onTab(p) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }) {
                            when (p) {
                                "home" -> DockSvg(D_HOME, 24.dp, col)
                                "search" -> DockSearchIcon(24.dp, col)
                                "lib" -> DockSvg(D_LIB, 24.dp, col)
                                else -> DockSvg(D_GEAR, 24.dp, col)
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            label, color = col, fontSize = 10.sp,
                            fontWeight = FontWeight.Medium, lineHeight = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NativeDockRoot(
    onTab: (String) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit,
    onQueue: () -> Unit
) {
    val st = NativeDockStore
    if (!st.enabled || st.imeOpen) return
    if (st.detail && !st.hasTrack) return
    DockRootContent(onTab, onToggle, onNext, onOpenPlayer, onQueue)
}

@Composable
private fun DockRootContent(
    onTab: (String) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit,
    onQueue: () -> Unit
) {
    val st = NativeDockStore
    val isNetease = st.style == "netease"
    val isHeijiao = st.style == "heijiao"
    val isLiuli = !isNetease && !isHeijiao
    // 进度自走（仅播放时）：50ms 一跳推 frac/环
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(st.playing) {
        if (!st.playing) return@LaunchedEffect
        while (true) {
            delay(50)
            tick++
        }
    }
    val tickNow = tick
    val frac = if (st.durMs > 0 && tickNow >= 0) {
        (st.livePosMs().toFloat() / st.durMs).coerceIn(0f, 1f)
    } else 0f
    // 封面/盘旋转：播时按真实时间推进，停时冻在当前角（网页 paused 同款）
    var spin by remember { mutableStateOf(0f) }
    val spinPeriod = if (isHeijiao) 18000f else 16000f
    LaunchedEffect(st.playing, spinPeriod) {
        if (!st.playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            val now = withFrameMillis { it }
            if (last != 0L) {
                spin = (spin + (now - last).toFloat() / spinPeriod * 360f) % 360f
            }
            last = now
        }
    }
    val collapseT by animateFloatAsState(
        if (st.collapsed && isLiuli && !st.detail) 1f else 0f,
        tween(780, easing = DockEase), label = "dkCol"
    )
    val detailT by animateFloatAsState(
        if (st.detail) 1f else 0f, tween(600, easing = DockEase), label = "dkDet"
    )
    val navInset = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding().value.coerceAtMost(10f)
    // —— 几何（网页 CSS 级联终局，单位 dp=CSS px）——
    val miniH = if (!st.hasTrack) 0f
    else lerpF(lerpF(56f, 52f, collapseT), 58f, detailT)
    val coverS = when {
        isHeijiao -> 44f
        isNetease -> lerpF(44f, 40f, detailT)
        else -> lerpF(lerpF(44f, 36f, collapseT), 40f, detailT)
    }
    val nameSp = if (isLiuli) lerpF(14f, 13f, collapseT) else 14f
    val nameWeight = when {
        isNetease -> FontWeight(650)
        isHeijiao -> FontWeight.W600
        else -> FontWeight.W700
    }
    val nextW = lerpF(40f * (1f - collapseT), 40f, detailT)
    val nextAlpha = lerpF(1f - collapseT, 1f, detailT)
    val artistAlpha = 1f - collapseT
    val sideS = 52f * collapseT * (1f - detailT)
    val tabBase = if (isNetease) 60f + navInset else 56f
    val tabH = tabBase * (1f - collapseT) * (1f - detailT)
    val tabAlpha = (1f - collapseT) * (1f - detailT)
    val gapH = if (st.hasTrack) 4f * (1f - maxOf(collapseT, detailT)) else 0f
    val miniStart = when {
        isHeijiao -> lerpF(32f, 16f, detailT)
        isLiuli -> lerpF(lerpF(13f, 73f, collapseT), 13f, detailT)
        else -> 13f
    }
    val miniEnd = when {
        isHeijiao -> lerpF(59f, 13f, detailT)
        isLiuli -> lerpF(lerpF(13f, 73f, collapseT), 13f, detailT)
        else -> 13f
    }
    val tabStart = when {
        isNetease -> 0f
        isHeijiao -> lerpF(59f, 13f, detailT)
        else -> 13f
    }
    val tabEnd = if (isNetease) 0f else 13f
    val bottomPad = lerpF(
        if (isNetease) 0f else 4f + navInset, 13f + navInset, detailT
    )
    val discOff = lerpF(-19f, -3f, detailT)
    val discSpacerW = discOff + 76f + lerpF(12f, 10f, detailT)
    val tabScale = lerpF(1f, 0.88f, collapseT)
    val tabTyDp = lerpF(0f, 18f, collapseT)
    val lyricColor = if (st.dark) androidx.compose.ui.graphics.lerp(
        st.accent, Color.White, 0.35f
    ) else st.npAccent
    val bmp = CoverBitmap(st.cover)
    val dens = LocalDensity.current.density
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(bottom = bottomPad.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (sideS > 0.5f) {
                    DockSideBtn(true, sideS, st.page == "home", st.dark, st.accent) {
                        onTab("home")
                    }
                }
                Box(
                    Modifier.weight(1f)
                        .padding(start = (miniStart - 13f).dp, end = (miniEnd - 13f).dp)
                ) {
                    if (miniH > 0.5f) {
                        DockMiniBar(
                            isLiuli, isNetease, isHeijiao, st.dark, st.accent,
                            lyricColor, miniH, coverS, nameSp, nameWeight,
                            nextW, nextAlpha, artistAlpha, discOff, discSpacerW,
                            detailT, frac, spin, bmp,
                            onToggle, onNext, onOpenPlayer, onQueue
                        )
                    }
                }
                if (sideS > 0.5f) {
                    DockSideBtn(false, sideS, st.page == "search", st.dark, st.accent) {
                        onTab("search")
                    }
                }
            }
            if (gapH > 0.1f) Spacer(Modifier.height(gapH.dp))
            if (tabH > 0.5f) {
                Box(
                    Modifier.fillMaxWidth()
                        .padding(start = tabStart.dp, end = tabEnd.dp)
                        .alpha(tabAlpha)
                        .graphicsLayer {
                            scaleX = tabScale; scaleY = tabScale
                            translationY = tabTyDp * dens
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                        }
                ) {
                    DockTabBar(
                        isNetease, isHeijiao, st.dark, st.accent,
                        st.page, tabH, navInset, onTab
                    )
                }
            }
        }
    }
}
