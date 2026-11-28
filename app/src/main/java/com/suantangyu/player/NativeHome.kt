package com.suantangyu.player

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.roundToInt

// 3.86.0 原生主页：网页（1_184）把主页六区算好推快照，壳只渲染＋把点击
// 转发回网页隐藏 DOM 的真实 click（播歌上下文/开详情/换一批全走网页原逻辑）。
// 主页内容同时录入 GraphicsLayer，Dock 在主页上方时改画该层＋RenderEffect
// 模糊＝真毛玻璃第一步（其余页面仍走 WebView 截图糊）。

data class HomePlat(val id: String, val name: String, val tail: String, val on: Boolean)

data class HomeDuo(val show: Boolean, val title: String, val sub: String, val cover: String)
data class HomeCard(val cover: String, val name: String, val sub: String)
data class HomeChannel(val name: String, val mark: String, val g1: String, val g2: String)
data class HomeNsRow(val cover: String, val name: String, val sub: String)
data class HomeHot(
    val cover: String, val name: String, val sub: String, val dur: String,
    val src: String, val srcPlat: String, val fav: Boolean
)

object NativeHomeStore {
    var enabled by mutableStateOf(false)
    var rectLeftPx by mutableStateOf(0)
    var rectTopPx by mutableStateOf(0)
    var rectWidthPx by mutableStateOf(0)
    var rectHeightPx by mutableStateOf(0)
    var plat by mutableStateOf("qq")
    var dark by mutableStateOf(false)
    var duoPlat by mutableStateOf("")
    var duoGuess by mutableStateOf(HomeDuo(false, "", "", ""))
    var duoRadar by mutableStateOf(HomeDuo(false, "", "", ""))
    var duoAi by mutableStateOf(HomeDuo(false, "", "", ""))
    var rec by mutableStateOf<List<HomeCard>>(emptyList())
    var chView by mutableStateOf("channel")
    var channels by mutableStateOf<List<HomeChannel>>(emptyList())
    var playlists by mutableStateOf<List<HomeCard>>(emptyList())
    var nsMsg by mutableStateOf("")
    var nsCards by mutableStateOf<List<List<HomeNsRow>>>(emptyList())
    var hot by mutableStateOf<List<HomeHot>>(emptyList())
    var rankMsg by mutableStateOf("")
    var ranks by mutableStateOf<List<HomeCard>>(emptyList())
    var homeLayer by mutableStateOf<GraphicsLayer?>(null)
    // 3.86.2 ⑥：站源菜单数据（快照 plats/platRect）与原生菜单开关（壳本地状态）
    var plats by mutableStateOf<List<HomePlat>>(emptyList())
    var platRectL by mutableStateOf(0)
    var platRectT by mutableStateOf(0)
    var platRectW by mutableStateOf(0)
    var platRectH by mutableStateOf(0)
    var platMenuShown by mutableStateOf(false)
    // 3.86.2 ⑦：底部安全区（dp），MainActivity 随 insets 灌入，与注入网页的值同源
    var safeBottomDp by mutableStateOf(0)
    // 3.86.3：网页实算 --safe-b（快照 safeB，CSS px）——底距公式优先用它
    var safeBWeb by mutableStateOf(0)
    // 3.86.3 ②：停稳烘焙好的主页糊位图（Dock/站源菜单贴它，不再逐帧过模糊）
    var homeBlurBmp by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    // 快照代次：parse 成功即 +1，烘焙以它为触发键之一
    var dataVersion by mutableStateOf(0)
    // 3.86.3 ⑥：站源菜单锚点（tap 当场 rect，设备 px；-1=无，用快照 platRect 兜底）
    var menuAnchorL by mutableStateOf(-1)
    var menuAnchorT by mutableStateOf(-1)
    var menuAnchorW by mutableStateOf(0)
    var menuAnchorH by mutableStateOf(0)
    // 3.86.1：站源菜单开着时原生主页让位（菜单挂在网页 header 下、垂进主页区，
    // 不让位会被原生层盖住点不了）
    var platMenuOpen by mutableStateOf(false)

    fun visibleNow(): Boolean =
        enabled && NativeDockStore.page == "home" && !NativeDockStore.detail &&
            rectWidthPx > 0 && rectHeightPx > 0

    fun blurActive(): Boolean =
        visibleNow() && homeBlurBmp != null

