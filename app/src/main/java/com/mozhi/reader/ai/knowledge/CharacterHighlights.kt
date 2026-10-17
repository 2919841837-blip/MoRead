package com.mozhi.reader.ai.knowledge

/**
 * 角色卡「本次更新新增了什么」的纯本地比较。
 *
 * 只标记新增/改写出来的文字，不标记删除：旧内容被删掉时界面上本来就没有可高亮的位置。
 * 字段级 LCS 逐字比较，插入片段之间夹着的 1–2 个字会并进同一段，避免高亮碎成一片。
 */
internal object CharacterHighlights {
    private const val MAX_DIFF_CHARS = 1_200
    private const val SHORT_TEXT = 40
    private const val MERGE_GAP = 2

    fun diff(before: BookCharacter, after: BookCharacter): List<CharacterHighlight> = buildList {
        scalar(HighlightField.IDENTITY, before.attributeValues(CharacterAttributeKind.IDENTITY).firstOrNull(),
            after.attributeValues(CharacterAttributeKind.IDENTITY).firstOrNull())
        scalar(HighlightField.GENDER, before.attributeValues(CharacterAttributeKind.GENDER).firstOrNull(),
            after.attributeValues(CharacterAttributeKind.GENDER).firstOrNull())
        scalar(HighlightField.AGE, before.attributeValues(CharacterAttributeKind.AGE).firstOrNull(),
            after.attributeValues(CharacterAttributeKind.AGE).firstOrNull())
        scalar(HighlightField.APPEARANCE, before.attributeValues(CharacterAttributeKind.APPEARANCE).joinToString("；"),
            after.attributeValues(CharacterAttributeKind.APPEARANCE).joinToString("；"))
        scalar(HighlightField.TAGLINE, before.tagline(), after.tagline())
        scalar(HighlightField.BIO, before.bio(), after.bio())
        scalar(HighlightField.ARC, before.arc(), after.arc())

        if (before.effectiveRole() != after.effectiveRole() && after.effectiveRole() != null) {
            add(CharacterHighlight(HighlightField.ROLE, wholeItem = true,
                before = before.effectiveRole()?.name.orEmpty()))
        }

        val oldTraits = traitsOf(before).associateBy { it.trait }
        traitsOf(after).forEach { trait ->
            val old = oldTraits[trait.trait]
            if (old == null) {
                add(CharacterHighlight(HighlightField.PERSONALITY, key = trait.trait, wholeItem = true))
            } else {
                val spans = diffText(old.detail, trait.detail)
                if (spans.isNotEmpty()) add(CharacterHighlight(HighlightField.PERSONALITY, key = trait.trait,
                    spans = spans, before = old.detail.trim()))
            }
        }

        val oldRelations = relationsOf(before).associateBy { it.target }
        relationsOf(after).forEach { relation ->
            val old = oldRelations[relation.target]
            when {
                old == null -> add(CharacterHighlight(HighlightField.RELATION, key = relation.target, wholeItem = true))
                old.relation.trim() != relation.relation.trim() -> add(CharacterHighlight(HighlightField.RELATION,
                    key = relation.target, wholeItem = true, before = old.relation.trim()))
                else -> {
                    val spans = diffText(old.detail, relation.detail)
                    if (spans.isNotEmpty()) add(CharacterHighlight(HighlightField.RELATION, key = relation.target,
                        spans = spans, before = old.detail.trim()))
                }
            }
        }
    }.filter { it.wholeItem || it.spans.isNotEmpty() }

    private fun MutableList<CharacterHighlight>.scalar(field: HighlightField, before: String?, after: String?) {
        val old = before.orEmpty().trim()
        val new = after.orEmpty().trim()
        if (new.isEmpty() || old == new) return
        val spans = diffText(old, new)
        if (spans.isNotEmpty()) add(CharacterHighlight(field, spans = spans, before = old))
    }

    private fun traitsOf(person: BookCharacter): List<DossierTrait> =
        person.manualTraits() ?: person.dossier?.personality.orEmpty()

    private fun relationsOf(person: BookCharacter): List<DossierRelation> =
        person.manualRelations() ?: person.dossier?.relationships.orEmpty()

