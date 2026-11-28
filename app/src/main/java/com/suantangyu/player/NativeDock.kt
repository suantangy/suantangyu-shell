package com.suantangyu.player

import android.graphics.Bitmap
import android.util.LruCache
import android.graphics.BlurMaskFilter
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
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
    // 3.85.1 真毛玻璃：MainActivity 截取 Dock 背后网页画面糊化后垫底（backdropTopPx=截取区顶边窗口坐标）
    var backdrop by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var backdropTopPx by mutableStateOf(-1)
    var backdropScale by mutableStateOf(0.25f)
    var regionTopPx by mutableStateOf(-1)
    // 3.85.4：底图位移补偿（截取后页面又滚过的距离，切片绘制时平移＋纵向拉伸补边）
    var backdropShiftPx by mutableStateOf(0)
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
private const val D_NEXT = "M6 4l10 8-10 8V4zm12 0h2v16h-2z"
private const val D_HOME = "M12 3.2L4 10v10h5v-6h6v6h5V10l-8-6.8z"
private const val D_LIB = "M4 5h16v2H4V5zm0 6h16v2H4v-2zm0 6h10v2H4v-2z"
private const val D_GEAR =
    "M19.1 12.9a7.4 7.4 0 0 0 0-1.8l2-1.6a.5.5 0 0 0 .1-.6l-1.9-3.3a.5.5 0 0 0-.6-.2l-2.4 1a7 7 0 0 0-1.6-1l-.4-2.5a.5.5 0 0 0-.5-.4h-3.8a.5.5 0 0 0-.5.4l-.4 2.5a7 7 0 0 0-1.6 1l-2.4-1a.5.5 0 0 0-.6.2L2.7 8.9a.5.5 0 0 0 .1.6l2 1.6a7.4 7.4 0 0 0 0 1.8l-2 1.6a.5.5 0 0 0-.1.6l1.9 3.3a.5.5 0 0 0 .6.2l2.4-1a7 7 0 0 0 1.6 1l.4 2.5a.5.5 0 0 0 .5.4h3.8a.5.5 0 0 0 .5-.4l.4-2.5a7 7 0 0 0 1.6-1l2.4 1a.5.5 0 0 0 .6-.2l1.9-3.3a.5.5 0 0 0-.1-.6l-2-1.6zM12 15.5A3.5 3.5 0 1 1 12 8.5a3.5 3.5 0 0 1 0 7z"
private const val D_QUEUE = "M4 6h16M4 12h16M4 18h10"
private const val D_SEARCH_LINE = "M21 21l-4.3-4.3"

private val DockEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val EaseCss = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

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
private fun Modifier.dockGlass(shape: RoundedCornerShape, dark: Boolean, shown: Boolean, bg: Boolean = true): Modifier {
    val bgC = if (dark) {
        if (shown) Color(0xC72C2C2E) else Color(0xD11C1C1E)
    } else {
        Color.White
    }
    val borderC = if (dark) Color.White.copy(alpha = if (shown) 0.10f else 0.08f)
    else Color.White.copy(alpha = if (shown) 0.85f else 0.55f)
    // 3.85.6：阴影改手绘软影 DockHalo（网页 box-shadow 式四周晕开）；
    // 系统 elevation 影太硬太小，到不了网页 0 10px 32px 的晕感，此处不再叠
    var m: Modifier = this
    if (bg) {
        m = if (dark) m.background(bgC, shape)
        else m.background(
            Brush.verticalGradient(listOf(Color.White.copy(0.78f), Color.White.copy(0.62f))),
            shape
        )
    }
    return m.border(0.5.dp, borderC, shape)
}

/** 3.85.6 网页 box-shadow 软影：手绘模糊圆角矩形——四周晕开＋向下偏移。
 * alpha2 是近边第二层（小偏移小模糊），参数照网页各面 box-shadow 实值。 */
