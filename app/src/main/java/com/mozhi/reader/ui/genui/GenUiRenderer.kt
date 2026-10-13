package com.mozhi.reader.ui.genui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mozhi.reader.ai.genui.GenUiCatalog
import com.mozhi.reader.ai.genui.GenUiFallback
import com.mozhi.reader.ai.genui.GenUiNode
import com.mozhi.reader.ai.genui.GenUiStyle
import com.mozhi.reader.ai.genui.GenUiStyleParser
import com.mozhi.reader.ai.genui.StylePaint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** 当前位置的近似底色：容器叠上自己的背景后更新，文字据此做对比度校正。 */
internal val LocalGenUiSurface = compositionLocalOf { Color.Unspecified }

/** 所在 form 的提交处理器编号（按钮写了 submit 时触发）。 */
internal val LocalGenUiFormSubmit = compositionLocalOf<String?> { null }

/**
 * 渲染一个互动岛（运行时交出的界面树）。
 *
 * 调用方负责外壳（随角色聊天外观）与 [LocalTextStyle]（角色聊天字体），这里只画内容。
 * 节点按结构路径做 key：生成中途重编译时，用户正在操作的控件被复用而不是重建。
 */
@Composable
fun GenUiIsland(
    island: GenUiNode,
    colors: GenUiColors,
    events: GenUiEvents,
    modifier: Modifier = Modifier,
    host: GenUiHost = LocalGenUiHost.current
) {
    CompositionLocalProvider(
        LocalGenUiColors provides colors,
        LocalGenUiEvents provides events,
        LocalGenUiHost provides host,
        LocalGenUiSurface provides colors.paper
    ) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Children(island.flatChildren)
        }
    }
}

@Composable
private fun Children(children: List<GenUiNode>, childModifier: (GenUiNode) -> Modifier = { Modifier }) {
    children.forEachIndexed { index, child ->
        key(child.key.ifEmpty { "t$index" }) {
            GenUiNodeView(child, childModifier(child))
        }
    }
}

@Composable
internal fun GenUiNodeView(node: GenUiNode, modifier: Modifier = Modifier) {
    if (node.isText) {
        TextBlock(GenUiNode(type = "text", key = "", children = listOf(node)), modifier)
        return
    }
    if (node.flag("hidden")) return
    val host = LocalGenUiHost.current
    when (node.type) {
        GenUiNode.FRAGMENT, GenUiNode.ISLAND, "tab", "form" -> FormOrColumn(node, modifier)
        "#md" -> host.Markdown(node.children.joinToString("") { it.text.orEmpty() }, modifier)
        "box" -> BoxLayout(node, modifier)
        "card" -> CardLayout(node, modifier)
        "row" -> RowLayout(node, modifier)
        "grid" -> GridLayout(node, modifier)
        "layer" -> LayerLayout(node, modifier)
        "scroller" -> ScrollerLayout(node, modifier)
        "divider" -> DividerView(node, modifier)
        "spacer" -> Spacer(modifier.height(spacing(node.prop("size"), 12.dp)))
        "tabs" -> TabsView(node, modifier)
        "disclosure" -> DisclosureView(node, modifier)
        "title", "text", "caption", "label", "bold", "italic", "link", "code", "list-item", "table-cell" -> TextBlock(node, modifier)
        "blockquote" -> BlockquoteView(node, modifier)
        "badge" -> BadgeView(node, modifier)
        "icon" -> IconView(node, modifier)
        "button" -> GenUiButton(node, modifier)
        "segmented" -> GenUiSegmented(node, modifier)
        "select" -> GenUiSelect(node, modifier)
        "chips" -> GenUiChips(node, modifier)
        "checkbox", "switch" -> GenUiToggle(node, modifier)
        "radio-group" -> GenUiRadioGroup(node, modifier)
        "radio" -> TextBlock(node, modifier)
        "slider" -> GenUiSlider(node, modifier)
        "stepper" -> GenUiStepper(node, modifier)
        "input", "textarea" -> GenUiInput(node, modifier)
        "table" -> TableView(node, modifier)
        "table-row" -> TableRowView(node, header = node.flag("header"), modifier = modifier)
        "list" -> ListView(node, modifier)
        "progress" -> ProgressView(node, modifier)
        "stat" -> StatView(node, modifier)
        "steps" -> StepsView(node, modifier)
        "step" -> StepView(node, done = node.flag("done"), modifier = modifier)
        "timeline" -> TimelineView(node, modifier)
        "event" -> EventView(node, last = true, modifier = modifier)
        "image" -> ImageView(node, modifier)
        else -> {
            if (!host.Component(node, modifier)) {
                val text = GenUiFallback.projectTree(GenUiNode(GenUiNode.ISLAND, node.key, children = listOf(node)))
                if (text.isNotBlank()) GenUiPlainText(text, modifier)
            }
        }
    }
}

