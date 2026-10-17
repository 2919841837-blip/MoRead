package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.ai.knowledge.BookCharacter
import com.mozhi.reader.ai.knowledge.CharacterAttributeDraft
import com.mozhi.reader.ai.knowledge.CharacterHighlight
import com.mozhi.reader.ai.knowledge.CharacterAttributeDraftCodec
import com.mozhi.reader.ai.knowledge.CharacterRole
import com.mozhi.reader.ai.knowledge.HighlightField
import com.mozhi.reader.ai.knowledge.ManualCharacterAttributes
import com.mozhi.reader.ui.components.MoReadPageDialog
import com.mozhi.reader.ui.components.MoReadTextField
import com.mozhi.reader.ui.components.TextMarker

/** 草稿要跨旋转保留：rememberSaveable 只认基本类型，这里把每个字段手工列出来（顺序与数据类一致）。 */
private val CharacterAttributeDraftSaver = listSaver<CharacterAttributeDraft, String>(
    save = { draft -> listOf(draft.identity, draft.gender, draft.age, draft.appearance, draft.tagline,
        draft.bio, draft.aliases, draft.personality, draft.role?.name.orEmpty(),
        draft.arc, draft.relations) },
    restore = { values -> CharacterAttributeDraft(values[0], values[1], values[2], values[3], values[4], values[5],
        values[6], values[7], values[8].takeIf { it.isNotEmpty() }?.let { role -> CharacterRole.valueOf(role) },
        values[9], values[10]) })

