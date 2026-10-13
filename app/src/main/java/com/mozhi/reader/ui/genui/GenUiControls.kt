package com.mozhi.reader.ui.genui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ai.genui.GenUiNode
import com.mozhi.reader.ai.genui.GenUiStyleParser
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 互动控件。手感的关键是「乐观更新」：拖滑块、点选项时，控件的视觉状态由本地状态立刻更新，
 * 事件再交给引擎；引擎回来的新树只更新依赖它的其它节点。手指永远不等 JS。
 * 引擎回传的值变化后（LaunchedEffect(外部值)）清掉本地的待定值，以引擎为准。
 */

internal data class GenUiOption(val label: String, val value: JsonElement)

internal fun options(node: GenUiNode): List<GenUiOption> {
    val array = node.prop("options") as? JsonArray ?: return emptyList()
    return array.mapNotNull { item ->
        when (item) {
            is JsonObject -> {
                val value = item["value"] ?: item["label"] ?: return@mapNotNull null
                val label = (item["label"] as? JsonPrimitive)?.contentOrNull ?: (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
                GenUiOption(label, value)
            }
            is JsonPrimitive -> GenUiOption(item.contentOrNull.orEmpty(), item)
            else -> null
        }
    }
}

/** 选项值比较：数字 1 与字符串 "1" 视为相同（模型两种写法混用很常见）。 */
internal fun sameValue(a: JsonElement?, b: JsonElement?): Boolean {
    if (a == null || b == null || a is JsonNull || b is JsonNull) return false
    if (a is JsonPrimitive && b is JsonPrimitive) {
        val da = a.doubleOrNull
        val db = b.doubleOrNull
        if (da != null && db != null) return da == db
        return a.content == b.content
    }
    return a == b
}

private fun args(vararg values: JsonElement): JsonArray = JsonArray(values.toList())

/* ---------------------------- 按钮 ---------------------------- */

@Composable
internal fun GenUiButton(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val events = LocalGenUiEvents.current
    val formSubmit = LocalGenUiFormSubmit.current
    val style = remember(node.prop("style")) { GenUiStyleParser.parse(node.prop("style")) }
    val handler = node.handler("onClick")
    val submits = node.flag("submit") || node.string("type") == "submit"
    val target = handler ?: formSubmit.takeIf { submits }
    val enabled = target != null && !node.flag("disabled")
    val variant = when (node.string("variant")) {
        "primary", "solid" -> "solid"
        "secondary", "outline" -> "outline"
        "ghost", "link" -> "ghost"
        "soft" -> "soft"
        else -> "soft"
    }
    val tone = colors.tone(node.string("color")) ?: colors.accent
    val parent = LocalGenUiSurface.current.takeIf { it != Color.Unspecified } ?: colors.paper
    val (container, content, border) = when (variant) {
        "solid" -> Triple(tone, if (tone == colors.accent) colors.onAccent else onColor(tone), null)
        "outline" -> Triple(Color.Transparent, colors.ink, colors.line)
        "ghost" -> Triple(Color.Transparent, tone, null)
        else -> Triple(tone.copy(alpha = 0.12f).compositeOver(parent), tone, null)
    }
    var surface = parent
    val shapeDefaults = BoxDefaults(
        background = container.takeIf { it.alpha > 0f },
        borderColor = border,
        borderWidth = if (border != null) 1.dp else 0.dp,
        radius = 999.dp
    )
    val icon = GenUiIcons.resolve(node.string("icon"))
    val boxModifier = modifier
        .then(if (node.flag("block")) Modifier.fillMaxWidth() else Modifier)
        .heightIn(min = 44.dp)
        .genUiBox(style, shapeDefaults, colors, parent) { surface = it }
        .clip(RoundedCornerShape(999.dp))
        .clickable(enabled = enabled, role = Role.Button) { target?.let { events.dispatch(it, JsonArray(emptyList())) } }
        .padding(horizontal = 18.dp, vertical = 10.dp)
    val textStyle = genUiTextStyle(MaterialTheme.typography.labelLarge, style, content, surface, colors)
    Row(
        boxModifier.then(if (enabled) Modifier else Modifier.background(Color.Transparent)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        val alpha = if (enabled) 1f else 0.4f
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = textStyle.color.copy(alpha = alpha))
        Text(node.plainText().trim(), style = textStyle.copy(color = textStyle.color.copy(alpha = textStyle.color.alpha * alpha)), maxLines = 1)
    }
}

private fun onColor(background: Color): Color =
    if (GenUiContrast.ratio(Color.White, background) >= GenUiContrast.ratio(Color(0xFF1A1A1A), background)) Color.White else Color(0xFF1A1A1A)

/* ---------------------------- 分段 / 胶囊 / 下拉 ---------------------------- */

@Composable
internal fun GenUiSegmented(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val events = LocalGenUiEvents.current
    val handler = node.handler("onChange")
    val opts = options(node)
    if (opts.isEmpty()) return
    val external = node.prop("value")
    var pending by remember(node.key) { mutableStateOf<JsonElement?>(null) }
    LaunchedEffect(external) { pending = null }
    val selected = pending ?: external
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.ink.copy(alpha = 0.05f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        opts.forEachIndexed { index, option ->
            key(index) {
                val active = sameValue(selected, option.value)
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (active) colors.card else Color.Transparent)
                        .then(if (active) Modifier.border(1.dp, colors.line, RoundedCornerShape(11.dp)) else Modifier)
                        .clickable(enabled = handler != null, role = Role.RadioButton) {
                            pending = option.value
                            handler?.let { events.dispatch(it, args(option.value)) }
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (active) colors.ink else colors.muted,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenUiChips(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val events = LocalGenUiEvents.current
    val handler = node.handler("onChange")
    val opts = options(node)
    val external = node.prop("value") as? JsonArray ?: JsonArray(emptyList())
    var pending by remember(node.key) { mutableStateOf<JsonArray?>(null) }
    LaunchedEffect(external) { pending = null }
    val selected = pending ?: external
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        opts.forEachIndexed { index, option ->
            key(index) {
                val active = selected.any { sameValue(it, option.value) }
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) colors.accent else colors.ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (active) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
                        .border(1.dp, if (active) colors.accent.copy(alpha = 0.5f) else colors.line, RoundedCornerShape(999.dp))
                        .clickable(enabled = handler != null, role = Role.Checkbox) {
                            val next = if (active) selected.filterNot { sameValue(it, option.value) } else selected + option.value
                            pending = JsonArray(next)
                            handler?.let { events.dispatch(it, args(JsonArray(next))) }
                        }
                        .heightIn(min = 36.dp)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
internal fun GenUiSelect(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val events = LocalGenUiEvents.current
    val handler = node.handler("onChange")
    val opts = options(node)
    val external = node.prop("value")
    var pending by remember(node.key) { mutableStateOf<JsonElement?>(null) }
    LaunchedEffect(external) { pending = null }
    val selected = pending ?: external
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        node.string("label")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.ink.copy(alpha = 0.05f))
                    .clickable(enabled = handler != null && opts.isNotEmpty(), role = Role.DropdownList) { open = true }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    opts.firstOrNull { sameValue(it.value, selected) }?.label ?: "—",
                    style = LocalTextStyle.current,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = colors.muted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                opts.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            open = false
                            pending = option.value
                            handler?.let { events.dispatch(it, args(option.value)) }
                        }
                    )
                }
            }
        }
    }
}

