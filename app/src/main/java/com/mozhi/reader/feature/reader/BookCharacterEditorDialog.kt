package com.mozhi.reader.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.ai.knowledge.BookCharacter
import com.mozhi.reader.ai.knowledge.CharacterAttributeDraft
import com.mozhi.reader.ai.knowledge.CharacterAttributeDraftCodec
import com.mozhi.reader.ai.knowledge.CharacterRole
import com.mozhi.reader.ai.knowledge.ManualCharacterAttributes
import com.mozhi.reader.ui.components.MoReadPageDialog
import com.mozhi.reader.ui.components.MoReadTextField

/** 草稿要跨旋转保留：rememberSaveable 只认基本类型，这里把每个字段手工列出来（顺序与数据类一致）。 */
private val CharacterAttributeDraftSaver = listSaver<CharacterAttributeDraft, String>(
    save = { draft -> listOf(draft.identity, draft.gender, draft.age, draft.appearance, draft.tagline,
        draft.bio, draft.aliases, draft.personality, draft.role?.name.orEmpty(),
        draft.experiences, draft.arc, draft.relations) },
    restore = { values -> CharacterAttributeDraft(values[0], values[1], values[2], values[3], values[4], values[5],
        values[6], values[7], values[8].takeIf { it.isNotEmpty() }?.let { role -> CharacterRole.valueOf(role) },
        values[9], values[10], values[11]) })