/**
 * 新增/编辑一位人物：姓名之后按档案页的展示顺序排列字段——主次、别名、一句话简介、
 * 性别/年龄/身份、小传、性格、外貌、成长变化、社会关系。字段名一律沿用档案页的栏目名。
 *
 * 手写过的字段从此由用户接管（[ManualCharacterAttributes]），重扫不会覆盖；留空则表示要求不显示，
 * 重扫也不会填回。社会关系一行一条「对象（关系）：说明」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookCharacterEditorDialog(person: BookCharacter?, people: List<BookCharacter>, onDismiss: () -> Unit,
    onSave: (String?, String, ManualCharacterAttributes?, Boolean) -> Unit,
    /** 点按字段里的 ★：只撤掉这条「新增」标记，内容不动。 */
    onCancelHighlight: (HighlightField, String) -> Unit = { _, _ -> },
    /** 右上角 ☆ 确认后调用：一次清掉这个人所有 ★ 标记。 */
    onClearHighlights: () -> Unit = {}) {
    var name by rememberSaveable(person?.identity) { mutableStateOf(person?.name.orEmpty()) }
    val baseline = remember(person) { CharacterAttributeDraftCodec.baseline(person) }
    var draft by rememberSaveable(person?.identity, stateSaver = CharacterAttributeDraftSaver) { mutableStateOf(baseline) }
    var departed by rememberSaveable(person?.identity) { mutableStateOf(person?.departed ?: false) }
    var clearingMarks by rememberSaveable(person?.identity) { mutableStateOf(false) }
    val clearMarksDescription = stringResource(R.string.dossier_clear_marks_cd)
    val manual = person?.manualAttributes
    val duplicate = people.any { it.identity != person?.identity && (it.name.equals(name.trim(), true) || it.identity.equals(name.trim(), true)) }
    MoReadPageDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (person == null) R.string.dossier_editor_new else R.string.dossier_editor_edit),
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (person?.highlights?.isNotEmpty() == true) {
                        TextButton(onClick = { clearingMarks = true }, modifier = Modifier.testTag("character-clear-marks")) {
                            Text("☆", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { contentDescription = clearMarksDescription })
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MoReadTextField(name, { name = it.take(80) }, label = stringResource(R.string.dossier_name), isError = duplicate,
                        supporting = stringResource(R.string.dossier_duplicate_name).takeIf { duplicate },
                        modifier = Modifier.testTag("character-name"))
                    Text(stringResource(R.string.dossier_basic_title), style = MaterialTheme.typography.labelLarge)
                    CharacterRoleRow(draft.role, person?.role,
                        updated = person?.highlightFor(HighlightField.ROLE) != null && draft.role == baseline.role,
                        onClearUpdate = { onCancelHighlight(HighlightField.ROLE, "") }) { draft = draft.copy(role = it) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.characters_departed_editor), style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f))
                        Switch(checked = departed, onCheckedChange = { departed = it },
                            modifier = Modifier.testTag("character-departed"))
                    }
                    // 顺序与档案页一致：别名/一句话简介 → 性别/年龄/身份 → 小传 → 性格 → 外貌 → 成长变化 → 关系。
                    AttributeField(R.string.dossier_aliases_label, draft.aliases, { draft = draft.copy(aliases = it) },
                        owned = manual?.aliases != null, supporting = stringResource(R.string.dossier_alias_hint))
                    AttributeField(R.string.dossier_tagline, draft.tagline, { draft = draft.copy(tagline = it) },
                        owned = manual?.tagline != null,
                        markers = textMarkers(person, HighlightField.TAGLINE, draft.tagline, baseline.tagline),
                        onMarkerClick = { onCancelHighlight(HighlightField.TAGLINE, "") })
                    AttributeField(R.string.dossier_gender, draft.gender, { draft = draft.copy(gender = it) },
                        owned = manual?.gender != null,
                        markers = textMarkers(person, HighlightField.GENDER, draft.gender, baseline.gender),
                        onMarkerClick = { onCancelHighlight(HighlightField.GENDER, "") })
                    AttributeField(R.string.dossier_age, draft.age, { draft = draft.copy(age = it) },
                        owned = manual?.age != null,
                        markers = textMarkers(person, HighlightField.AGE, draft.age, baseline.age),
                        onMarkerClick = { onCancelHighlight(HighlightField.AGE, "") })
                    AttributeField(R.string.dossier_identity, draft.identity, { draft = draft.copy(identity = it) },
                        owned = manual?.identity != null, modifier = Modifier.testTag("character-identity"),
                        markers = textMarkers(person, HighlightField.IDENTITY, draft.identity, baseline.identity),
                        onMarkerClick = { onCancelHighlight(HighlightField.IDENTITY, "") })
                    AttributeField(R.string.dossier_bio, draft.bio, { draft = draft.copy(bio = it) },
                        owned = manual?.bio != null, minLines = 4, modifier = Modifier.testTag("character-bio"),
                        markers = textMarkers(person, HighlightField.BIO, draft.bio, baseline.bio),
                        onMarkerClick = { onCancelHighlight(HighlightField.BIO, "") })
                    AttributeField(R.string.dossier_personality, draft.personality, { draft = draft.copy(personality = it) },
                        owned = manual?.personality != null,
                        minLines = 3, supporting = stringResource(R.string.dossier_personality_hint),
                        markers = entryMarkers(person, HighlightField.PERSONALITY, draft.personality, baseline.personality),
                        onMarkerClick = { marker -> entryKeyAt(person, HighlightField.PERSONALITY, draft.personality,
                            baseline.personality, marker.offset)?.let { onCancelHighlight(HighlightField.PERSONALITY, it) } })
                    AttributeField(R.string.dossier_appearance, draft.appearance, { draft = draft.copy(appearance = it) },
                        owned = manual?.appearance != null, minLines = 3,
                        markers = textMarkers(person, HighlightField.APPEARANCE, draft.appearance, baseline.appearance),
                        onMarkerClick = { onCancelHighlight(HighlightField.APPEARANCE, "") })
                    AttributeField(R.string.dossier_arc, draft.arc, { draft = draft.copy(arc = it) },
                        owned = manual?.arc != null, minLines = 2, supporting = stringResource(R.string.dossier_arc_hint),
                        modifier = Modifier.testTag("character-arc"),
                        markers = textMarkers(person, HighlightField.ARC, draft.arc, baseline.arc),
                        onMarkerClick = { onCancelHighlight(HighlightField.ARC, "") })
                    AttributeField(R.string.dossier_relations, draft.relations, { draft = draft.copy(relations = it) },
                        owned = manual?.relationships != null,
                        minLines = 3, supporting = stringResource(R.string.dossier_relations_hint),
                        modifier = Modifier.testTag("character-relations"),
                        markers = entryMarkers(person, HighlightField.RELATION, draft.relations, baseline.relations),
                        onMarkerClick = { marker -> entryKeyAt(person, HighlightField.RELATION, draft.relations,
                            baseline.relations, marker.offset)?.let { onCancelHighlight(HighlightField.RELATION, it) } })
                    Text(stringResource(R.string.dossier_attr_hint), style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Button(enabled = name.isNotBlank() && !duplicate, onClick = {
                        onSave(person?.identity, name.trim(), CharacterAttributeDraftCodec.merge(person, draft), departed)
                    }) { Text(stringResource(R.string.dossier_save)) }
                }
            }
        }
    }
    if (clearingMarks) {
        AlertDialog(onDismissRequest = { clearingMarks = false },
            title = { Text(stringResource(R.string.dossier_clear_marks_title)) },
            text = { Text(stringResource(R.string.dossier_clear_marks_body)) },
            confirmButton = { TextButton(onClick = {
                clearingMarks = false
                onClearHighlights()
            }) { Text(stringResource(R.string.dossier_clear_marks_confirm)) } },
            dismissButton = { TextButton(onClick = { clearingMarks = false }) {
                Text(stringResource(R.string.action_cancel))
            } })
    }
}