/* ---------------------------- 令牌换算 ---------------------------- */

private val SPACING_SCALE = listOf(0.dp, 4.dp, 8.dp, 12.dp, 16.dp, 20.dp, 28.dp)

/** gap / padding：0–6 档、xs–2xl 词、或较大的数字直接当 dp。 */
internal fun spacing(value: kotlinx.serialization.json.JsonElement?, default: Dp): Dp {
    val primitive = value as? JsonPrimitive ?: return default
    val text = primitive.contentOrNull?.trim()?.lowercase() ?: return default
    return when (text) {
        "none" -> 0.dp
        "xs" -> 4.dp
        "sm" -> 8.dp
        "md" -> 12.dp
        "lg" -> 16.dp
        "xl" -> 20.dp
        "2xl", "xxl" -> 28.dp
        else -> {
            val n = text.removeSuffix("px").toFloatOrNull() ?: return default
            if (n in 0f..6f && n == Math.floor(n.toDouble()).toFloat()) SPACING_SCALE[n.toInt()] else n.coerceIn(0f, 64f).dp
        }
    }
}

private fun radiusToken(value: String?, default: Dp): Dp = when (value) {
    null -> default
    "none" -> 0.dp
    "xs" -> 8.dp
    "sm" -> 14.dp
    "md" -> 16.dp
    "lg" -> 20.dp
    "xl" -> 28.dp
    "2xl" -> 32.dp
    "full" -> 999.dp
    else -> default
}

@Composable
private fun rememberStyle(node: GenUiNode): GenUiStyle {
    val raw = node.prop("style")
    return remember(raw) { GenUiStyleParser.parse(raw) }
}

/** 令牌属性（background / border / radius / padding）+ 组件默认 → 盒子默认外观。 */
@Composable
private fun tokenDefaults(node: GenUiNode, base: BoxDefaults): BoxDefaults {
    val colors = LocalGenUiColors.current
    val background = when (node.string("background")) {
        "card", "surface" -> colors.card
        "sunken" -> colors.ink.copy(alpha = 0.04f)
        "accent" -> colors.accent.copy(alpha = 0.12f)
        "none" -> null
        else -> base.background
    }
    val bordered = node.prop("border") != null && node.flag("border")
    return base.copy(
        background = background,
        borderWidth = if (bordered) 1.dp else base.borderWidth,
        borderColor = if (bordered) colors.line else base.borderColor,
        radius = radiusToken(node.string("radius"), if (bordered || background != null) maxOf(base.radius, 14.dp) else base.radius),
        padding = node.prop("padding")?.let { spacing(it, base.padding ?: 0.dp) } ?: base.padding
    )
}

/**
 * 通用容器：样式落盘 → 背景图（若有）→ 内容。内容在新的底色上下文里组合。
 */
