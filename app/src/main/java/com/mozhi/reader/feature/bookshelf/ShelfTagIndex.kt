package com.mozhi.reader.feature.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.ui.components.shelfTagColor

/**
 * 标签不再是一排带色点的描边胶囊，而是像书的索引页签：「# 名字」一行排开，
 * 井号用标签色、名字用墨；选中的那几个名字加深，下沿一道同色短线。
 */
@Composable
internal fun ShelfTagIndex(
    tags: List<BookTagEntity>,
    selectedTagIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (tags.isEmpty()) return
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(tags, key = BookTagEntity::id) { tag ->
            val selected = tag.id in selectedTagIds
            val tint = shelfTagColor(tag.colorTag)
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onToggle(tag.id) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row {
                    Text(
                        text = "#",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (selected || selectedTagIds.isEmpty()) tint else tint.copy(alpha = 0.45f)
                    )
                    Text(
                        text = tag.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) ink else muted,
                        modifier = Modifier.padding(start = 3.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .drawBehind { if (selected) drawRect(tint) }
                )
            }
        }
        if (selectedTagIds.isNotEmpty()) {
            item(key = "clear") {
                Text(
                    text = stringResource(R.string.shelf_tags_clear),
                    style = MaterialTheme.typography.titleSmall,
                    color = muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onClear)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }
}