/* ---------------------------- 勾选 / 开关 / 单选 ---------------------------- */

@Composable
internal fun GenUiToggle(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val events = LocalGenUiEvents.current
    val handler = node.handler("onChange")
    val external = node.flag("checked")
    var pending by remember(node.key) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(external) { pending = null }
    val checked = pending ?: external
    val enabled = handler != null && !node.flag("disabled")
    val toggle = {
        pending = !checked
        handler?.let { events.dispatch(it, args(JsonPrimitive(!checked))) }
        Unit
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = if (node.type == "switch") Role.Switch else Role.Checkbox) { toggle() }
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.type == "switch") {
            Box(Modifier.weight(1f)) { if (node.flatChildren.isNotEmpty()) GenUiNodeView(GenUiNode("text", node.key + "/l", emptyMap(), node.children)) }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = { toggle() },
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    uncheckedThumbColor = colors.muted,
                    uncheckedTrackColor = colors.ink.copy(alpha = 0.08f),
                    uncheckedBorderColor = colors.line
                )
            )
        } else {
            Checkbox(
                checked = checked,
                onCheckedChange = { toggle() },
                enabled = enabled,
                colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.muted, checkmarkColor = colors.onAccent)
            )
            Box(Modifier.weight(1f)) { if (node.flatChildren.isNotEmpty()) GenUiNodeView(GenUiNode("text", node.key + "/l", emptyMap(), node.children)) }
        }
    }
}

