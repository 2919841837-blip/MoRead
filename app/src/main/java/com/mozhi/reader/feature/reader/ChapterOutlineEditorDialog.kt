package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.ai.knowledge.ChapterKnowledgeCodec
import com.mozhi.reader.ui.components.MoReadPageDialog

/** 手改章节大纲：沿用模型输出的同一套格式约束，保存后覆盖这一章已存的大纲。 */
@Composable
internal fun ChapterOutlineEditorDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    // 限制只写一份：长度与「不要罗列要点」的规则都复用 codec，不在界面里复制一遍。
    val error = runCatching { ChapterKnowledgeCodec.validateOutline(text) }.exceptionOrNull()?.message
    MoReadPageDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp)) {
                Text(stringResource(R.string.reader_outline_editor_title), style = MaterialTheme.typography.titleLarge)
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        text,
                        { text = it.take(MAX_OUTLINE_EDIT_CHARS) },
                        label = { Text(stringResource(R.string.reader_outline_editor_label)) },
                        minLines = 10,
                        isError = error != null,
                        supportingText = if (error != null) ({ Text(error) }) else null,
                        modifier = Modifier.fillMaxWidth().testTag("outline-editor")
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("outline-editor-cancel")) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        enabled = error == null,
                        onClick = { onSave(text.trim()) },
                        modifier = Modifier.testTag("outline-editor-save")
                    ) { Text(stringResource(R.string.reader_outline_editor_save)) }
                }
            }
        }
    }
}

/** 输入上限留出余量，真正的长度校验交给 codec 给出可读提示。 */
private const val MAX_OUTLINE_EDIT_CHARS = 4_000