    fun parse(json: String) {
        try {
            val o = JSONObject(json)
            val r = o.optJSONArray("rect")
            if (r != null && r.length() == 4) {
                rectLeftPx = r.optInt(0); rectTopPx = r.optInt(1)
                rectWidthPx = r.optInt(2); rectHeightPx = r.optInt(3)
            }
            plat = o.optString("plat", "qq")
            dark = o.optBoolean("dark")
            duoPlat = o.optString("duoPlat", "")
            platMenuOpen = o.optBoolean("platMenu", false)
            val plts = o.optJSONArray("plats")
            plats = List(plts?.length() ?: 0) { i ->
                val c = plts!!.optJSONObject(i) ?: JSONObject()
                HomePlat(
                    c.optString("id"), c.optString("name"),
                    c.optString("tail"), c.optBoolean("on")
                )
            }
            val prc = o.optJSONArray("platRect")
            if (prc != null && prc.length() == 4) {
                platRectL = prc.optInt(0); platRectT = prc.optInt(1)
                platRectW = prc.optInt(2); platRectH = prc.optInt(3)
            }
            safeBWeb = o.optDouble("safeB", 0.0).toInt()
            val duo = o.optJSONObject("duo")
            if (duo != null) {
                duoGuess = duoOf(duo.optJSONObject("guess"), duoGuess)
                duoRadar = duoOf(duo.optJSONObject("radar"), duoRadar)
                duoAi = duoOf(duo.optJSONObject("ai"), duoAi)
            }
            val recArr = o.optJSONArray("rec")
            rec = List(recArr?.length() ?: 0) { i ->
                val c = recArr!!.optJSONObject(i) ?: JSONObject()
                HomeCard(c.optString("cover"), c.optString("name"), c.optString("artist"))
            }
            chView = o.optString("chView", "channel")
            val chArr = o.optJSONArray("channels")
            channels = List(chArr?.length() ?: 0) { i ->
                val c = chArr!!.optJSONObject(i) ?: JSONObject()
                HomeChannel(
                    c.optString("name"), c.optString("mark"),
                    c.optString("g1"), c.optString("g2")
                )
            }
            val plArr = o.optJSONArray("playlists")
            playlists = List(plArr?.length() ?: 0) { i ->
                val c = plArr!!.optJSONObject(i) ?: JSONObject()
                HomeCard(c.optString("cover"), c.optString("name"), c.optString("src"))
            }
            nsMsg = o.optString("nsMsg", "")
            val nsArr = o.optJSONArray("nsCards")
            nsCards = List(nsArr?.length() ?: 0) { ci ->
                val card = nsArr!!.optJSONArray(ci)
                List(card?.length() ?: 0) { ri ->
                    val c = card!!.optJSONObject(ri) ?: JSONObject()
                    HomeNsRow(c.optString("cover"), c.optString("name"), c.optString("sub"))
                }
            }
            val hotArr = o.optJSONArray("hot")
            hot = List(hotArr?.length() ?: 0) { i ->
                val c = hotArr!!.optJSONObject(i) ?: JSONObject()
                HomeHot(
                    c.optString("cover"), c.optString("name"), c.optString("sub"),
                    c.optString("dur"), c.optString("src"), c.optString("srcPlat"),
                    c.optBoolean("fav")
                )
            }
            rankMsg = o.optString("rankMsg", "")
            val rkArr = o.optJSONArray("ranks")
            ranks = List(rkArr?.length() ?: 0) { i ->
                val c = rkArr!!.optJSONObject(i) ?: JSONObject()
                HomeCard(c.optString("cover"), c.optString("name"), "")
            }
            dataVersion++
        } catch (_: Exception) {
        }
    }

    private fun duoOf(o: JSONObject?, prev: HomeDuo): HomeDuo {
        if (o == null) return prev
        return HomeDuo(
            o.optBoolean("show", true), o.optString("title"),
            o.optString("sub"), o.optString("cover")
        )
    }
}

private class HomePal(dark: Boolean) {
    val bg = if (dark) Color(0xFF000000) else Color(0xFFF3F3F6)
    val surface = if (dark) Color(0xFF1C1C1E) else Color(0xFFFAFAFC)
    val surface2 = if (dark) Color(0xFF2C2C2E) else Color(0xFFEEEEEF)
    val label = if (dark) Color(0xFFF5F5F7) else Color(0xFF1C1C1E)
    val label2 = if (dark) Color(0x99EBEBF5) else Color(0xB83C3C43)
    val label3 = if (dark) Color(0x73EBEBF5) else Color(0x8C3C3C43)
    val separator = if (dark) Color(0x26FFFFFF) else Color(0x2E3C3C43)
    val nsBg = if (dark) Color(0x1A767680) else Color(0x8CFFFFFF)
    val nsBorder = if (dark) Color(0x24767680) else Color(0x14502814)
}