@Composable
private fun Container(
    node: GenUiNode,
    base: BoxDefaults,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LocalGenUiColors.current
    val host = LocalGenUiHost.current
    val style = rememberStyle(node)
    val parent = LocalGenUiSurface.current.takeIf { it != Color.Unspecified } ?: colors.paper
    var surface = parent
    val boxModifier = modifier.genUiBox(style, tokenDefaults(node, base), colors, parent) { surface = it }
    val image = style.backgrounds.firstNotNullOfOrNull { it as? StylePaint.Image }?.let { host.imageModel(it.src) }
    Box(boxModifier) {
        if (image != null) {
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        CompositionLocalProvider(LocalGenUiSurface provides surface) { content() }
    }
}

/* ---------------------------- 布局 ---------------------------- */

@Composable
private fun FormOrColumn(node: GenUiNode, modifier: Modifier) {
    val submit = if (node.type == "form") node.handler("onSubmit") else LocalGenUiFormSubmit.current
    CompositionLocalProvider(LocalGenUiFormSubmit provides submit) {
        BoxLayout(node, modifier)
    }
}

private fun horizontalAlignment(align: String?): Alignment.Horizontal = when (align) {
    "center" -> Alignment.CenterHorizontally
    "end", "right" -> Alignment.End
    else -> Alignment.Start
}

private fun verticalAlignment(align: String?): Alignment.Vertical = when (align) {
    "start", "top" -> Alignment.Top
    "end", "bottom" -> Alignment.Bottom
    else -> Alignment.CenterVertically
}

private fun mainArrangement(justify: String?, gap: Dp): Arrangement.Horizontal = when (justify) {
    "center" -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    "end" -> Arrangement.spacedBy(gap, Alignment.End)
    "between" -> Arrangement.SpaceBetween
    "around" -> Arrangement.SpaceAround
    "evenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(gap)
}

private fun verticalArrangement(justify: String?, gap: Dp): Arrangement.Vertical = when (justify) {
    "center" -> Arrangement.spacedBy(gap, Alignment.CenterVertically)
    "end" -> Arrangement.spacedBy(gap, Alignment.Bottom)
    "between" -> Arrangement.SpaceBetween
    "around" -> Arrangement.SpaceAround
    "evenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(gap)
}

@Composable
private fun BoxLayout(node: GenUiNode, modifier: Modifier, base: BoxDefaults = BoxDefaults()) {
    val style = rememberStyle(node)
    val gap = style.gap?.dp ?: spacing(node.prop("gap"), 8.dp)
    val stretch = node.string("align") == "stretch"
    Container(node, base, modifier) {
        Column(
            verticalArrangement = verticalArrangement(node.string("justify"), gap),
            horizontalAlignment = horizontalAlignment(node.string("align"))
        ) {
            Children(node.flatChildren) { child -> if (stretch) Modifier.fillMaxWidth() else Modifier }
        }
    }
}

@Composable
private fun CardLayout(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    BoxLayout(
        node,
        modifier.fillMaxWidth(),
        BoxDefaults(background = colors.card, borderColor = colors.line, borderWidth = 1.dp, radius = 20.dp, padding = 16.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowLayout(node: GenUiNode, modifier: Modifier) {
    val style = rememberStyle(node)
    val gap = style.gap?.dp ?: spacing(node.prop("gap"), 8.dp)
    val justify = node.string("justify")
    val wrap = node.flag("wrap")
    val children = node.flatChildren
    Container(node, BoxDefaults(), modifier.then(if (justify != null && justify != "start") Modifier.fillMaxWidth() else Modifier)) {
        if (wrap) {
            FlowRow(
                horizontalArrangement = mainArrangement(justify, gap),
                verticalArrangement = Arrangement.spacedBy(gap),
                itemVerticalAlignment = verticalAlignment(node.string("align"))
            ) {
                children.forEachIndexed { index, child ->
                    key(child.key.ifEmpty { "t$index" }) {
                        val grow = growOf(child)
                        GenUiNodeView(child, if (grow != null) Modifier.weight(grow) else Modifier)
                    }
                }
            }
        } else {
            Row(
                horizontalArrangement = mainArrangement(justify, gap),
                verticalAlignment = verticalAlignment(node.string("align"))
            ) {
                children.forEachIndexed { index, child ->
                    key(child.key.ifEmpty { "t$index" }) {
                        // 文字类子元素按需收缩（fill = false：短文字保持自然宽度），长标题不会把旁边的徽标挤出屏幕
                        val explicitGrow = growOf(child)
                        val grow = explicitGrow ?: if (children.size > 1 && (child.isText || child.type in TEXTY)) 1f else null
                        GenUiNodeView(child, if (grow != null) Modifier.weight(grow, fill = explicitGrow != null) else Modifier)
                    }
                }
            }
        }
    }
}

private val TEXTY = setOf("text", "title", "caption", "label")

@Composable
private fun growOf(child: GenUiNode): Float? {
    val styleFlex = if (child.isText) null else remember(child.prop("style")) { GenUiStyleParser.parse(child.prop("style")).flex }
    return (styleFlex ?: child.number("grow")?.toFloat())?.takeIf { it > 0f }
}

@Composable
private fun GridLayout(node: GenUiNode, modifier: Modifier) {
    val style = rememberStyle(node)
    val gap = style.gap?.dp ?: spacing(node.prop("gap"), 8.dp)
    val columns = (node.int("columns") ?: 2).coerceIn(1, 3)
    val cells = node.flatChildren.filterNot { it.isText && it.text.isNullOrBlank() }
    Container(node, BoxDefaults(), modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            cells.chunked(columns).forEachIndexed { rowIndex, row ->
                key("row$rowIndex") {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.height(IntrinsicSize.Min)) {
                        row.forEachIndexed { index, cell ->
                            key(cell.key.ifEmpty { "t$rowIndex-$index" }) {
                                GenUiNodeView(cell, Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerLayout(node: GenUiNode, modifier: Modifier) {
    val rtl = isRtl()
    Container(node, BoxDefaults(), modifier) {
        node.flatChildren.forEachIndexed { index, child ->
            key(child.key.ifEmpty { "t$index" }) {
                val style = if (child.isText) GenUiStyle.EMPTY else remember(child.prop("style")) { GenUiStyleParser.parse(child.prop("style")) }
                if (style.position == "absolute" || style.position == "fixed") {
                    val inset = style.inset
                    val alignment = when {
                        inset.top != null && inset.left != null -> Alignment.TopStart
                        inset.top != null && inset.right != null -> Alignment.TopEnd
                        inset.bottom != null && inset.left != null -> Alignment.BottomStart
                        inset.bottom != null && inset.right != null -> Alignment.BottomEnd
                        inset.top != null -> Alignment.TopCenter
                        inset.bottom != null -> Alignment.BottomCenter
                        inset.left != null -> Alignment.CenterStart
                        inset.right != null -> Alignment.CenterEnd
                        else -> Alignment.Center
                    }
                    val start = if (rtl) inset.right else inset.left
                    val end = if (rtl) inset.left else inset.right
                    GenUiNodeView(
                        child,
                        Modifier.align(alignment).padding(
                            start = (start ?: 0f).coerceIn(0f, 400f).dp,
                            top = (inset.top ?: 0f).coerceIn(0f, 400f).dp,
                            end = (end ?: 0f).coerceIn(0f, 400f).dp,
                            bottom = (inset.bottom ?: 0f).coerceIn(0f, 400f).dp
                        )
                    )
                } else {
                    GenUiNodeView(child)
                }
            }
        }
    }
}

@Composable
private fun ScrollerLayout(node: GenUiNode, modifier: Modifier) {
    val style = rememberStyle(node)
    val gap = style.gap?.dp ?: spacing(node.prop("gap"), 8.dp)
    Container(node, BoxDefaults(), modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Children(node.flatChildren)
        }
    }
}

@Composable
private fun DividerView(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val style = rememberStyle(node)
    val color = style.border?.color?.let(colors::resolve) ?: style.backgrounds.firstNotNullOfOrNull { (it as? StylePaint.Solid)?.color }?.let(colors::resolve) ?: colors.line
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(color.copy(alpha = color.alpha * (style.opacity ?: 1f)))
    )
}

/* ---------------------------- 切换 ---------------------------- */

@Composable
private fun TabsView(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val events = LocalGenUiEvents.current
    val tabs = node.flatChildren.filter { it.type == "tab" }
    if (tabs.isEmpty()) return
    val handler = node.handler("onChange")
    val controlled = node.prop("value")
    var local by rememberSaveable(node.key) { mutableStateOf<String?>(null) }
    var pending by remember(node.key) { mutableStateOf<String?>(null) }
    val externalKey = controlled?.let { tabValueKey(it) }
    androidx.compose.runtime.LaunchedEffect(externalKey) { pending = null }
    val activeKey = pending ?: (if (handler != null) externalKey else local) ?: tabValueKey(tabs.first().prop("value"), tabs.first())
    val active = tabs.firstOrNull { tabValueKey(it.prop("value"), it) == activeKey } ?: tabs.first()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tabs.forEach { tab ->
                val key = tabValueKey(tab.prop("value"), tab)
                val selected = tab === active
                val label = tab.string("label") ?: tab.string("value") ?: ""
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.accent else colors.muted,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(role = Role.Tab) {
                            if (handler != null) {
                                pending = key
                                events.dispatch(handler, JsonArray(listOf(tab.prop("value") ?: JsonPrimitive(label))))
                            } else {
                                local = key
                            }
                        }
                        .heightIn(min = 36.dp)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            key(active.key) { Children(active.flatChildren) }
        }
    }
}

private fun tabValueKey(value: kotlinx.serialization.json.JsonElement?, fallback: GenUiNode? = null): String =
    value?.toString() ?: fallback?.string("label") ?: fallback?.key.orEmpty()

@Composable
private fun DisclosureView(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    var open by rememberSaveable(node.key) { mutableStateOf(node.flag("open")) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "disclosure")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { open = !open }
                .heightIn(min = 44.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                node.string("label").orEmpty(),
                style = LocalTextStyle.current.copy(fontWeight = FontWeight.Medium),
                color = colors.ink,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp).rotate(rotation), tint = colors.muted)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Children(node.flatChildren)
            }
        }
    }
}

/* ---------------------------- 文字 ---------------------------- */

private val INLINE_TYPES = setOf(GenUiNode.TEXT, "bold", "italic", "link", "code", "Character")

private fun isInline(node: GenUiNode): Boolean =
    node.isText || node.type in INLINE_TYPES || GenUiCatalog.resolve(node.type)?.inline == true

@Composable
private fun baseTextStyle(node: GenUiNode): Pair<TextStyle, Color> {
    val colors = LocalGenUiColors.current
    val base = LocalTextStyle.current
    val type = MaterialTheme.typography
    val size = node.string("size")
    return when (node.type) {
        "title" -> when (size) {
            "xs", "sm" -> base.merge(type.titleSmall)
            "lg" -> base.merge(type.titleLarge.copy(fontFamily = FontFamily.Serif))
            "xl" -> base.merge(type.titleLarge.copy(fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp))
            "2xl" -> base.merge(type.titleLarge.copy(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp))
            else -> base.merge(type.titleMedium)
        } to colors.ink
        "caption" -> base.merge(type.bodySmall) to colors.muted
        "label" -> base.merge(type.labelLarge) to colors.ink
        "code" -> base.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp) to colors.ink
        "link" -> base to colors.accent
        else -> when (size) {
            "xs", "sm" -> base.merge(type.bodySmall)
            "lg", "xl", "2xl" -> base.merge(type.bodyLarge)
            else -> base
        }.let { style ->
            when (node.string("weight")) {
                "bold" -> style.copy(fontWeight = FontWeight.Bold)
                "medium" -> style.copy(fontWeight = FontWeight.Medium)
                else -> style
            }
        } to colors.ink
    }
}

/** 行内内容 → 带样式的文字；含非行内子元素时返回 null（改用流式布局）。 */
private fun inlineText(node: GenUiNode, styles: GenUiInlineMarkdown.Styles, transform: String?, accent: Color): AnnotatedString? {
    if (node.flatChildren.any { !isInline(it) }) return null
    return buildAnnotatedString { appendInline(node.flatChildren, styles, transform, accent) }
}

private fun AnnotatedString.Builder.appendInline(nodes: List<GenUiNode>, styles: GenUiInlineMarkdown.Styles, transform: String?, accent: Color) {
    nodes.forEach { child ->
        when (child.type) {
            GenUiNode.TEXT -> GenUiInlineMarkdown.append(this, applyTextTransform(child.text.orEmpty(), transform), styles)
            "bold" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(child.flatChildren, styles, transform, accent) }
            "italic" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(child.flatChildren, styles, transform, accent) }
            "link" -> withStyle(styles.link) { appendInline(child.flatChildren, styles, transform, accent) }
            "code" -> withStyle(styles.code) { append(child.plainText()) }
            "Character" -> withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) { append(child.string("name").orEmpty()) }
            else -> appendInline(child.flatChildren, styles, transform, accent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextBlock(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val style = rememberStyle(node)
    val (base, defaultColor) = baseTextStyle(node)
    val tone = colors.tone(node.string("color"))
    val parent = LocalGenUiSurface.current.takeIf { it != Color.Unspecified } ?: colors.paper
    var surface = parent
    val boxed = style.backgrounds.isNotEmpty() || style.border != null || !style.padding.isEmpty || style.shadows.isNotEmpty() ||
        style.transform != null || style.animation != null || style.opacity != null || !style.margin.isEmpty || style.width != null
    val boxModifier = if (boxed) modifier.genUiBox(style, BoxDefaults(), colors, parent) { surface = it } else modifier
    val textStyle = genUiTextStyle(base, style, tone ?: defaultColor, surface, colors, large = node.type == "title")
    val inlineStyles = remember(textStyle.color, colors.accent) { GenUiInlineMarkdown.defaultStyles(textStyle.color.takeIf { it != Color.Unspecified } ?: colors.ink, colors.accent) }
    val annotated = remember(node, inlineStyles, style.textTransform) { inlineText(node, inlineStyles, style.textTransform, colors.accent) }
    if (annotated != null) {
        if (annotated.isEmpty()) return
        Text(
            text = annotated,
            style = textStyle,
            softWrap = !style.noWrap,
            maxLines = if (style.noWrap) 1 else Int.MAX_VALUE,
            overflow = if (style.noWrap) TextOverflow.Ellipsis else TextOverflow.Clip,
            modifier = boxModifier
        )
    } else {
        // 文字里夹着徽标、图标之类的块：流式排开，连续的行内段合成一段文字
        CompositionLocalProvider(LocalTextStyle provides textStyle, LocalGenUiSurface provides surface) {
            FlowRow(boxModifier, horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                val runs = mutableListOf<MutableList<GenUiNode>>()
                node.flatChildren.forEach { child ->
                    if (isInline(child)) {
                        if (runs.lastOrNull()?.firstOrNull()?.let(::isInline) == true) runs.last() += child else runs += mutableListOf(child)
                    } else {
                        runs += mutableListOf(child)
                    }
                }
                runs.forEachIndexed { index, run ->
                    key(index) {
                        if (run.size == 1 && !isInline(run[0])) {
                            GenUiNodeView(run[0])
                        } else {
                            val text = buildAnnotatedString { appendInline(run, inlineStyles, style.textTransform, colors.accent) }
                            Text(text, style = textStyle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockquoteView(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.accent.copy(alpha = 0.6f)))
        Spacer(Modifier.width(10.dp))
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(color = colors.muted)) {
            TextBlock(GenUiNode("text", node.key, node.props, node.children), Modifier.weight(1f))
        }
    }
}

@Composable
private fun BadgeView(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val style = rememberStyle(node)
    val tone = colors.tone(node.string("color")) ?: colors.muted
    val parent = LocalGenUiSurface.current.takeIf { it != Color.Unspecified } ?: colors.paper
    var surface = parent
    val defaults = BoxDefaults(background = tone.copy(alpha = 0.14f).compositeOver(parent), radius = 999.dp)
    val boxModifier = modifier.genUiBox(style, defaults, colors, parent) { surface = it }.padding(horizontal = 9.dp, vertical = 3.dp)
    val textStyle = genUiTextStyle(MaterialTheme.typography.labelSmall, style, tone, surface, colors)
    Text(node.plainText(), style = textStyle, maxLines = 1, modifier = boxModifier)
}

@Composable
private fun IconView(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val vector = GenUiIcons.resolve(node.string("name")) ?: return
    val style = rememberStyle(node)
    val tint = style.color?.let(colors::resolve) ?: colors.tone(node.string("color")) ?: colors.ink
    val size = (node.number("size")?.toFloat() ?: style.fontSize?.value ?: 18f).coerceIn(10f, 48f).dp
    Icon(vector, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/* ---------------------------- 数据 ---------------------------- */

@Composable
private fun TableView(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val rows = node.flatChildren.filter { it.type == "table-row" }
    Container(node, BoxDefaults(borderColor = colors.line, borderWidth = 1.dp, radius = 14.dp), modifier.fillMaxWidth()) {
        Column(Modifier.clip(RoundedCornerShape(14.dp))) {
            rows.forEachIndexed { index, row ->
                key(row.key.ifEmpty { "r$index" }) {
                    TableRowView(row, header = row.flag("header") || (index == 0 && rows.size > 1 && row.flag("header")))
                    if (index < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                }
            }
        }
    }
}

@Composable
private fun TableRowView(node: GenUiNode, header: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalGenUiColors.current
    val cells = node.flatChildren.filter { !it.isText }
    Row(
        modifier
            .fillMaxWidth()
            .background(if (header) colors.ink.copy(alpha = 0.04f) else Color.Transparent)
            .height(IntrinsicSize.Min)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cells.forEachIndexed { index, cell ->
            key(cell.key.ifEmpty { "c$index" }) {
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontWeight = if (header) FontWeight.SemiBold else LocalTextStyle.current.fontWeight)
                ) {
                    val align = cell.string("align")
                    Box(Modifier.weight(1f), contentAlignment = if (align == "end" || align == "right") Alignment.CenterEnd else if (align == "center") Alignment.Center else Alignment.CenterStart) {
                        GenUiNodeView(cell)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListView(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val numbered = node.string("marker") == "number"
    val none = node.string("marker") == "none"
    val items = node.flatChildren.filter { it.type == "list-item" || !it.isText }
    Container(node, BoxDefaults(), modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEachIndexed { index, item ->
                key(item.key.ifEmpty { "i$index" }) {
                    Row(verticalAlignment = Alignment.Top) {
                        if (!none) {
                            Text(
                                if (numbered) "${index + 1}." else "•",
                                style = LocalTextStyle.current,
                                color = colors.muted,
                                modifier = Modifier.width(if (numbered) 22.dp else 14.dp)
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (item.flatChildren.all(::isInline)) TextBlock(item, Modifier) else Children(item.flatChildren)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressView(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val max = (node.number("max") ?: 100.0).takeIf { it > 0 } ?: 100.0
    val value = (node.number("value") ?: 0.0).coerceIn(0.0, max)
    val tone = colors.tone(node.string("color")) ?: colors.accent
    val fraction by animateFloatAsState((value / max).toFloat(), tween(450), label = "progress")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        node.string("label")?.let { label ->
            Row(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.weight(1f))
                Text("${(value / max * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
        LinearProgressIndicator(
            progress = { fraction },
            color = tone,
            trackColor = colors.ink.copy(alpha = 0.08f),
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            drawStopIndicator = {}
        )
    }
}

@Composable
private fun StatView(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val style = rememberStyle(node)
    val parent = LocalGenUiSurface.current.takeIf { it != Color.Unspecified } ?: colors.paper
    val value = node.string("value").orEmpty()
    val big = genUiTextStyle(
        MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        style,
        colors.tone(node.string("color")) ?: colors.ink,
        parent,
        colors,
        large = true
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = big)
            node.string("unit")?.let { Text(" $it", style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp)) }
        }
        node.string("label")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
    }
}

@Composable
private fun StepsView(node: GenUiNode, modifier: Modifier) {
    val steps = node.flatChildren.filter { it.type == "step" }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        steps.forEachIndexed { index, step ->
            key(step.key.ifEmpty { "s$index" }) { StepView(step, done = step.flag("done")) }
        }
    }
}

@Composable
private fun StepView(node: GenUiNode, done: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalGenUiColors.current
    val events = LocalGenUiEvents.current
    val handler = node.handler("onToggle")
    var local by rememberSaveable(node.key) { mutableStateOf<Boolean?>(null) }
    var pending by remember(node.key) { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(done) { pending = null }
    val checked = pending ?: if (handler != null) done else local ?: done
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Checkbox) {
                if (handler != null) {
                    pending = !checked
                    events.dispatch(handler, JsonArray(listOf(JsonPrimitive(!checked))))
                } else {
                    local = !checked
                }
            }
            .heightIn(min = 44.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) colors.accent else Color.Transparent)
                .then(if (!checked) Modifier.background(colors.ink.copy(alpha = 0.08f)) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = colors.onAccent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            node.string("title")?.let {
                Text(
                    it,
                    style = LocalTextStyle.current.copy(fontWeight = FontWeight.Medium),
                    color = if (checked) colors.muted else colors.ink
                )
            }
            if (node.flatChildren.isNotEmpty()) {
                if (node.flatChildren.all(::isInline)) TextBlock(GenUiNode("caption", node.key + "/c", emptyMap(), node.children), Modifier)
                else Children(node.flatChildren)
            }
        }
    }
}

@Composable
private fun TimelineView(node: GenUiNode, modifier: Modifier) {
    val events = node.flatChildren.filter { it.type == "event" }
    Column(modifier.fillMaxWidth()) {
        events.forEachIndexed { index, event ->
            key(event.key.ifEmpty { "e$index" }) { EventView(event, last = index == events.lastIndex) }
        }
    }
}

@Composable
private fun EventView(node: GenUiNode, last: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalGenUiColors.current
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(16.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(colors.accent))
            if (!last) Box(Modifier.width(1.dp).weight(1f).background(colors.line))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            node.string("time")?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.muted) }
            node.string("title")?.let { Text(it, style = LocalTextStyle.current.copy(fontWeight = FontWeight.Medium), color = colors.ink) }
            if (node.flatChildren.isNotEmpty()) {
                if (node.flatChildren.all(::isInline)) TextBlock(GenUiNode("text", node.key + "/t", emptyMap(), node.children), Modifier)
                else Children(node.flatChildren)
            }
        }
    }
}

@Composable
private fun ImageView(node: GenUiNode, modifier: Modifier) {
    val host = LocalGenUiHost.current
    val src = node.string("src") ?: return
    val model = remember(src) { host.imageModel(src) } ?: return
    val aspect = node.string("aspect")?.let { a ->
        val parts = a.split(':', '/').mapNotNull { it.trim().toFloatOrNull() }
        if (parts.size == 2 && parts[1] > 0f) parts[0] / parts[1] else null
    } ?: (4f / 3f)
    AsyncImage(
        model = model,
        contentDescription = node.string("alt"),
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.4f, 3f)).clip(RoundedCornerShape(14.dp))
    )
}

/** 纯文字兜底（宿主不支持的组件、编译退化时）。 */
@Composable
internal fun GenUiPlainText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = LocalTextStyle.current, modifier = modifier)
}