@Composable
internal fun GenUiRadioGroup(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val events = LocalGenUiEvents.current
    val handler = node.handler("onChange")
    val radios = node.flatChildren.filter { it.type == "radio" }
    val external = node.prop("value")
    var pending by remember(node.key) { mutableStateOf<JsonElement?>(null) }
    LaunchedEffect(external) { pending = null }
    val selected = pending ?: external
    val enabled = handler != null && !node.flag("disabled")
    Column(modifier.fillMaxWidth()) {
        radios.forEachIndexed { index, radio ->
            key(radio.key.ifEmpty { "r$index" }) {
                val value = radio.prop("value") ?: JsonPrimitive(radio.plainText())
                val active = sameValue(selected, value)
                val choose = {
                    pending = value
                    handler?.let { events.dispatch(it, args(value)) }
                    Unit
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled, role = Role.RadioButton) { choose() }
                        .heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = active,
                        onClick = { choose() },
                        enabled = enabled,
                        colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.muted)
                    )
                    Box(Modifier.weight(1f)) { GenUiNodeView(GenUiNode("text", radio.key + "/l", emptyMap(), radio.children)) }
                }
            }
        }
    }
}

/* ---------------------------- 滑条 / 步进 ---------------------------- */

private fun numberArg(value: Double, step: Double): JsonPrimitive {
    val integral = step >= 1.0 && abs(step - step.roundToInt()) < 1e-9
    return if (integral) JsonPrimitive(value.roundToInt()) else JsonPrimitive((value * 1000).roundToInt() / 1000.0)
}

private fun formatNumber(value: Double, step: Double): String {
    val integral = step >= 1.0 && abs(step - step.roundToInt()) < 1e-9
    return if (integral) value.roundToInt().toString() else ((value * 100).roundToInt() / 100.0).toString().removeSuffix(".0")
}

