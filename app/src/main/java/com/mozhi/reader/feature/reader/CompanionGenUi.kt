package com.mozhi.reader.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mozhi.reader.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ai.agent.ChartSpecCodec
import com.mozhi.reader.ai.genui.GenUiFallback
import com.mozhi.reader.ai.genui.GenUiNode
import com.mozhi.reader.ai.genui.GenUiStyleParser
import com.mozhi.reader.ai.genui.runtime.GenUiMessageView
import com.mozhi.reader.ai.genui.runtime.GenUiSession
import com.mozhi.reader.core.database.entity.PersonaChatAppearance
import com.mozhi.reader.core.datastore.CompanionGenUiSettings
import com.mozhi.reader.ui.components.ChatBubbleStyle
import com.mozhi.reader.ui.genui.GenUiColors
import com.mozhi.reader.ui.genui.GenUiEvents
import com.mozhi.reader.ui.genui.GenUiHost
import com.mozhi.reader.ui.genui.GenUiIsland
import com.mozhi.reader.ui.components.ChatTextStyling
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 聊天页里互动回答所需的一切：会话、设置、原文核对与跳转。页面在列表外层提供，
 * 气泡里的互动岛按需取用；没有提供时（如预览）岛显示为纯文字投影。
 */
internal class CompanionGenUiController(
    val session: GenUiSession,
    val settings: CompanionGenUiSettings,
    val verifyQuote: suspend (chapterNumber: Int?, quote: String) -> LocatedCompanionCitation?,
    val onLocate: (LocatedCompanionCitation) -> Unit,
    /** `asset:` / `gen:` 图片引用 → 本地文件；不认识返回 null（不加载任意网址）。 */
    val resolveImage: (String) -> Any? = { null }
)

internal val LocalCompanionGenUi = staticCompositionLocalOf<CompanionGenUiController?> { null }

/** 由阅读纸色派生互动卡片配色：卡片底、分隔线随纸色明暗取值，语气色日夜两套。 */
internal fun ReaderPalette.genUiColors(surface: Color, nightAdapt: Boolean): GenUiColors {
    val tones = GenUiColors.toneSet(isDark)
    val paper = if (surface.alpha < 1f) surface.compositeOver(background) else surface
    return GenUiColors(
        ink = onBackground,
        paper = paper,
        muted = muted,
        card = onBackground.copy(alpha = if (isDark) 0.06f else 0.035f).compositeOver(paper),
        line = onBackground.copy(alpha = if (isDark) 0.16f else 0.12f),
        accent = accent,
        onAccent = onAccent,
        seal = if (isDark) Color(0xFFE08A80) else Color(0xFFB0443A),
        success = tones[0],
        warning = tones[1],
        danger = tones[2],
        info = tones[3],
        dark = isDark,
        nightAdapt = nightAdapt
    )
}

/**
 * 气泡流里的一个互动岛。外壳随角色聊天外观：气泡底色 + 卡片圆角，宽度用满消息列；
 * 岛的根元素自带背景（模型自己设计了卡片）时外壳退为透明，只保留裁切。
 *
 * 首次求值完成前保持最小高度不可见，就绪后一次性淡入——不先画默认值再跳变。
 */