private fun hexColor(hex: String, fallback: Color): Color {
    if (hex.isEmpty()) return fallback
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        fallback
    }
}

private val DUO_ACCENTS = mapOf(
    "qq" to listOf(0xFF31C27C, 0xFF0A84FF, 0xFFA855F7),
    "netease" to listOf(0xFFEC4141, 0xFF0A84FF, 0xFFFF9F0A),
    "kuwo" to listOf(0xFFFF6A00, 0xFF0A84FF, 0xFFA855F7),
    "kugou" to listOf(0xFF2CA2F9, 0xFF30D158, 0xFFA855F7),
    "migu" to listOf(0xFFED4141, 0xFF0A84FF, 0xFFFF9F0A)
)
private val SRC_COLORS = mapOf(
    "qq" to Color(0xFF31C27C), "netease" to Color(0xFFEC4141),
    "kuwo" to Color(0xFFFF6A00), "kugou" to Color(0xFF2CA2F9),
    "migu" to Color(0xFFED4141)
)
private const val HEART_PATH =
    "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"

@Composable
private fun Modifier.homePress(scaleTo: Float = 0.97f, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) scaleTo else 1f, label = "homePress")
    return this
        .graphicsLayer { scaleX = sc; scaleY = sc }
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

@Composable
private fun HomeCover(url: String, modifier: Modifier, radius: androidx.compose.ui.unit.Dp, bg: Color) {
    val bmp = CoverBitmap(url)
    Box(modifier.clip(RoundedCornerShape(radius)).background(bg)) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun NativeHomeRoot(onAction: (String, String) -> Unit) {
    val st = NativeHomeStore
    if (!st.enabled) return
    val vis = st.visibleNow()
    val pal = remember(st.dark) { HomePal(st.dark) }
    val density = LocalDensity.current
    val wDp = with(density) { st.rectWidthPx.toDp() }
    val hDp = with(density) { st.rectHeightPx.toDp() }
    val xDp = with(density) { st.rectLeftPx.toDp() }
    val yDp = with(density) { st.rectTopPx.toDp() }
    val layer = rememberGraphicsLayer()
    // 3.86.3 ②：图层只录原图，模糊＋饱和在停稳烘焙时一次算进位图（见 bakeHomeBlur）
    SideEffect { st.homeLayer = layer }
    val listState = rememberLazyListState()
    // 3.86.2 ②：滚动中停录图层（每帧重录是滑动掉帧主因），停稳补录一次
    var homeScrolling by remember { mutableStateOf(false) }
    var recordTick by remember { mutableStateOf(0) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { sc ->
                homeScrolling = sc
                if (!sc) recordTick++
            }
    }
    // 3.86.3 ② 烘焙：停稳/数据变/页面出现后 90ms，把图层算成糊位图存好
    LaunchedEffect(recordTick, st.dataVersion, vis) {
        if (!vis) return@LaunchedEffect
        delay(90)
        if (!homeScrolling && st.visibleNow()) {
            try {
                val raw = layer.toImageBitmap()
                val baked = withContext(Dispatchers.Default) { bakeHomeBlur(raw) }
                if (baked != null) st.homeBlurBmp = baked
            } catch (_: Exception) {
            }
        }
    }
    // 原生主页滚动驱动 Dock 收起（网页主页没在滚、它的收起值已过期）：
    // 下滚收起、上滚展开、回顶必展开
    LaunchedEffect(listState) {
        var lastTotal = -1
        snapshotFlow {
            listState.firstVisibleItemIndex * 100000 + listState.firstVisibleItemScrollOffset
        }.collect { total ->
            if (NativeHomeStore.visibleNow()) {
                if (listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset <= 4
                ) {
                    NativeDockStore.collapsed = false
                } else if (lastTotal >= 0) {
                    val d = total - lastTotal
                    if (d > 28) NativeDockStore.collapsed = true
                    else if (d < -28) NativeDockStore.collapsed = false
                }
            }
            lastTotal = total
        }
    }
    LaunchedEffect(vis) {
        if (vis && listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset <= 4
        ) {
            NativeDockStore.collapsed = false
        }
    }
    Box(
        Modifier
            .offset {
                if (vis) IntOffset(st.rectLeftPx, st.rectTopPx)
                else IntOffset(12000, st.rectTopPx)
            }
            .size(wDp, hDp)
            .clipToBounds()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    val tick = recordTick
                    if (vis && !homeScrolling && tick >= 0) {
                        try {
                            val cds = this
                            // 3.86.3：录两条——底部 240dp 供 Dock、顶部 360dp
                            // 供站源菜单的玻璃底；中间不录，省光栅
                            val strip = 240.dp.toPx()
                            val topStrip = 360.dp.toPx()
                            layer.record {
                                clipRect(0f, 0f, size.width, topStrip) {
                                    with(cds) { drawContent() }
                                }
                                clipRect(
                                    0f, size.height - strip, size.width, size.height
                                ) {
                                    with(cds) { drawContent() }
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                    drawContent()
                }
                .background(pal.bg)
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                    bottom = (130 + if (st.safeBWeb > 0) st.safeBWeb else st.safeBottomDp).dp)
            ) {
                // —— 双卡 ——
                item(key = "duo") {
                    val accents = DUO_ACCENTS[st.duoPlat.ifEmpty { st.plat }]
                        ?: DUO_ACCENTS["qq"]!!
                    val cards = mutableListOf<Triple<HomeDuo, String, Int>>()
                    cards.add(Triple(st.duoGuess, "猜你喜欢", 0))
                    cards.add(Triple(st.duoRadar, "听歌雷达", 1))
                    if (st.duoAi.show) cards.add(Triple(st.duoAi, "智能推荐", 2))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
                    ) {
                        cards.forEach { (duo, kicker, ai) ->
                            DuoCard(
                                duo, kicker, Color(accents[ai]), pal,
                                Modifier.weight(1f)
                            ) {
                                onAction(
                                    "duo",
                                    when (ai) {
                                        1 -> "radar"; 2 -> "ai"; else -> "guess"
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                }
                // —— 为你推荐 ——
                item(key = "recHead") {
                    SecHead("为你推荐", "换一批", pal) { onAction("recRefresh", "") }
                }
                item(key = "recRow") {
                    if (st.rec.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 4.dp)
                        ) {
                            itemsIndexed(st.rec, key = { i, c -> "rec$i${c.name}" }) { i, c ->
                                Column(
                                    Modifier
                                        .width(164.dp)
                                        .homePress { onAction("rec", i.toString()) }
                                ) {
                                    HomeCover(
                                        c.cover, Modifier.size(164.dp), 10.dp, pal.surface2
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    androidx.compose.material3.Text(
                                        c.name, color = pal.label, fontSize = 13.sp,
                                        fontWeight = FontWeight(500), maxLines = 1,
                                        lineHeight = 19.sp,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (c.sub.isNotEmpty()) {
                                        androidx.compose.material3.Text(
                                            c.sub, color = pal.label2, fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
                // —— 频道/歌单 ——
                item(key = "chHead") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                    ) {
                        androidx.compose.material3.Text(
                            if (st.chView == "playlist") "热门歌单" else "频道",
                            color = pal.label, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.66).sp, lineHeight = 30.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Text(
                                "换一批", color = NativeDockStore.accent, fontSize = 17.sp,
                                fontWeight = FontWeight(600),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onAction("chRefresh", "") }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(120, 120, 128).copy(alpha = 0.12f))
                                    .padding(2.dp)
                            ) {
                                ChTab("频道", st.chView == "channel", pal) {
                                    onAction("chTab", "channel")
                                }
                                ChTab("歌单", st.chView == "playlist", pal) {
                                    onAction("chTab", "playlist")
                                }
                            }
                        }
                    }
                }
                item(key = "chGrid") {
                    if (st.chView == "playlist") {
                        Grid3(st.playlists.size) { i ->
                            val c = st.playlists[i]
                            Column(
                                Modifier.homePress {
                                    onAction("playlist", i.toString())
                                }
                            ) {
                                HomeCover(
                                    c.cover,
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                    12.dp, pal.surface2
                                )
                                Spacer(Modifier.height(6.dp))
                                androidx.compose.material3.Text(
                                    c.name, color = pal.label, fontSize = 12.sp,
                                    fontWeight = FontWeight(600), maxLines = 2,
                                    lineHeight = 15.6.sp, overflow = TextOverflow.Ellipsis
                                )
                                if (c.sub.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    androidx.compose.material3.Text(
                                        c.sub, color = pal.label3, fontSize = 10.sp,
                                        lineHeight = 14.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else {
                        Grid3(st.channels.size) { i ->
                            val ch = st.channels[i]
                            Column(
                                Modifier.homePress {
                                    onAction("channel", i.toString())
                                }
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    hexColor(ch.g1, pal.surface2),
                                                    hexColor(ch.g2, pal.surface2)
                                                )
                                            )
                                        )
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxSize(0.48f)
                                            .align(Alignment.TopStart)
                                            .offset(x = (-14).dp, y = (-16).dp)
                                            .border(
                                                1.5.dp, Color.White.copy(alpha = 0.12f),
                                                CircleShape
                                            )
                                    )
                                    Box(
                                        Modifier
                                            .fillMaxSize(0.7f)
                                            .align(Alignment.BottomEnd)
                                            .offset(x = 20.dp, y = 24.dp)
                                            .border(
                                                1.5.dp, Color.White.copy(alpha = 0.18f),
                                                CircleShape
                                            )
                                    )
                                    Column(
                                        Modifier.align(Alignment.Center),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        androidx.compose.material3.Text(
                                            ch.mark, color = Color.White, fontSize = 22.sp,
                                            fontWeight = FontWeight(800),
                                            letterSpacing = (-0.44).sp
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        androidx.compose.material3.Text(
                                            "CHANNEL", color = Color.White.copy(alpha = 0.72f),
                                            fontSize = 10.sp, fontWeight = FontWeight(600),
                                            letterSpacing = 1.2.sp
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                androidx.compose.material3.Text(
                                    ch.name, color = pal.label, fontSize = 12.sp,
                                    fontWeight = FontWeight(600), maxLines = 1,
                                    lineHeight = 17.sp,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    // 网页边距折叠:max(频道区 mb 28, 新歌区 mt 30) = 30
                    Spacer(Modifier.height(30.dp))
                }
                // —— 新歌首发 ——
                item(key = "nsHead") {
                    androidx.compose.material3.Text(
                        "新歌首发", color = pal.label, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-0.66).sp,
                        lineHeight = 30.sp,
                        modifier = Modifier.padding(bottom = 14.dp)
                    )
                }
                item(key = "nsGrid") {
                    if (st.nsMsg.isNotEmpty() && st.nsCards.isEmpty()) {
                        androidx.compose.material3.Text(
                            st.nsMsg, color = pal.label3, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else if (st.nsCards.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                        ) {
                            var base = 0
                            st.nsCards.forEach { rows ->
                                val myBase = base
                                base += rows.size
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(pal.nsBg)
                                        .border(1.dp, pal.nsBorder, RoundedCornerShape(14.dp))
                                ) {
                                    rows.forEachIndexed { ri, row ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    onAction("ns", (myBase + ri).toString())
                                                }
                                                .padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            HomeCover(
                                                row.cover, Modifier.size(52.dp), 8.dp,
                                                pal.surface
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                NsNameText(row.name, pal.label)
                                                Spacer(Modifier.height(4.dp))
                                                androidx.compose.material3.Text(
                                                    row.sub, color = pal.label2,
                                                    fontSize = 12.sp, maxLines = 1,
                                                    lineHeight = 15.sp,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (st.nsCards.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
                // —— 热门歌曲 ——
                item(key = "hotHead") {
                    SecHead("热门歌曲", null, pal, null)
                }
                item(key = "hotList") {
                    Column {
                        st.hot.forEachIndexed { i, s ->
                            HotRow(s, pal, i == st.hot.lastIndex,
                                onClick = { onAction("hot", i.toString()) },
                                onLike = { onAction("hotLike", i.toString()) })
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
                // —— 精品榜单 ——
                item(key = "rankHead") {
                    SecHead("精品榜单", "换一批", pal) { onAction("rankRefresh", "") }
                }
                item(key = "rankGrid") {
                    if (st.rankMsg.isNotEmpty() && st.ranks.isEmpty()) {
                        androidx.compose.material3.Text(
                            st.rankMsg, color = pal.label3, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        Box(Modifier.padding(horizontal = 2.dp)) {
                            Grid3(st.ranks.size) { i ->
                                val c = st.ranks[i]
                                Column(
                                    Modifier.homePress {
                                        onAction("rank", i.toString())
                                    }
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                    ) {
                                        HomeCover(
                                            c.cover, Modifier.fillMaxSize(), 14.dp,
                                            pal.surface2
                                        )
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(
                                                    Brush.verticalGradient(
                                                        0.58f to Color.Transparent,
                                                        1f to Color.Black.copy(alpha = 0.18f)
                                                    )
                                                )
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    androidx.compose.material3.Text(
                                        c.name, color = pal.label, fontSize = 12.sp,
                                        fontWeight = FontWeight(600), maxLines = 1,
                                        lineHeight = 17.sp,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Grid3(count: Int, cell: @Composable (Int) -> Unit) {
    Column {
        var i = 0
        while (i < count) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                for (k in 0 until 3) {
                    Box(Modifier.weight(1f)) {
                        if (i + k < count) cell(i + k)
                    }
                }
            }
            if (i + 3 < count) Spacer(Modifier.height(12.dp))
            i += 3
        }
    }
}

// 3.86.1 新歌名跑马灯：照网页 newSongMarquee——溢出才滚、双份复制无缝循环、
// 约 30px/秒、时长 max(5s, (文宽+60)/30)
@Composable
private fun NsNameText(name: String, color: Color) {
    val style = TextStyle(color = color, fontSize = 14.sp, lineHeight = 17.5.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds()) {
        val boxW = constraints.maxWidth
        val textW = remember(name, boxW) {
            try {
                measurer.measure(name, style).size.width
            } catch (e: Exception) {
                0
            }
        }
        if (textW <= boxW || textW == 0) {
            androidx.compose.material3.Text(
                name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        } else {
            val cyclePx = textW + with(density) { 30.dp.toPx() }
            val durMs = maxOf(
                5000,
                ((textW + with(density) { 60.dp.toPx() }) /
                    with(density) { 30.dp.toPx() } * 1000).toInt()
            )
            val anim = remember(name) { Animatable(0f) }
            LaunchedEffect(name, textW) {
                while (true) {
                    anim.snapTo(0f)
                    anim.animateTo(-cyclePx, tween(durMs, easing = LinearEasing))
                }
            }
            Row(
                Modifier
                    .wrapContentWidth(unbounded = true)
                    .offset { IntOffset(anim.value.roundToInt(), 0) }
            ) {
                val wDp = with(density) { textW.toDp() }
                androidx.compose.material3.Text(
                    name, style = style, maxLines = 1, modifier = Modifier.width(wDp)
                )
                Spacer(Modifier.width(30.dp))
                androidx.compose.material3.Text(
                    name, style = style, maxLines = 1, modifier = Modifier.width(wDp)
                )
                Spacer(Modifier.width(30.dp))
            }
        }
    }
}

@Composable
private fun ChTab(text: String, on: Boolean, pal: HomePal, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (on) {
                    if (pal.bg == Color.Black) Color(0xFF5A5A5E) else Color.White
                } else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        androidx.compose.material3.Text(
            text, fontSize = 13.sp, fontWeight = FontWeight(600),
            lineHeight = 19.sp,
            color = if (on) pal.label else pal.label3
        )
    }
}

@Composable
private fun SecHead(
    title: String, linkText: String?, pal: HomePal, onLink: (() -> Unit)?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        androidx.compose.material3.Text(
            title, color = pal.label, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.66).sp, lineHeight = 30.sp
        )
        if (linkText != null && onLink != null) {
            androidx.compose.material3.Text(
                linkText, color = NativeDockStore.accent, fontSize = 17.sp,
                fontWeight = FontWeight(600),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onLink)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun DuoCard(
    duo: HomeDuo, kicker: String, barColor: Color, pal: HomePal,
    modifier: Modifier, onClick: () -> Unit
) {
    Box(
        modifier
            .height(176.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (pal.bg == Color.Black) Color(0xFF2C2C2E) else Color(0xFFE8E8ED))
            .homePress { onClick() }
    ) {
        HomeCover(duo.cover, Modifier.fillMaxSize(), 0.dp, Color.Transparent)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.34f)
                    )
                )
        )
        Row(
            Modifier
                .padding(start = 12.dp, top = 12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.22f))
                .border(0.5.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(999.dp))
                .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 11.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
            Spacer(Modifier.width(6.dp))
            androidx.compose.material3.Text(
                kicker, color = Color.White.copy(alpha = 0.98f), fontSize = 11.sp,
                fontWeight = FontWeight(680)
            )
        }
        androidx.compose.material3.Text(
            duo.title, color = Color.White,
            style = TextStyle(
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 2f), 6f),
                lineHeight = 19.5.sp
            ),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, end = 14.dp, bottom = 30.dp)
        )
        androidx.compose.material3.Text(
            duo.sub, color = Color.White.copy(alpha = 0.84f),
            style = TextStyle(
                fontSize = 12.sp, fontWeight = FontWeight(500),
                shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 5f)
            ),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
        )
    }
}

@Composable
private fun HotRow(
    s: HomeHot, pal: HomePal, isLast: Boolean,
    onClick: () -> Unit, onLike: () -> Unit
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeCover(s.cover, Modifier.size(56.dp), 8.dp, pal.surface)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(
                    s.name, color = pal.label, fontSize = 16.sp, maxLines = 1,
                    lineHeight = 20.8.sp,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (s.src.isNotEmpty()) {
                        val sc = SRC_COLORS[s.srcPlat] ?: NativeDockStore.accent
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(sc.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            androidx.compose.material3.Text(
                                s.src, color = sc, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    androidx.compose.material3.Text(
                        s.sub, color = pal.label2, fontSize = 13.sp, maxLines = 1,
                        lineHeight = 18.sp,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (s.dur.isNotEmpty()) {
                androidx.compose.material3.Text(
                    s.dur, color = pal.label3, fontSize = 12.sp,
                    textAlign = TextAlign.Right,
                    modifier = Modifier
                        .width(36.dp)
                        .padding(horizontal = 2.dp)
                )
            }
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onLike),
                contentAlignment = Alignment.Center
            ) {
                val heartPath = remember { PathParser().parsePathString(HEART_PATH).toPath() }
                Canvas(Modifier.size(20.dp)) {
                    val scale = size.minDimension / 24f
                    val path = Path().apply {
                        addPath(heartPath)
                        transform(androidx.compose.ui.graphics.Matrix().apply {
                            scale(scale, scale, 1f)
                        })
                    }
                    if (s.fav) {
                        drawPath(path, color = Color(0xFFFA2D48))
                    } else {
                        drawPath(path, color = pal.label3, style = Stroke(width = 2.15f * scale))
                    }
                }
            }
        }
        if (!isLast) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(pal.separator)
            )
        }
    }
}

/**
 * 3.86.2 ⑥ 原生站源菜单：照网页 .plat-menu 样式（白玻璃卡、圆角 14、内距 6，
 * 行：站名 14/500 左、源数 11 号 label3 右；当前站粉字 700＋8% 粉底、行圆角 10）。
 * 锚点＝网页胶囊 rect（快照 platRect，设备 px），菜单在其下 6dp、右缘与胶囊齐。
 */
@Composable
fun HomePlatMenu(onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val st = NativeHomeStore
    val pal = remember(st.dark) { HomePal(st.dark) }
    val density = LocalDensity.current
    // 3.86.3 ⑥：锚点优先用 tap 当场 rect（桥带回），快照 platRect 只兜底
    val aL = if (st.menuAnchorW > 0) st.menuAnchorL else st.platRectL
    val aT = if (st.menuAnchorW > 0) st.menuAnchorT else st.platRectT
    val aW = if (st.menuAnchorW > 0) st.menuAnchorW else st.platRectW
    val aH = if (st.menuAnchorW > 0) st.menuAnchorH else st.platRectH
    // 右内距 = 屏宽(px) − 胶囊右缘(px)，换回 dp
    val screenWPx = with(density) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val endDp = with(density) {
        (screenWPx - (aL + aW)).toDp()
    }
    val topDp = with(density) { (aT + aH).toDp() } + 6.dp
    if (st.plats.isEmpty() || aW <= 0) return
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = topDp, end = endDp)
                .width(160.dp)
                .shadow(16.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
        ) {
            // 3.86.3 ⑥ 玻璃底：主页糊位图按窗口坐标对位＋网页同值 0.72 罩
            var cardPos by remember { mutableStateOf(Offset.Zero) }
            Box(
                Modifier
                    .matchParentSize()
                    .onGloballyPositioned { cardPos = it.positionInWindow() }
                    .drawBehind {
                        val bmp = st.homeBlurBmp
                        if (bmp != null) {
                            drawImage(
                                bmp,
                                dstOffset = IntOffset(
                                    (st.rectLeftPx - cardPos.x).roundToInt(),
                                    (st.rectTopPx - cardPos.y).roundToInt()
                                ),
                                dstSize = IntSize(st.rectWidthPx, st.rectHeightPx)
                            )
                        }
                        drawRect(
                            if (st.dark) Color(0xB82C2C2E) else Color(0xB8FFFFFF)
                        )
                    }
            )
            Column(Modifier.padding(6.dp)) {
            st.plats.forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (p.on) Color(0x14FA2D48) else Color.Transparent
                        )
                        .clickable { onSelect(p.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                ) {
                    androidx.compose.material3.Text(
                        p.name,
                        color = if (p.on) Color(0xFFFA2D48) else pal.label,
                        fontSize = 14.sp,
                        fontWeight = if (p.on) FontWeight(700) else FontWeight(500)
                    )
                    androidx.compose.material3.Text(
                        p.tail, color = pal.label3, fontSize = 11.sp
                    )
                }
            }
            }
        }
    }
}

/**
 * 3.86.3 ② 烘焙：图层原图 → 0.5 缩放 → 滑动窗盒式模糊横竖各三遍（近似高斯）
 * → 饱和 1.85。算法与 MainActivity.blurAndSaturate 同款；在后台线程跑，
 * 只在停稳/数据变时算一次，滚动期间 Dock 只贴这张成品图。
 */
private fun bakeHomeBlur(
    src: androidx.compose.ui.graphics.ImageBitmap
): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val sb = src.asAndroidBitmap()
        val w = (sb.width / 2).coerceAtLeast(1)
        val h = (sb.height / 2).coerceAtLeast(1)
        val small = android.graphics.Bitmap.createScaledBitmap(sb, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        val radius = 16
        val n = radius * 2 + 1
        fun passH() {
            for (y in 0 until h) {
                val row = y * w
                var sr = 0; var sg = 0; var sb2 = 0; var sa = 0
                for (k in -radius..radius) {
                    val c = px[row + k.coerceIn(0, w - 1)]
                    sa += android.graphics.Color.alpha(c); sr += android.graphics.Color.red(c)
                    sg += android.graphics.Color.green(c); sb2 += android.graphics.Color.blue(c)
                }
                for (x in 0 until w) {
                    tmp[row + x] = android.graphics.Color.argb(sa / n, sr / n, sg / n, sb2 / n)
                    val co = px[row + (x - radius).coerceIn(0, w - 1)]
                    val ci = px[row + (x + radius + 1).coerceIn(0, w - 1)]
                    sa += android.graphics.Color.alpha(ci) - android.graphics.Color.alpha(co)
                    sr += android.graphics.Color.red(ci) - android.graphics.Color.red(co)
                    sg += android.graphics.Color.green(ci) - android.graphics.Color.green(co)
                    sb2 += android.graphics.Color.blue(ci) - android.graphics.Color.blue(co)
                }
            }
            System.arraycopy(tmp, 0, px, 0, px.size)
        }
        fun passV() {
            for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb2 = 0; var sa = 0
                for (k in -radius..radius) {
                    val c = px[k.coerceIn(0, h - 1) * w + x]
                    sa += android.graphics.Color.alpha(c); sr += android.graphics.Color.red(c)
                    sg += android.graphics.Color.green(c); sb2 += android.graphics.Color.blue(c)
                }
                for (y in 0 until h) {
                    tmp[y * w + x] = android.graphics.Color.argb(sa / n, sr / n, sg / n, sb2 / n)
                    val co = px[(y - radius).coerceIn(0, h - 1) * w + x]
                    val ci = px[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                    sa += android.graphics.Color.alpha(ci) - android.graphics.Color.alpha(co)
                    sr += android.graphics.Color.red(ci) - android.graphics.Color.red(co)
                    sg += android.graphics.Color.green(ci) - android.graphics.Color.green(co)
                    sb2 += android.graphics.Color.blue(ci) - android.graphics.Color.blue(co)
                }
            }
            System.arraycopy(tmp, 0, px, 0, px.size)
        }
        passH(); passV(); passH(); passV(); passH(); passV()
        val blurred = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888
        )
        blurred.setPixels(px, 0, w, 0, 0, w, h)
        val cm = android.graphics.ColorMatrix()
        cm.setSaturation(1.85f)
        val out = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888
        )
        val c2 = android.graphics.Canvas(out)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        }
        c2.drawBitmap(blurred, 0f, 0f, paint)
        out.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
