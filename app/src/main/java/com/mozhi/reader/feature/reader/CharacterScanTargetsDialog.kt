package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.ai.knowledge.CharacterScanTargets
import com.mozhi.reader.ai.knowledge.CharacterTarget
import com.mozhi.reader.ui.components.MoReadPageDialog
import com.mozhi.reader.ui.components.MoReadTextField

/**
 * 点名扫描的名单输入：一行一位，括号里写别称。
 *
 * 单独一个整页弹窗，而不是塞进「提取」那个 AlertDialog —— AlertDialog 的固有测量会和文本框
 * 互相牵制，实机上表现为永不停止的重组（Compose 一直拿不到 idle）。书籍大纲编辑器用的是同一套
 * 有界窗口（[MoReadPageDialog]），输入区的滚动与 IME 避让都由它负责。
 */
@Composable
internal fun CharacterScanTargetsDialog(
    input: String,
    onInputChange: (String) -> Unit,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<CharacterTarget>) -> Unit
) {
    val parsed = remember(input) { CharacterScanTargets.parse(input) }
    MoReadPageDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp)) {
                Text(stringResource(R.string.characters_targets_label), style = MaterialTheme.typography.titleLarge)
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MoReadTextField(
                        value = input,
                        // 上限与解析器的读取上限一致：超出部分解析器根本看不到，不该让用户白白打出来。
                        onValueChange = { onInputChange(it.take(CharacterScanTargets.MAX_INPUT)) },
                        placeholder = stringResource(R.string.characters_targets_placeholder),
                        supporting = when {
                            input.isBlank() -> stringResource(R.string.characters_targets_hint)
                            parsed.rejected.isNotEmpty() -> stringResource(R.string.characters_targets_invalid, parsed.rejected.joinToString("、"))
                            parsed.overflowed -> stringResource(R.string.characters_targets_overflow, CharacterScanTargets.MAX_TARGETS)
                            else -> stringResource(R.string.characters_targets_ready, parsed.targets.size)
                        },
                        isError = parsed.rejected.isNotEmpty() || parsed.overflowed,
                        singleLine = false,
                        minLines = 6,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("character-targets")
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("character-targets-cancel")) {
                        Text(stringResource(R.string.characters_cancel))
                    }
                    Button(
                        enabled = enabled && parsed.targets.isNotEmpty(),
                        onClick = { onConfirm(parsed.targets) },
                        modifier = Modifier.testTag("character-targets-confirm")
                    ) { Text(stringResource(R.string.characters_targets_action)) }
                }
            }
        }
    }
}