@Composable
private fun DockHalo(radiusDp: Float, offYDp: Float, blurDp: Float, alpha: Float, alpha2: Float = 0f) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            val r = radiusDp.dp.toPx()
            val oy = offYDp.dp.toPx()
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(
                    (alpha * 255).toInt().coerceIn(0, 255), 0, 0, 0
                )
                maskFilter = BlurMaskFilter(blurDp.dp.toPx() / 2f, BlurMaskFilter.Blur.NORMAL)
            }
            drawContext.canvas.nativeCanvas.drawRoundRect(
                0f, oy, size.width, size.height + oy, r, r, paint
            )
            if (alpha2 > 0f) {
                val p2 = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(
                        (alpha2 * 255).toInt().coerceIn(0, 255), 0, 0, 0
                    )
                    maskFilter = BlurMaskFilter(blurDp.dp.toPx() / 6f, BlurMaskFilter.Blur.NORMAL)
                }
                drawContext.canvas.nativeCanvas.drawRoundRect(
                    0f, oy / 5f, size.width, size.height + oy / 5f, r, r, p2
                )
            }
        }
    )
}

/**
 * 3.85.1 真毛玻璃切片：把 MainActivity 截好的糊化图按本元素在窗口中的位置对位画出，
 * 再压一层与网页玻璃同值的罩（wash）。须放在已被 clip 的容器内当第一个子元素。
 */
@Composable
private fun DockBackdropSlice(wash: Brush) {
    val st = NativeDockStore
    val bmp = st.backdrop ?: return
    var winPos by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { winPos = it.positionInWindow() }
            .drawBehind {
                val scale = st.backdropScale
                val dstW = (bmp.width / scale).roundToInt()
                val dstH = (bmp.height / scale).roundToInt()
                val shift = st.backdropShiftPx
                drawImage(
                    bmp,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bmp.width, bmp.height),
                    dstOffset = IntOffset(
                        -winPos.x.roundToInt(),
                        -(winPos.y - st.backdropTopPx).roundToInt() - shift
                    ),
                    dstSize = IntSize(dstW, dstH + maxOf(0, shift))
                )
                drawRect(wash, size = size)
            }
    )
}

/** 与 dockGlass 的底色同值：有截取图时改由切片画（图在下、罩在上） */
private fun dockWash(shown: Boolean, dark: Boolean): Brush {
    return if (dark) {
        val c = if (shown) Color(0xC72C2C2E) else Color(0xD11C1C1E)
        Brush.verticalGradient(listOf(c, c))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(0.78f), Color.White.copy(0.62f)))
    }
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
    // 3.85.3 照网页 .mini-vinyl 逐项复刻：投影（不转）＋盘体（转）＋封面＋中心孔（压在封面上）
    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(82, 20, 10, 10)
                    maskFilter = BlurMaskFilter(10.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                }
                drawContext.canvas.nativeCanvas.drawCircle(
                    center.x, center.y + 8.dp.toPx(), size.minDimension / 2, paint
                )
            }
        )
        Box(
            Modifier.fillMaxSize().graphicsLayer { rotationZ = spin },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                // 盘体：径向 #2a2a30 0% → #060608 78%（78% 后纯色到边）
                drawCircle(
                    Brush.radialGradient(
                        0f to Color(0xFF2A2A30), 0.78f to Color(0xFF060608), 1f to Color(0xFF060608),
                        center = center, radius = r
                    ),
                    radius = r
                )
                // 纹路：每 3dp 一条 1dp 亮线（白 .055），只在封面外
                var rr = r - 1.5.dp.toPx()
                val grooveW = 1.dp.toPx()
                val grooveStep = 3.dp.toPx()
                val coverR = 22.dp.toPx()
                while (rr > coverR) {
                    drawCircle(Color.White.copy(0.055f), radius = rr, style = Stroke(grooveW))
                    rr -= grooveStep
                }
                // 光扫：conic 软楔（from 210°，两楔峰值 .13/.08）→ 换算成 sweep 渐变
                drawCircle(
                    Brush.sweepGradient(
                        0.000f to Color.Transparent,
                        0.333f to Color.Transparent,
                        0.383f to Color.White.copy(0.13f),
                        0.450f to Color.Transparent,
                        0.875f to Color.Transparent,
                        0.939f to Color.White.copy(0.08f),
                        1.000f to Color.Transparent,
                        center = center
                    ),
                    radius = r
                )
                // 内圈描边：inset 0 0 0 1px 白 .05
                drawCircle(
                    Color.White.copy(0.05f), radius = r - 0.5.dp.toPx(),
                    style = Stroke(1.dp.toPx())
                )
            }
            // 封面 44px 圆＋暗环在封面外侧 1px（网页 box-shadow 0 0 0 1px rgba(0,0,0,.35)
            // 是画在封面之外的；3.85.8 前画在内侧，压住封面边缘成一圈灰，cheng 实测指出）
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxSize()
                        .border(1.dp, Color.Black.copy(0.35f), CircleShape)
                )
                Box(
                    Modifier.size(44.dp)
                        .background(Color(0xFF0A0A0C), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (bmp != null) {
                        Image(
                            remember(bmp) { circleCropBmp(bmp) }.asImageBitmap(), null,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.High
                        )
                    }
                }
            }
        }
        // 中心孔（3.85.5 加回，3.85.8 按实测修正）：网页 ::after 是伪元素、不吃全局
        // border-box，是 content-box——宽 7px 是黑心、1.5px 边在外，总径 10px、黑心 7px
        // （cheng 两图实测：网页黑心 Ø≈6.6 单位、总 Ø≈10 单位）
        // 3.85.9：总径 11（黑心 8）——cheng 逐行实测 App 黑心 Ø≈21.5px < 网页 ≈24px：
        // 原生边框＋抗锯齿对黑心的侵蚀比 CSS 大约 1.4px/边，按实测差额放大黑心补偿
        Box(
            Modifier.size(10.5.dp).background(Color(0xFF0B0B0D), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.fillMaxSize().border(1.25.dp, Color.White.copy(0.4f), CircleShape))
        }
    }
}

