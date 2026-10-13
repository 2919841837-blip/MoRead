package com.mozhi.reader.feature.bookshelf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import com.mozhi.reader.ui.theme.moReadMetrics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.label
import com.mozhi.reader.core.database.entity.isPinned
import com.mozhi.reader.core.database.entity.readState
import com.mozhi.reader.core.library.BookReadSpan
import com.mozhi.reader.core.library.readFraction
import com.mozhi.reader.core.library.readPercent
import com.mozhi.reader.ui.MoReadWindowWidth
import com.mozhi.reader.ui.rememberMoReadWindowWidth
import com.mozhi.reader.ui.theme.MoReadTokens
import java.io.File

@Composable
internal fun BookCover(
    book: BookEntity,
    readSpan: BookReadSpan?,
    modifier: Modifier
) {
    val tablet = rememberMoReadWindowWidth() != MoReadWindowWidth.COMPACT
    val coverFile = remember(book.coverPath) {
        book.coverPath?.let(::File)?.takeIf(File::isFile)
    }
    val progress = readFraction(book, readSpan)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 420),
        label = "book-progress"
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (tablet) 4.dp else moReadMetrics().radiusFor(12)),
        color = coverColor(book.title),
        shadowElevation = 10.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            if (coverFile != null) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                FallbackCover(book = book, modifier = Modifier.fillMaxSize())
            }

            // 左下角玻璃小胶囊进度徽章。
            Surface(
                shape = MoReadTokens.CapsuleShape,
                color = Color.Black.copy(alpha = 0.34f),
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = when (val state = book.readState()) {
                        BookReadState.READING -> "${readPercent(animatedProgress)}%"
                        else -> state.label()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            if (book.isPinned) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.38f),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = "已置顶",
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompactBookArtwork(book: BookEntity, modifier: Modifier) {    val coverFile = remember(book.coverPath) {
        book.coverPath?.let(::File)?.takeIf(File::isFile)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(moReadMetrics().radiusFor(12)),
        color = coverColor(book.title),
        shadowElevation = 7.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            if (coverFile != null) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                FallbackCover(
                    book = book,
                    modifier = Modifier.fillMaxSize(),
                    compact = true
                )
            }
        }
    }
}

/** 封面缺图 fallback（§1）：左侧书脊线 + 竖排书名 + 底部竖排作者。 */
@Composable
private fun FallbackCover(
    book: BookEntity,
    modifier: Modifier,
    compact: Boolean = false
) {
    val base = coverColor(book.title)
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    base.copy(alpha = 0.94f),
                    base,
                    Color.Black.copy(alpha = 0.52f)
                )
            )
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(if (compact) 3.dp else 5.dp)
                .padding(start = if (compact) 1.dp else 2.dp)
                .background(Color.White.copy(alpha = 0.30f))
        )
        VerticalBookTitle(
            text = book.title,
            compact = compact,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = if (compact) 8.dp else 14.dp,
                    end = if (compact) 8.dp else 14.dp
                )
        )
        if (book.author.isNotBlank()) {
            VerticalBookTitle(
                text = book.author,
                compact = true,
                small = true,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = if (compact) 8.dp else 12.dp, bottom = 8.dp)
            )
        }
    }
}

/** 竖排文字：逐字换行模拟直排；超出单列时按传统直排从右往左折成多列。 */
@Composable
private fun VerticalBookTitle(
    text: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
    small: Boolean = false
) {
    val perColumn = if (compact) 6 else 8
    val maxColumns = if (small) 1 else if (compact) 2 else 3
    val capacity = perColumn * maxColumns
    val display = if (text.length > capacity) text.take(capacity - 1) + "…" else text
    val columns = display.chunked(perColumn)
    val style = when {
        small -> MaterialTheme.typography.labelSmall
        compact -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.titleMedium
    }
    val lineHeight = when {
        small -> MaterialTheme.typography.labelSmall.fontSize * 1.25f
        compact -> MaterialTheme.typography.titleSmall.fontSize * 1.3f
        else -> MaterialTheme.typography.titleMedium.fontSize * 1.3f
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)
    ) {
        // 直排阅读顺序是从右往左：第一列排在最右边。
        columns.asReversed().forEach { column ->
            Text(
                text = column.toCharArray().joinToString("\n"),
                style = style,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = if (small) 0.72f else 0.95f),
                textAlign = TextAlign.Center,
                lineHeight = lineHeight
            )
        }
    }
}

/** 无封面时的占位底色：中性深灰阶，按书名 hash 取一档，黑白主题下不跳色。 */
internal fun coverColor(title: String): Color {
    val palette = listOf(
        Color(0xFF2B2B2B),
        Color(0xFF3A3A3A),
        Color(0xFF484848),
        Color(0xFF565656),
        Color(0xFF333333),
        Color(0xFF414141),
        Color(0xFF4F4F4F)
    )
    return palette[(title.hashCode() and Int.MAX_VALUE) % palette.size]
}