@Composable
internal fun CompanionGenUiWidget(
    entry: ChatEntry.Bubble,
    palette: ReaderPalette,
    style: ChatBubbleStyle,
    appearance: PersonaChatAppearance,
    fontFamily: FontFamily?,
    onLongPress: () -> Unit
) {
    val part = entry.part as? CompanionBubblePart.Widget ?: return
    val controller = LocalCompanionGenUi.current
    val key = entry.widgetMessageKey
    val source = entry.widgetSource
    if (controller == null || key == null || source == null) {
        WidgetFallback(part.text, palette, style)
        return
    }
    val restored = remember(entry.message?.id) {
        entry.message?.uiStateJson?.let { raw ->
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        }
    }
    LaunchedEffect(key, source, entry.widgetComplete) {
        controller.session.submit(key, source, entry.widgetComplete, restored)
    }
    val view by remember(key) { controller.session.view(key) }.collectAsState()
    val island = view.islands.getOrNull(part.islandIndex)
    if (view.status == GenUiMessageView.Status.FALLBACK) {
        val text = island?.let(GenUiFallback::projectTree)?.takeIf { it.isNotBlank() } ?: part.text
        WidgetFallback(text, palette, style)
        return
    }
    val reveal = remember(key, part.islandIndex) { Animatable(if (island != null) 1f else 0f) }
    LaunchedEffect(island != null) {
        if (island != null && reveal.value < 1f) reveal.animateTo(1f, tween(WIDGET_REVEAL_MS))
    }
    val events = remember(key) { GenUiEvents { handler, args -> controller.session.dispatch(key, handler, args) } }
    val ownSurface = remember(island) {
        island?.flatChildren?.singleOrNull { !it.isText }?.let { root ->
            root.type == "card" || GenUiStyleParser.parse(root.prop("style")).hasOwnSurface
        } ?: false
    }
    // 默认外观下角色的话直接排在纸上，互动岛也不再套一张卡：与正文同一栏、同一张纸，
    // 只有模型自己设计了卡片（根元素自带底色）时才保留它的卡面。
    val shellColor = when {
        ownSurface || style.inline -> Color.Transparent
        else -> style.container.takeIf { it.alpha > 0f } ?: palette.glass
    }
    val colors = remember(palette, shellColor, controller.settings.nightAdapt) {
        palette.genUiColors(if (shellColor.alpha > 0f) shellColor else palette.background, controller.settings.nightAdapt)
    }
    val host = rememberCompanionGenUiHost(palette, controller)
    val borderless = ownSurface || style.inline
    Surface(
        color = shellColor,
        contentColor = palette.onBackground,
        border = if (borderless) null else style.border ?: BorderStroke(1.dp, palette.glassBorder),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (island == null) WIDGET_PENDING_HEIGHT else 0.dp)
            .graphicsLayer { alpha = reveal.value }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
    ) {
        if (island != null) {
            ChatTextStyling(appearance, fontFamily) {
                CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(color = palette.onBackground)) {
                    GenUiIsland(
                        island = island,
                        colors = colors,
                        events = events,
                        host = host,
                        modifier = Modifier.padding(
                            horizontal = if (borderless) 0.dp else 14.dp,
                            vertical = if (ownSurface) 0.dp else if (style.inline) 4.dp else 14.dp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetFallback(text: String, palette: ReaderPalette, style: ChatBubbleStyle) {
    if (text.isBlank()) return
    if (style.inline) {
        AiRichText(content = text, palette = palette)
        return
    }
    Surface(
        color = style.container,
        contentColor = style.content,
        border = style.border,
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) { AiRichText(content = text, palette = palette) }
    }
}

/* ---------------------------- 宿主组件 ---------------------------- */

@Composable
private fun rememberCompanionGenUiHost(palette: ReaderPalette, controller: CompanionGenUiController): GenUiHost {
    val context = LocalContext.current
    val animations = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) > 0f
    }
    return remember(palette, controller, animations) { CompanionGenUiHost(palette, controller, animations) }
}

private class CompanionGenUiHost(
    private val palette: ReaderPalette,
    private val controller: CompanionGenUiController,
    override val animationsEnabled: Boolean
) : GenUiHost {

    @Composable
    override fun Markdown(text: String, modifier: Modifier) {
        Box(modifier) { AiRichText(content = text, palette = palette) }
    }

    @Composable
    override fun Component(node: GenUiNode, modifier: Modifier): Boolean = when (node.type) {
        "Quote" -> {
            GenUiQuoteCard(node, palette, controller, modifier)
            true
        }
        "Chart" -> {
            val spec = remember(node.prop("content")) { chartSpecOf(node.prop("content")) }
            if (spec != null) CompanionChartCard(spec, palette, modifier)
            spec != null
        }
        "Character" -> {
            GenUiCharacterChip(node, palette, modifier)
            true
        }
        "AppBlock" -> {
            GenUiPendingFeature(node.string("title") ?: stringResource(R.string.genui_appblock_default_title), palette, modifier)
            true
        }
        else -> false
    }

    /** 只认宿主引用（图片库 asset:、本条回复生成的 gen:）；任意网址一律不加载（防外带数据）。 */
    override fun imageModel(src: String): Any? =
        if (src.startsWith("asset:") || src.startsWith("gen:")) controller.resolveImage(src) else null

    override fun fontFamily(name: String): FontFamily? = null
}

/**
 * 图表规格：墨知格式（type/labels/series）直接解码；ChatGPT DIL 的写法
 * （chartType/xKey/series[dataKey]/data[]）先换算成墨知格式，模型两种都写得出来。
 */
internal fun chartSpecOf(content: JsonElement?): com.mozhi.reader.ai.agent.ChartSpec? {
    val obj = content as? JsonObject ?: return null
    val normalized = if (obj["chartType"] != null && obj["data"] is JsonArray) {
        val data = (obj["data"] as JsonArray).mapNotNull { it as? JsonObject }
        val xKey = (obj["xKey"] as? JsonPrimitive)?.contentOrNull ?: "name"
        val series = (obj["series"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val meta = obj["meta"] as? JsonObject
        buildJsonObject {
            put("type", (obj["chartType"] as JsonPrimitive).content)
            ((meta?.get("title") ?: obj["title"]) as? JsonPrimitive)?.contentOrNull?.let { put("title", it) }
            put("labels", buildJsonArray { data.forEach { add(JsonPrimitive((it[xKey] as? JsonPrimitive)?.contentOrNull.orEmpty())) } })
            put("series", buildJsonArray {
                series.ifEmpty { listOf(buildJsonObject { put("dataKey", "value") }) }.forEach { s ->
                    val dataKey = (s["dataKey"] as? JsonPrimitive)?.contentOrNull ?: "value"
                    add(buildJsonObject {
                        put("name", (s["label"] as? JsonPrimitive)?.contentOrNull ?: dataKey)
                        put("values", buildJsonArray { data.forEach { add(JsonPrimitive((it[dataKey] as? JsonPrimitive)?.doubleOrNull ?: 0.0)) } })
                    })
                }
            })
        }
    } else obj
    return (ChartSpecCodec.decode(normalized.toString()) as? ChartSpecCodec.Outcome.Valid)?.spec
}

/**
 * 原文引用卡：在已读范围内核对，找得到才以原文样式显示并可点按跳回；
 * 找不到时降级为普通文字并注明，不用引文样式冒充原文。核对期间保持同样高度。
 */
@Composable
private fun GenUiQuoteCard(node: GenUiNode, palette: ReaderPalette, controller: CompanionGenUiController, modifier: Modifier) {
    val text = node.string("text")?.trim().orEmpty()
    if (text.isEmpty()) return
    val chapter = node.int("chapter")
    val located by produceState<QuoteCheck>(QuoteCheck.Checking, chapter, text) {
        value = controller.verifyQuote(chapter, text)?.let { QuoteCheck.Found(it) } ?: QuoteCheck.Missing
    }
    val found = located as? QuoteCheck.Found
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.onBackground.copy(alpha = 0.04f))
            .then(if (found != null) Modifier.clickable { controller.onLocate(found.citation) } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (located !is QuoteCheck.Missing) {
                Icon(Icons.Outlined.FormatQuote, null, Modifier.size(16.dp), tint = palette.accent.copy(alpha = 0.8f))
            }
            Text(
                text = if (located is QuoteCheck.Missing) text else "「$text」",
                style = LocalTextStyle.current.copy(
                    fontFamily = if (located is QuoteCheck.Missing) LocalTextStyle.current.fontFamily else FontFamily.Serif,
                    fontStyle = FontStyle.Normal
                ),
                color = palette.onBackground,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        val chapterLabel = chapter?.let { stringResource(R.string.genui_quote_chapter, it) }
        val caption = when (located) {
            is QuoteCheck.Found -> listOfNotNull(chapterLabel, stringResource(R.string.genui_quote_jump)).joinToString(" · ")
            QuoteCheck.Missing -> stringResource(R.string.genui_quote_missing)
            QuoteCheck.Checking -> chapterLabel ?: " "
        }
        Text(caption, style = MaterialTheme.typography.labelSmall, color = palette.muted)
        node.string("note")?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = palette.muted)
        }
    }
}

private sealed interface QuoteCheck {
    data object Checking : QuoteCheck
    data object Missing : QuoteCheck
    data class Found(val citation: LocatedCompanionCitation) : QuoteCheck
}

@Composable
private fun GenUiCharacterChip(node: GenUiNode, palette: ReaderPalette, modifier: Modifier) {
    val name = node.string("name")?.trim().orEmpty()
    if (name.isEmpty()) return
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.accent.copy(alpha = 0.1f))
            .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Person, null, Modifier.size(14.dp), tint = palette.accent)
        Text(name, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), color = palette.onBackground, modifier = Modifier.padding(start = 4.dp))
    }
}

/** 尚未开放的宿主能力（随后续批次上线）：显示一个安静的占位，不报错。 */
@Composable
private fun GenUiPendingFeature(title: String, palette: ReaderPalette, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.onBackground.copy(alpha = 0.04f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Widgets, null, Modifier.size(16.dp), tint = palette.muted)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = palette.onBackground)
            Text(stringResource(R.string.genui_appblock_pending), style = MaterialTheme.typography.labelSmall, color = palette.muted)
        }
    }
}

private const val WIDGET_REVEAL_MS = 220
private val WIDGET_PENDING_HEIGHT = 56.dp