    /**
     * [after] 相对 [before] 的新增区间。纯删除返回空；前缀/后缀相同的部分直接跳过，
     * 只对中段跑 LCS，人物简介这种几百字的字段也不会卡。
     */
    fun diffText(before: String, after: String): List<HighlightSpan> {
        val old = before.take(MAX_DIFF_CHARS)
        val new = after.take(MAX_DIFF_CHARS)
        if (old == new || new.isEmpty()) return emptyList()
        if (old.isEmpty()) return listOf(HighlightSpan(0, new.length))

        var prefix = 0
        while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.length - prefix && suffix < new.length - prefix &&
            old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++

        val removed = old.substring(prefix, old.length - suffix)
        val added = new.substring(prefix, new.length - suffix)
        if (added.isEmpty()) return emptyList()
        // 纯插入（旧文本整段都是新文本的前/后缀之外没有别的变化）：中段整块算新增。
        if (removed.isEmpty()) return settle(listOf(HighlightSpan(prefix, prefix + added.length)), new)
        val ranges = insertedRanges(removed, added).map { HighlightSpan(it.first + prefix, it.second + prefix) }
        return settle(ranges, new)
    }

    /** 标准 LCS 回溯：只记录落在 [added]（b）里、没有对应旧字符的位置。 */
    private fun insertedRanges(a: String, b: String): List<Pair<Int, Int>> {
        val n = a.length
        val m = b.length
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val ranges = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        var start = -1
        fun flush(end: Int) {
            if (start >= 0) {
                ranges += start to end
                start = -1
            }
        }
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    flush(j)
                    i++
                    j++
                }
                // 旧文本里的字被删了：与「新增」无关。
                lcs[i + 1][j] >= lcs[i][j + 1] -> i++
                else -> {
                    if (start < 0) start = j
                    j++
                }
            }
        }
        if (j < m) {
            if (start < 0) start = j
            j = m
        }
        flush(j)
        return ranges
    }

    /** 合并挨得很近的片段；整段几乎都变了就直接整块高亮，避免满屏碎屑。 */
    private fun settle(ranges: List<HighlightSpan>, text: String): List<HighlightSpan> {
        val merged = mutableListOf<HighlightSpan>()
        ranges.mapNotNull { span ->
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(0, text.length)
            if (end <= start || text.substring(start, end).isBlank()) null else HighlightSpan(start, end)
        }.sortedBy { it.start }.forEach { span ->
            val last = merged.lastOrNull()
            if (last != null && span.start - last.end <= MERGE_GAP) {
                merged[merged.lastIndex] = HighlightSpan(last.start, maxOf(last.end, span.end))
            } else {
                merged += span
            }
        }
        if (merged.isEmpty()) return emptyList()
        val covered = merged.sumOf { it.end - it.start }
        val whole = if (text.length <= SHORT_TEXT) covered * 2 >= text.length else covered * 5 >= text.length * 3
        return if (whole) listOf(HighlightSpan(0, text.length)) else merged
    }
}

/**
 * 增量更新的范围判断：上次扫到 [CharacterScanBoundary]，这次只看它之后的正文。
 * 名字/别称命中的既有角色才值得再发一次请求；没出现的角色这一轮直接跳过，省掉的就是他们的 LLM 调用。
 */
internal object CharacterIncrementalUpdate {
    /** 新正文起点；null = 没有新正文（例如上次已经扫完整本书）。 */
    fun startOf(chapters: List<Pair<Int, String>>, from: CharacterScanBoundary): Pair<Int, Int>? {
        if (from.isWholeBook || chapters.isEmpty()) return null
        val last = chapters.last()
        return when {
            last.first < from.chapterIndex -> null
            last.first == from.chapterIndex && from.charOffset >= last.second.length -> null
            else -> from.chapterIndex to from.charOffset.coerceAtLeast(0)
        }
    }

    /** [names] 是否出现在边界之后的正文里。范围已由调用方按阅读进度 / 全书裁好。 */
    fun appears(chapters: List<Pair<Int, String>>, from: CharacterScanBoundary, names: List<String>): Boolean {
        if (from.isWholeBook || names.isEmpty()) return false
        val needles = names.filter { it.isNotBlank() }.distinct()
        if (needles.isEmpty()) return false
        return chapters.any { (index, raw) ->
            if (index < from.chapterIndex) return@any false
            val text = if (index == from.chapterIndex) raw.substring(from.charOffset.coerceIn(0, raw.length)) else raw
            needles.any { text.contains(it) }
        }
    }
}