/**
 * 新增/编辑一位人物：姓名、基本属性（主次/身份/性别/年龄/外貌/别名/性格）与档案三项长文
 * （人物经历/成长变化/社会关系），最后是一句话简介与小传。字段名一律沿用档案页的栏目名。
 *
 * 手写过的字段从此由用户接管（[ManualCharacterAttributes]），重扫不会覆盖；「恢复 AI 内容」把文本填回
 * 模型原值，保存时这一项自然交回 AI；留空则表示要求不显示，重扫也不会填回。
 * 经历一行一条（行首可写「第 3 章」），社会关系一行一条「对象（关系）：说明」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookCharacterEditorDialog(person: BookCharacter?, people: List<BookCharacter>, onDismiss: () -> Unit,
    onSave: (String?, String, ManualCharacterAttributes?) -> Unit) {
    var name by rememberSaveable(person?.identity) { mutableStateOf(person?.name.orEmpty()) }
    val baseline = remember(person) { CharacterAttributeDraftCodec.baseline(person) }
    val ai = remember(person) { CharacterAttributeDraftCodec.aiBaseline(person) }
    var draft by rememberSaveable(person?.identity, stateSaver = CharacterAttributeDraftSaver) { mutableStateOf(baseline) }
    val manual = person?.manualAttributes
    val duplicate = people.any { it.identity != person?.identity && (it.name.equals(name.trim(), true) || it.identity.equals(name.trim(), true)) }
    MoReadPageDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp)) {
                Text(stringResource(if (person == null) R.string.dossier_editor_new else R.string.dossier_editor_edit),
                    style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MoReadTextField(name, { name = it.take(80) }, label = stringResource(R.string.dossier_name), isError = duplicate,
                        supporting = stringResource(R.string.dossier_duplicate_name).takeIf { duplicate },
                        modifier = Modifier.testTag("character-name"))
                    Text(stringResource(R.string.dossier_basic_title), style = MaterialTheme.typography.labelLarge)
                    CharacterRoleRow(draft.role, person?.role) { draft = draft.copy(role = it) }
                    AttributeField(R.string.dossier_identity, draft.identity, { draft = draft.copy(identity = it) },
                        owned = manual?.identity != null, restore = ai.identity, onRestore = { draft = draft.copy(identity = it) },
                        modifier = Modifier.testTag("character-identity"))
                    AttributeField(R.string.dossier_gender, draft.gender, { draft = draft.copy(gender = it) },
                        owned = manual?.gender != null, restore = ai.gender, onRestore = { draft = draft.copy(gender = it) })
                    AttributeField(R.string.dossier_age, draft.age, { draft = draft.copy(age = it) },
                        owned = manual?.age != null, restore = ai.age, onRestore = { draft = draft.copy(age = it) })
                    AttributeField(R.string.dossier_appearance, draft.appearance, { draft = draft.copy(appearance = it) },
                        owned = manual?.appearance != null, restore = ai.appearance, onRestore = { draft = draft.copy(appearance = it) },
                        minLines = 3)
                    AttributeField(R.string.dossier_aliases_label, draft.aliases, { draft = draft.copy(aliases = it) },
                        owned = manual?.aliases != null, restore = ai.aliases, onRestore = { draft = draft.copy(aliases = it) },
                        supporting = stringResource(R.string.dossier_alias_hint))
                    AttributeField(R.string.dossier_personality, draft.personality, { draft = draft.copy(personality = it) },
                        owned = manual?.personality != null, restore = ai.personality, onRestore = { draft = draft.copy(personality = it) },
                        minLines = 3, supporting = stringResource(R.string.dossier_personality_hint))
                    AttributeField(R.string.dossier_timeline, draft.experiences, { draft = draft.copy(experiences = it) },
                        owned = manual?.experiences != null, restore = ai.experiences, onRestore = { draft = draft.copy(experiences = it) },
                        minLines = 6, supporting = stringResource(R.string.dossier_experiences_hint),
                        modifier = Modifier.testTag("character-experiences"))
                    AttributeField(R.string.dossier_arc, draft.arc, { draft = draft.copy(arc = it) },
                        owned = manual?.arc != null, restore = ai.arc, onRestore = { draft = draft.copy(arc = it) },
                        minLines = 2, supporting = stringResource(R.string.dossier_arc_hint),
                        modifier = Modifier.testTag("character-arc"))
                    AttributeField(R.string.dossier_relations, draft.relations, { draft = draft.copy(relations = it) },
                        owned = manual?.relationships != null, restore = ai.relations, onRestore = { draft = draft.copy(relations = it) },
                        minLines = 3, supporting = stringResource(R.string.dossier_relations_hint),
                        modifier = Modifier.testTag("character-relations"))
                    AttributeField(R.string.dossier_tagline, draft.tagline, { draft = draft.copy(tagline = it) },
                        owned = manual?.tagline != null, restore = ai.tagline, onRestore = { draft = draft.copy(tagline = it) })
                    AttributeField(R.string.dossier_bio, draft.bio, { draft = draft.copy(bio = it) },
                        owned = manual?.bio != null, restore = ai.bio, onRestore = { draft = draft.copy(bio = it) },
                        minLines = 4)
                    Text(stringResource(R.string.dossier_attr_hint), style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Button(enabled = name.isNotBlank() && !duplicate, onClick = {
                        onSave(person?.identity, name.trim(), CharacterAttributeDraftCodec.merge(person, draft))
                    }) { Text(stringResource(R.string.dossier_save)) }
                }
            }
        }
    }
}

/** 一个基本属性：接管中的字段标「手写」，并给出「恢复 AI 内容」把文本填回模型原值。 */
@Composable
private fun AttributeField(label: Int, value: String, onValueChange: (String) -> Unit, owned: Boolean,
    restore: String, onRestore: (String) -> Unit, modifier: Modifier = Modifier, minLines: Int = 1, supporting: String? = null) {
    // minLines 只管最小高度，行数由单行开关决定：单行字段按回车不该换行，多行字段才铺成方框。
    MoReadTextField(value, onValueChange, label = stringResource(label) +
        if (owned) " · " + stringResource(R.string.dossier_attr_manual) else "",
        supporting = supporting, singleLine = minLines <= 1, minLines = minLines, modifier = modifier,
        trailing = if (owned) ({ Text(stringResource(R.string.dossier_attr_restore), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onRestore(restore) }.padding(4.dp)) }) else null)
}

/**
 * 主次：四档「跟随 AI / 主角 / 主要 / 配角」。档位**就是**所有权（「跟随 AI」= 交回模型），
 * 所以它不跟 AI 原值比较，也不另外给「恢复」按钮 —— 选一个与 AI 当前值相同的档位就是「别再改」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterRoleRow(role: CharacterRole?, aiRole: CharacterRole?, onPick: (CharacterRole?) -> Unit) {
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
        }
        if (aiRole != null) Text(stringResource(R.string.dossier_role_ai, characterRoleBadge(aiRole)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 4.dp))
    }
}