/** 一个基本属性：接管中的字段标「手写」；[markers] 是字段内可点掉的 ★。 */
@Composable
private fun AttributeField(label: Int, value: String, onValueChange: (String) -> Unit, owned: Boolean,
    modifier: Modifier = Modifier, minLines: Int = 1, supporting: String? = null,
    markers: List<TextMarker> = emptyList(), onMarkerClick: ((TextMarker) -> Unit)? = null) {
    // minLines 只管最小高度，行数由单行开关决定：单行字段按回车不该换行，多行字段才铺成方框。
    MoReadTextField(value, onValueChange, label = stringResource(label) +
        if (owned) " · " + stringResource(R.string.dossier_attr_manual) else "",
        supporting = supporting, singleLine = minLines <= 1, minLines = minLines, modifier = modifier,
        markers = markers, onMarkerClick = onMarkerClick)
}

/**
 * 字段级标记：草稿仍是打开编辑器时的那份文本才显示 —— 高亮的锚点按那份文本计算，
 * 用户一改字段，坐标就不再可靠，先藏起来最稳（保存时高亮本来也会按接管字段清掉）。
 */
private fun textMarkers(person: BookCharacter?, field: HighlightField, draft: String, baseline: String): List<TextMarker> {
    if (person == null || draft != baseline) return emptyList()
    val highlight = person.highlightFor(field) ?: return emptyList()
    val offsets = if (highlight.wholeItem) listOf(0) else highlight.spans.map { it.start }
    return offsets.map { it.coerceIn(0, draft.length) }.distinct().map(::TextMarker)
}

/**
 * 性格/关系的标记落在每个条目所在行的行首；[CharacterHighlight.key] 就是条目名。
 * 同一字段可能有多条高亮（本次新增了好几个性格/关系），所以不能按 key="" 取第一条。
 */
internal fun entryMarkers(person: BookCharacter?, field: HighlightField, draft: String, baseline: String): List<TextMarker> {
    if (person == null || draft != baseline) return emptyList()
    return person.highlights.filter { it.field == field }
        .mapNotNull { entryLine(draft, it) }.distinct().map(::TextMarker)
}

/** 点掉的到底是哪一条：用 ★ 的位置反查条目，避免同字段的多条高亮互相误删。 */
internal fun entryKeyAt(person: BookCharacter?, field: HighlightField, draft: String, baseline: String,
    offset: Int): String? {
    if (person == null || draft != baseline) return null
    return person.highlights.filter { it.field == field }
        .firstOrNull { entryLine(draft, it) == offset }?.key
}

private fun entryLine(text: String, highlight: CharacterHighlight): Int? =
    lineStart(text, highlight.key) ?: 0.takeIf { highlight.wholeItem }

/** 「性格词（说明）」/「对象（关系）：说明」的行首偏移；括号与冒号全角半角都认。 */
private fun lineStart(text: String, key: String): Int? {
    if (key.isBlank()) return null
    var offset = 0
    text.split('\n').forEach { line ->
        val head = line.trimStart().substringBefore('（').substringBefore('(')
            .substringBefore('：').substringBefore(':').trim()
        if (head == key.trim()) return offset + line.length - line.trimStart().length
        offset += line.length + 1
    }
    return null
}

/**
 * 主次：四档「跟随 AI / 主角 / 主要 / 配角」。档位**就是**所有权（「跟随 AI」= 交回模型），
 * 所以它不跟 AI 原值比较，也不另外给「恢复」按钮 —— 选一个与 AI 当前值相同的档位就是「别再改」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterRoleRow(role: CharacterRole?, aiRole: CharacterRole?, updated: Boolean = false,
    onClearUpdate: () -> Unit = {}, onPick: (CharacterRole?) -> Unit) {
    Column {
        Text(stringResource(R.string.dossier_role_label), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = role == null, onClick = { onPick(null) }, label = { Text(stringResource(R.string.dossier_role_auto)) },
                modifier = Modifier.testTag("character-role-auto"))
            listOf(CharacterRole.PROTAGONIST, CharacterRole.MAJOR, CharacterRole.SUPPORTING).forEach { option ->
                FilterChip(selected = role == option, onClick = { onPick(option) }, label = { Text(characterRoleBadge(option)) },
                    modifier = Modifier.testTag("character-role-${option.name.lowercase()}"))
            }
            if (updated) Surface(onClick = onClearUpdate, shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.testTag("character-role-updated")) {
                Text("★", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        if (aiRole != null) Text(stringResource(R.string.dossier_role_ai, characterRoleBadge(aiRole)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 4.dp))
    }
}