@Composable
internal fun GenUiSlider(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val events = LocalGenUiEvents.current
    val handler = node.handler("onChange")
    val min = node.number("min") ?: 0.0
    val max = (node.number("max") ?: 100.0).let { if (it <= min) min + 1 else it }
    val step = (node.number("step") ?: if (max - min > 10) 1.0 else 0.0).coerceAtLeast(0.0)
    val external = (node.number("value") ?: min).coerceIn(min, max)
    var local by remember(node.key) { mutableFloatStateOf(external.toFloat()) }
    var dragging by remember(node.key) { mutableStateOf(false) }
    LaunchedEffect(external) { if (!dragging) local = external.toFloat() }
    val steps = if (step > 0) (((max - min) / step).roundToInt() - 1).takeIf { it in 1..200 } ?: 0 else 0
    Column(modifier.fillMaxWidth()) {
        val label = node.string("label")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (label != null) Text(label, style = LocalTextStyle.current, color = colors.ink, modifier = Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            Text(
                formatNumber(local.toDouble(), step) + node.string("unit").orEmpty(),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = colors.accent
            )
        }
        Slider(
            value = local,
            onValueChange = { v ->
                dragging = true
                val snapped = if (step > 0) (min + ((v - min) / step).roundToInt() * step) else v.toDouble()
                if (snapped.toFloat() != local) {
                    local = snapped.toFloat()
                    handler?.let { events.dispatch(it, args(numberArg(snapped, step))) }
                }
            },
            onValueChangeFinished = { dragging = false },
            valueRange = min.toFloat()..max.toFloat(),
            steps = steps,
            enabled = handler != null,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.ink.copy(alpha = 0.1f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}

@Composable
internal fun GenUiStepper(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val events = LocalGenUiEvents.current
    val handler = node.handler("onChange")
    val min = node.number("min") ?: Double.NEGATIVE_INFINITY
    val max = node.number("max") ?: Double.POSITIVE_INFINITY
    val step = (node.number("step") ?: 1.0).takeIf { it > 0 } ?: 1.0
    val external = node.number("value") ?: 0.0
    var pending by remember(node.key) { mutableStateOf<Double?>(null) }
    LaunchedEffect(external) { pending = null }
    val value = pending ?: external
    fun change(next: Double) {
        val clamped = next.coerceIn(min, max)
        if (clamped == value) return
        pending = clamped
        handler?.let { events.dispatch(it, args(numberArg(clamped, step))) }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        node.string("label")?.let { Text(it, style = LocalTextStyle.current, color = colors.ink, modifier = Modifier.weight(1f)) }
            ?: Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(999.dp)).background(colors.ink.copy(alpha = 0.05f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clickable(enabled = handler != null && value > min, role = Role.Button) { change(value - step) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Remove, null, Modifier.size(18.dp), tint = if (value > min) colors.ink else colors.muted.copy(alpha = 0.4f)) }
            Text(
                formatNumber(value, step) + node.string("unit").orEmpty(),
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                color = colors.ink,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            Box(
                Modifier.size(44.dp).clickable(enabled = handler != null && value < max, role = Role.Button) { change(value + step) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp), tint = if (value < max) colors.ink else colors.muted.copy(alpha = 0.4f)) }
        }
    }
}

/* ---------------------------- 输入 ---------------------------- */

/**
 * 输入框：本地文字状态是真源，聚焦期间忽略树里回传的值（防止光标跳、打断输入法组合），
 * 向引擎去抖推送。
 */
@Composable
internal fun GenUiInput(node: GenUiNode, modifier: Modifier) {
    val colors = LocalGenUiColors.current
    val events = LocalGenUiEvents.current
    val handler = node.handler("onChange")
    val numeric = node.string("type") == "number"
    val external = (node.prop("value") as? JsonPrimitive)?.contentOrNull.orEmpty()
    var field by remember(node.key) { mutableStateOf(TextFieldValue(external, TextRange(external.length))) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(external) { if (!focused && field.text != external) field = TextFieldValue(external, TextRange(external.length)) }
    var outgoing by remember(node.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(outgoing) {
        val text = outgoing ?: return@LaunchedEffect
        delay(INPUT_DEBOUNCE_MS)
        val arg = if (numeric) text.toDoubleOrNull()?.let { if (it == Math.floor(it)) JsonPrimitive(it.toLong()) else JsonPrimitive(it) } ?: JsonPrimitive(text) else JsonPrimitive(text)
        handler?.let { events.dispatch(it, args(arg)) }
    }
    val multiline = node.type == "textarea"
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        node.string("label")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
        BasicTextField(
            value = field,
            onValueChange = { next ->
                field = next
                if (next.text != outgoing) outgoing = next.text
            },
            enabled = handler != null,
            singleLine = !multiline,
            minLines = if (multiline) (node.int("rows") ?: 3).coerceIn(2, 8) else 1,
            textStyle = LocalTextStyle.current.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.ink.copy(alpha = 0.05f))
                .then(if (focused) Modifier.border(1.dp, colors.accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp)) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (field.text.isEmpty()) {
                        node.string("placeholder")?.let { Text(it, style = LocalTextStyle.current, color = colors.muted.copy(alpha = 0.7f)) }
                    }
                    inner()
                }
            }
        )
    }
}

private const val INPUT_DEBOUNCE_MS = 120L
