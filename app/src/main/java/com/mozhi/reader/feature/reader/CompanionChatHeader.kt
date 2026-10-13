package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.ui.components.PersonaAvatarImage

@Composable
internal fun CompanionChatHeader(
    persona: PersonaEntity?,
    personas: List<PersonaEntity>,
    bookTitle: String,
    personaMenuExpanded: Boolean,
    isStreaming: Boolean,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onOpenPersonaMenu: () -> Unit,
    onDismissPersonaMenu: () -> Unit,
    onSelectPersona: (Long) -> Unit,
    onNewConversation: () -> Unit,
    onShowConversations: () -> Unit,
    embedded: Boolean = false
) {
    val sidebar = LocalCompanionSidebarVisible.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 全 App 的顶栏都是 8~12dp 起步；这里原来是 4dp，切进聊天页时返回箭头会往左跳一下。
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 顶栏的按钮是浮在纸上的玻璃小件：返回一枚圆钮，右侧会话动作收进一枚胶囊。
        if (!sidebar) HeaderGlass(palette, CircleShape) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (embedded) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = if (embedded) "关闭伴读面板" else "返回",
                    tint = palette.onBackground,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClick = onOpenPersonaMenu)
                .padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PersonaAvatarImage(
                name = persona?.name.orEmpty(),
                avatarPath = persona?.avatarPath,
                modifier = Modifier.size(40.dp)
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = persona?.name ?: "伴读",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        Icons.Outlined.ExpandMore,
                        contentDescription = "切换角色",
                        tint = palette.muted,
                        modifier = Modifier.padding(start = 2.dp).size(18.dp)
                    )
                }
                Text(
                    text = bookTitle.ifBlank { "伴读中" },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(
                expanded = personaMenuExpanded,
                onDismissRequest = onDismissPersonaMenu
            ) {
                personas.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.name) },
                        leadingIcon = {
                            PersonaAvatarImage(
                                name = candidate.name,
                                avatarPath = candidate.avatarPath,
                                modifier = Modifier.size(26.dp)
                            )
                        },
                        onClick = { onSelectPersona(candidate.id) }
                    )
                }
            }
        }
        if (!sidebar) HeaderGlass(palette, RoundedCornerShape(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShowConversations, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = "会话历史",
                        tint = palette.onBackground,
                        modifier = Modifier.size(21.dp)
                    )
                }
                IconButton(
                    onClick = onNewConversation,
                    enabled = !isStreaming,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Outlined.EditNote,
                        contentDescription = "新会话",
                        tint = if (isStreaming) palette.muted else palette.onBackground,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderGlass(
    palette: ReaderPalette,
    shape: androidx.compose.ui.graphics.Shape,
    content: @Composable () -> Unit
) {
    Surface(
        shape = shape,
        color = palette.glassStrong,
        contentColor = palette.onBackground,
        border = BorderStroke(0.5.dp, palette.glassBorder),
        shadowElevation = 3.dp,
        content = content
    )
}