/** 3.85.4：黏性封面——新歌封面没加载完先顶着上一首的图，到了整帧换，
 * 不让 CoverBitmap 在切歌时露 #EEEEEE 白占位（cheng 报的切歌闪白） */
// 封面预裁成正圆（3.85.10）：Compose 的圆形 clipPath 在不少机器上不抗锯齿、
// 边缘有刺（cheng 指认）；用 AA 画笔把圆烘进位图本身，再以 High 过滤绘制，边缘才细腻。
// 网页浏览器对 border-radius 圆图是带 AA 合成的，这步即对齐它。
private val circleBmpCache = LruCache<Bitmap, Bitmap>(24)

private fun circleCropBmp(src: Bitmap): Bitmap {
    circleBmpCache.get(src)?.let { return it }
    val size = minOf(src.width, src.height)
    if (size <= 0) return src
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(out)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    cv.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    val l = (src.width - size) / 2
    val t = (src.height - size) / 2
    cv.drawBitmap(
        src,
        android.graphics.Rect(l, t, l + size, t + size),
        android.graphics.Rect(0, 0, size, size),
        p
    )
    circleBmpCache.put(src, out)
    return out
}

@Composable
private fun DockStickyCover(url: String): Bitmap? {
    val fresh = CoverBitmap(url)
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(fresh) { if (fresh != null) shown = fresh }
    return fresh ?: shown
}

@Composable
private fun DockCover(sizeDp: Float, spin: Float, bmp: Bitmap?, dark: Boolean) {
    Box(
        Modifier.size(sizeDp.dp).graphicsLayer { rotationZ = spin }
            .background(if (dark) Color(0xFF2C2C2E) else Color(0xFFEEEEEE), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                remember(bmp) { circleCropBmp(bmp) }.asImageBitmap(), null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High
            )
        }
    }
}

@Composable
private fun DockSideBtn(
    home: Boolean, sizeDp: Float, t: Float, on: Boolean, dark: Boolean,
    accent: Color, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(percent = 50)
    val col = if (on) accent
    else if (dark) Color(235, 235, 245).copy(0.68f)
    else Color(60, 60, 67).copy(0.55f)
    // 3.85.5 照网页 .dock-side：槽位宽 0→52 把迷你条推窄，圆钮本体固定 52、
    // scale .72→1＋整钮淡入（旧版圆从 0 长大，入场观感与网页不同）
    Box(
        Modifier.width(sizeDp.dp).height(52.dp).clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(52.dp)) {
            DockHalo(26f, 6f, 20f, (if (dark) 0.35f else 0.06f) * t)
        }
        Box(
            Modifier.size(52.dp)
                .graphicsLayer {
                    val sc = 0.72f + 0.28f * t
                    scaleX = sc; scaleY = sc; alpha = t
                }
                .dockGlass(shape, dark, shown = false, bg = NativeDockStore.backdrop == null)
                .clip(shape).dockPress(0.9f) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (NativeDockStore.backdrop != null) DockBackdropSlice(dockWash(false, dark))
            if (home) DockSvg(D_HOME, 24.dp, col) else DockSearchIcon(24.dp, col)
        }
    }
}

@Composable
private fun DockMiniBar(
    isLiuli: Boolean, isNetease: Boolean, isHeijiao: Boolean,
    dark: Boolean, accent: Color, lyricColor: Color, lyricText: String,
    miniH: Float, coverS: Float, nameSp: Float, nameWeight: FontWeight,
    nextW: Float, nextAlpha: Float, artistAlpha: Float, artistHFrac: Float,
    discOff: Float, discSpacerW: Float, detailT: Float,
    frac: Float, spin: Float, bmp: Bitmap?,
    shOff: Float, shBlur: Float, shAlpha: Float, shAlpha2: Float,
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
        DockHalo(if (isNetease) 18f else miniH / 2f, shOff, shBlur, shAlpha, shAlpha2)
        Box(
            Modifier.fillMaxSize()
                .dockGlass(shape, dark, shown = true, bg = st.backdrop == null)
                .clip(shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onOpenPlayer() }
        ) {
            if (st.backdrop != null) DockBackdropSlice(dockWash(true, dark))
            if (isLiuli && frac > 0f) {
                // 网页 .mini-fill：固定 --pink #fa2d48（不随主题色），渐变
                // 26/18/8/2 还要再乘 mask（72% 前全显、88% 处 .45、末端 0），
                // 旧版漏了 mask、前沿偏硬（3.85.17 补齐为等效止点）
                val pink = Color(0xFFFA2D48)
                val stops = if (dark) arrayOf(
                    0f to pink.copy(0.32f), 0.55f to pink.copy(0.20f),
                    0.72f to pink.copy(0.124f), 0.82f to pink.copy(0.052f),
                    0.88f to pink.copy(0.0225f), 0.94f to pink.copy(0.0045f),
                    1f to Color.Transparent
                ) else arrayOf(
                    0f to pink.copy(0.26f), 0.55f to pink.copy(0.18f),
                    0.72f to pink.copy(0.117f), 0.82f to pink.copy(0.052f),
                    0.88f to pink.copy(0.0225f), 0.94f to pink.copy(0.0045f),
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
                            Color(0xFFFA2D48), Color.White, 0.30f
                        ) else Color(0xFFFA2D48)
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
                    // 网页 .mini-sub 恒高 20px：收起时塌的只有歌手自己
                    // （.mini-artist max-height/opacity），歌词行常显——旧版把
                    // 歌词塞进歌手的塌高槽，收起时歌词被一起挤没（3.85.17 修）
                    Box(
                        Modifier.fillMaxWidth().height(20.dp).clipToBounds()
                    ) {
                        if (lyricText.isNotEmpty()) {
                            DockLyricLine(lyricText, lyricColor)
                        } else {
                            Box(
                                Modifier.fillMaxWidth()
                                    .height((20f * artistHFrac).dp).clipToBounds()
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().height(20.dp)
                                        .alpha(artistAlpha)
                                ) {
                                    Text(
                                        st.artist, color = artistColor,
                                        fontSize = 12.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        lineHeight = (12 * 1.35f).sp
                                    )
                                }
                            }
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
                    .wrapContentSize(unbounded = true)
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
    Box(Modifier.fillMaxWidth().height(tabH.dp).clipToBounds()) {
        val hasBd = NativeDockStore.backdrop != null
        val inner = if (isNetease) {
            // 网易风通栏：无整圈边框/阴影，只有顶边线；内容坐在上方 60dp
            if (hasBd) Modifier.fillMaxSize()
            else if (dark) Modifier.fillMaxSize().background(Color(0xD11C1C1E), shape)
            else Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(0.78f), Color.White.copy(0.62f))
                ),
                shape
            )
        } else {
            Modifier.fillMaxSize().dockGlass(shape, dark, shown = false, bg = !hasBd)
        }
        Box(inner.clip(shape)) {
            if (hasBd) DockBackdropSlice(dockWash(false, dark))
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
                    .fillMaxHeight()
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
    // 3.85.1：迷你歌词句自己算——脑的 lyricLine 是锁屏轮换通道（当前句↔下一句每 2.8s 轮换），
    // 直接显示会抢先；按网页 syncLyrics 规则（行时间 <= 位置+0.15s）从带时间歌词里取当前句
    val lyricLines = NativePlayerStore.lyrics
    val dockLyric = if (lyricLines.isEmpty()) "" else {
        val posL = st.livePosMs() + 150
        var txt = ""
        for (ln in lyricLines) {
            if (ln.t <= posL) txt = ln.x else break
        }
        txt.trim()
    }
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
    // —— 收起编排（3.85.12，沙盒逐帧实测网页真值后重写）——
    // 网页收起是「快收慢长」：导航栏 height:0 无过渡、同帧消失，迷你条同帧落底、
    // 先缩到 120 再随 flex .8s 长开，侧钮在两角 .75s 长出，封面 .28s 先缩，
    // 歌手行 .55s 塌高、下一首约 .45s 先走；展开反过来：导航栏 .8s 涨高把
    // 迷你条顶上去、迷你条瞬时满宽。旧版单 tween 780ms 全员同速（导航栏慢慢
    // 裁、迷你条缓缓沉），方式完全两样（cheng 2026-11-28 指认）。
    val colActive = st.collapsed && isLiuli && !st.detail
    val tabGrow = remember { Animatable(1f) }
    LaunchedEffect(colActive) {
        if (colActive) tabGrow.snapTo(0f)
        else tabGrow.animateTo(1f, tween(800, easing = DockEase))
    }
    val screenWDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val miniW = remember { Animatable(0f) }
    var colMode by remember { mutableStateOf(false) }
    LaunchedEffect(colActive) {
        if (colActive) {
            colMode = true
            miniW.snapTo(120f)
            miniW.animateTo(
                (screenWDp - 146f).coerceAtLeast(120f),
                tween(800, easing = DockEase)
            )
        } else colMode = false
    }
    val tCover by animateFloatAsState(
        if (colActive) 1f else 0f, tween(280, easing = EaseCss), label = "dkCov"
    )
    val tArtist by animateFloatAsState(
        if (colActive) 1f else 0f,
        if (colActive) tween(550, easing = EaseCss)
        else tween(600, easing = EaseCss),
        label = "dkArt"
    )
    val tArtistA by animateFloatAsState(
        if (colActive) 0f else 1f,
        if (colActive) tween(450, easing = EaseCss)
        else tween(550, easing = EaseCss, delayMillis = 100),
        label = "dkArtA"
    )
    val tNextW by animateFloatAsState(
        if (colActive) 0f else 1f,
        if (colActive) tween(550, easing = DockEase)
        else tween(600, easing = DockEase),
        label = "dkNxtW"
    )
    val tNextA by animateFloatAsState(
        if (colActive) 0f else 1f,
        if (colActive) tween(400, easing = EaseCss)
        else tween(500, easing = EaseCss, delayMillis = 150),
        label = "dkNxtA"
    )
    // 侧钮收起 .75s 长出（网页 .dock-side .75s）；展开在 cheng 真机上是同帧
    // 消失——类名一翻转钮就没了，宽高过渡没跑起来（2026-11-28 录屏逐帧实锤：
    // f150 两钮还在、f152 已无影；沙盒桌面 Chrome 会把过渡跑完，不作数）。
    val tSideA = remember { Animatable(0f) }
    LaunchedEffect(colActive) {
        if (colActive) tSideA.animateTo(1f, tween(750, easing = DockEase))
        else tSideA.snapTo(0f)
    }
    val tSide = tSideA.value
    val collapseT = tSide
    val detailT by animateFloatAsState(
        if (st.detail) 1f else 0f, tween(600, easing = DockEase), label = "dkDet"
    )
    val navInset = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding().value.coerceAtMost(10f)
    // —— 几何（网页 CSS 级联终局，单位 dp=CSS px）——
    val miniH by animateFloatAsState(
        when {
            !st.hasTrack -> 0f
            st.detail -> 58f
            colActive -> 52f
            else -> 56f
        },
        if (isLiuli) tween(800, easing = DockEase)
        else tween(600, easing = DockEase),
        label = "dkMiniH"
    )
    val coverS = when {
        isHeijiao -> 44f
        isNetease -> lerpF(44f, 40f, detailT)
        else -> lerpF(lerpF(44f, 36f, tCover), 40f, detailT)
    }
    val nameSp = if (isLiuli) lerpF(14f, 13f, tCover) else 14f
    val nameWeight = when {
        isNetease -> FontWeight(650)
        isHeijiao -> FontWeight.W600
        else -> FontWeight.W700
    }
    val nextW = lerpF(40f * tNextW, 40f, detailT)
    val nextAlpha = lerpF(tNextA, 1f, detailT)
    val artistAlpha = tArtistA
    val artistHFrac = 1f - tArtist
    val sideS = 52f * tSide * (1f - detailT)
    val tabBase = if (isNetease) 60f + navInset else 56f
    val tabVis = tabGrow.value * (1f - detailT)
    // 网页涨高走 max-height 0→64 的曲线、内容高 56 先饱和：高度/间距按 64 过冲
    // 折算（CSS 原值；cheng 真机录屏里迷你条被涨起的导航栏顶着匀速升到顶）
    val tabHFrac = min(1f, tabGrow.value * (64f / tabBase)) * (1f - detailT)
    val tabH = tabBase * tabHFrac
    val tabAlpha = tabVis
    val gapH = if (st.hasTrack) 4f * tabHFrac else 0f
    val miniStart = when {
        isHeijiao -> lerpF(32f, 16f, detailT)
        else -> 13f
    }
    val miniEnd = when {
        isHeijiao -> lerpF(59f, 13f, detailT)
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
    val tabScale = lerpF(0.88f, 1f, tabGrow.value)
    val tabTyDp = lerpF(18f, 0f, tabGrow.value)
    val lyricColor = if (st.dark) androidx.compose.ui.graphics.lerp(
        Color(0xFFFA2D48), Color.White, 0.35f
    ) else st.npAccent
    val bmp = DockStickyCover(st.cover)
    val dens = LocalDensity.current.density
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(bottom = bottomPad.dp)
                .onGloballyPositioned {
                    NativeDockStore.regionTopPx = it.positionInWindow().y.toInt()
                }
        ) {
            if (isLiuli) {
                // 琉璃光布局（3.85.12）：侧钮角锚、迷你条独立定位——网页 collapsed 是
                // flex 行＋space-between：侧钮钉两角、迷你条居中长开，互不挤压；
                // 展开时侧钮脱离流、迷你条满宽。旧版打包 Row 里迷你条被槽位推窄，
                // 宽度方向与网页相反（网页先缩 120 再长开，原生从满宽慢慢变窄）。
                Box(Modifier.fillMaxWidth().height(miniH.dp)) {
                    Box(
                        if (colMode) Modifier.width(miniW.value.dp)
                            .align(Alignment.Center)
                        else Modifier.fillMaxWidth().padding(horizontal = 13.dp)
                            .align(Alignment.Center)
                    ) {
                        if (miniH > 0.5f) {
                        // 阴影照网页：展开 0 10px 32px .10（网易风 30px/.12、深色 8px 28px .45），
                        // 收起 0 6px 20px .06（深色 .40），随 collapseT 插值
                        val shOff = if (st.dark) lerpF(8f, 6f, collapseT)
                            else lerpF(10f, 6f, collapseT)
                        val shBlur = if (st.dark) lerpF(28f, 20f, collapseT)
                            else lerpF(if (isNetease) 30f else 32f, 20f, collapseT)
                        val shAlpha = if (st.dark) lerpF(0.45f, 0.40f, collapseT)
                            else lerpF(if (isNetease) 0.12f else 0.10f, 0.06f, collapseT)
                        val shAlpha2 = if (st.dark) 0f else lerpF(0.05f, 0f, collapseT)
                        DockMiniBar(
                            isLiuli, isNetease, isHeijiao, st.dark, st.accent,
                            lyricColor, dockLyric, miniH, coverS, nameSp, nameWeight,
                            nextW, nextAlpha, artistAlpha, artistHFrac, discOff, discSpacerW,
                            detailT, frac, spin, bmp,
                            shOff, shBlur, shAlpha, shAlpha2,
                            onToggle, onNext, onOpenPlayer, onQueue
                        )
                        }
                    }
                    if (sideS > 0.5f) {
                        Box(
                            Modifier.align(Alignment.CenterStart)
                                .padding(start = 13.dp)
                        ) {
                            DockSideBtn(true, sideS, tSide, st.page == "home", st.dark, st.accent) {
                                onTab("home")
                            }
                        }
                        Box(
                            Modifier.align(Alignment.CenterEnd)
                                .padding(end = 13.dp)
                        ) {
                            DockSideBtn(false, sideS, tSide, st.page == "search", st.dark, st.accent) {
                                onTab("search")
                            }
                        }
                    }
                }
            } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (sideS > 0.5f) {
                    DockSideBtn(true, sideS, collapseT, st.page == "home", st.dark, st.accent) {
                        onTab("home")
                    }
                }
                Box(
                    Modifier.weight(1f)
                        .padding(
                            start = (miniStart - 13f - sideS).dp,
                            end = (miniEnd - 13f - sideS).dp
                        )
                ) {
                    if (miniH > 0.5f) {
                        // 阴影照网页：展开 0 10px 32px .10（网易风 30px/.12、深色 8px 28px .45），
                        // 收起 0 6px 20px .06（深色 .40），随 collapseT 插值
                        val shOff = if (st.dark) lerpF(8f, 6f, collapseT)
                            else lerpF(10f, 6f, collapseT)
                        val shBlur = if (st.dark) lerpF(28f, 20f, collapseT)
                            else lerpF(if (isNetease) 30f else 32f, 20f, collapseT)
                        val shAlpha = if (st.dark) lerpF(0.45f, 0.40f, collapseT)
                            else lerpF(if (isNetease) 0.12f else 0.10f, 0.06f, collapseT)
                        val shAlpha2 = if (st.dark) 0f else lerpF(0.05f, 0f, collapseT)
                        DockMiniBar(
                            isLiuli, isNetease, isHeijiao, st.dark, st.accent,
                            lyricColor, dockLyric, miniH, coverS, nameSp, nameWeight,
                            nextW, nextAlpha, artistAlpha, artistHFrac, discOff, discSpacerW,
                            detailT, frac, spin, bmp,
                            shOff, shBlur, shAlpha, shAlpha2,
                            onToggle, onNext, onOpenPlayer, onQueue
                        )
                    }
                }
                if (sideS > 0.5f) {
                    DockSideBtn(false, sideS, collapseT, st.page == "search", st.dark, st.accent) {
                        onTab("search")
                    }
                }
            }
            }
            if (gapH > 0.1f) Spacer(Modifier.height(gapH.dp))
            if (tabH > 0.5f) {
                Box(
                    Modifier.fillMaxWidth()
                        .padding(
                            start = tabStart.dp,
                            // 展开时导航栏宽度从左往右长开（cheng 真机录屏实锤：
                            // 初时是左锚的短条、四页签挤在里面，随涨高一起长满；
                            // 3.85.15 撤掉是助手错步，3.85.16 恢复）
                            end = (tabEnd + (screenWDp - tabStart - tabEnd) *
                                (1f - tabGrow.value)).dp
                        )
                        .alpha(tabAlpha)
                        .graphicsLayer {
                            scaleX = tabScale; scaleY = tabScale
                            translationY = tabTyDp * dens
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                        }
                ) {
                    if (!isNetease) {
                        // 阴影盒必须给死高 tabH：wrapper 是 wrap-content，
                        // fillMaxSize 子元素会把整列撑满屏（3.85.6 踩过，Dock 跑到屏顶）
                        Box(Modifier.fillMaxWidth().height(tabH.dp)) {
                            DockHalo(
                                28f, if (st.dark) 8f else 10f, 28f,
                                if (st.dark) 0.45f else 0.09f,
                                if (st.dark) 0f else 0.04f
                            )
                        }
                    }
                    DockTabBar(
                        isNetease, isHeijiao, st.dark, st.accent,
                        st.page, tabH, navInset, onTab
                    )
                }
            }
        }
    }
}
