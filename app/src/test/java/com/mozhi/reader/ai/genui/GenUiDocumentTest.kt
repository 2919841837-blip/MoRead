package com.mozhi.reader.ai.genui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenUiDocumentTest {

    @Test
    fun plainMarkdownStaysOneSegmentWithoutIslands() {
        val source = "第一段，提到 a < b 与 `{x}`。\n\n- 列表项\n- 第二项"
        val doc = GenUiDocument.parse(source)
        assertFalse(doc.isInteractive)
        assertEquals(source, (doc.segments.single() as GenUiSegment.Markdown).text)
    }

    @Test
    fun statementsAreInvisibleAndIslandSplitsTheProse() {
        val source = """
            开头一句。

            {@body const [n,setN] = useState(3)}
            <card>
              <slider value={n} onChange={setN} min={1} max={9}/>
            </card>

            结尾一句。
        """.trimIndent()
        val doc = GenUiDocument.parse(source)
        assertEquals(listOf("Markdown", "Island", "Markdown"), doc.segments.map { it::class.simpleName })
        assertEquals("const [n,setN] = useState(3)", doc.statements.single().code)
        val card = doc.islands.single().nodes.single() as DilElement
        assertEquals("card", card.name)
        val slider = card.children.filterIsInstance<DilElement>().single()
        assertEquals(DilAttrKind.EXPR, slider.attr("value")!!.kind)
        assertEquals("1", slider.attr("min")!!.value)
        assertTrue((doc.segments.last() as GenUiSegment.Markdown).text.contains("结尾一句"))
    }

    @Test
    fun htmlTableWithoutStatementsIsLeftToTheMarkdownRenderer() {
        val source = "对比如下：\n<table><tr><td>甲</td></tr></table>\n完。"
        val doc = GenUiDocument.parse(source)
        assertFalse(doc.isInteractive)
    }

    @Test
    fun sharedHtmlTagsStartIslandsOnceTheDocumentHasStatements() {
        val source = "{@body const [x,setX] = useState(false)}\n<button onClick={() => setX(true)}>点我</button>"
        val doc = GenUiDocument.parse(source)
        assertEquals("button", (doc.islands.single().nodes.single() as DilElement).name)
    }

    @Test
    fun codeContentIsOpaque() {
        val source = "<card>\n<code>for i := 0; i < n; i++ { wg.Add(1) }</code>\n</card>"
        val doc = GenUiDocument.parse(source)
        val code = (doc.islands.single().nodes.single() as DilElement).children.filterIsInstance<DilElement>().single()
        assertEquals("code", code.name)
        assertEquals("for i := 0; i < n; i++ { wg.Add(1) }", (code.children.single() as DilText).value)
    }

    @Test
    fun wrapperCodeFenceIsStripped() {
        val source = "```html\n{@body const [a,setA] = useState(1)}\n<card><text>{a}</text></card>\n```"
        val doc = GenUiDocument.parse(source)
        assertTrue(doc.isInteractive)
        assertTrue(doc.diagnostics.any { it.code == "stripped_code_fence" })
        assertTrue(doc.segments.none { it is GenUiSegment.Markdown && it.text.contains("```") })
    }

    @Test
    fun realCodeBlocksStayCodeBlocks() {
        val source = "示例：\n```js\nconst a = {b: 1}\n```"
        val doc = GenUiDocument.parse(source)
        assertFalse(doc.isInteractive)
        assertTrue((doc.segments.single() as GenUiSegment.Markdown).text.contains("```js"))
    }

    @Test
    fun statementMissingItsBraceOnlyBreaksItsOwnLine() {
        val source = "{@body const T = {a: 1, b: 2}\n<card><text>后面的内容</text></card>\n收尾。"
        val doc = GenUiDocument.parse(source)
        assertEquals("const T = {a: 1, b: 2}", doc.statements.single().code)
        assertTrue(doc.diagnostics.any { it.code == "truncated_statement" })
        assertEquals(1, doc.islands.size)
        assertTrue(doc.segments.last() is GenUiSegment.Markdown)
    }

    @Test
    fun multiLineStatementIsAcceptedWhenNothingElseStartsInside() {
        val source = "{@body const ROWS = [\n  {a: 1},\n  {a: 2}\n]}\n<card><text>{ROWS.length}</text></card>"
        val doc = GenUiDocument.parse(source)
        assertTrue(doc.statements.single().code.startsWith("const ROWS = ["))
        assertTrue(doc.statements.single().code.endsWith("]"))
        assertEquals(1, doc.islands.size)
    }

    @Test
    fun unfinishedStatementWaitsWhileStreamingAndTruncatesWhenDone() {
        val partial = "前言\n{@body const T = {a: 1,"
        assertTrue(GenUiDocument.parse(partial, complete = false).statements.isEmpty())
        assertEquals("const T = {a: 1,", GenUiDocument.parse(partial, complete = true).statements.single().code)
    }

    @Test
    fun halfWrittenTagOnTheLastLineIsHeldBackWhileStreaming() {
        val doc = GenUiDocument.parse("一段话。\n<car", complete = false)
        assertEquals("一段话。", (doc.segments.single() as GenUiSegment.Markdown).text)
        val statement = GenUiDocument.parse("一段话。\n{@bo", complete = false)
        assertEquals("一段话。", (statement.segments.single() as GenUiSegment.Markdown).text)
    }

    @Test
    fun streamingContainerAppearsOnlyOnceItHasContent() {
        val empty = GenUiDocument.parse("<card>\n  <text>", complete = false)
        assertFalse(empty.isInteractive)
        val growing = GenUiDocument.parse("<card>\n  <text>你好", complete = false)
        val card = growing.islands.single().nodes.single() as DilElement
        assertFalse(growing.islands.single().complete)
        assertEquals("你好", ((card.children.filterIsInstance<DilElement>().single()).children.single() as DilText).value)
    }

    @Test
    fun adjacentIslandsSeparatedByBlankLinesAndStatementsMerge() {
        val source = "<card><text>一</text></card>\n\n{@body const k = 1}\n<card><text>二</text></card>\n\n正文"
        val doc = GenUiDocument.parse(source)
        assertEquals(2, doc.islands.single().nodes.size)
        assertEquals(1, doc.statements.size)
    }

    @Test
    fun topLevelControlBlockKeepsItsMarkdown() {
        val source = "{@body const [ok,setOk] = useState(false)}\n{#if ok}\n**答对了！**\n{:else}\n再想想。\n{/if}"
        val block = GenUiDocument.parse(source).islands.single().nodes.single() as DilIf
        assertEquals(listOf("ok", null), block.branches.map { it.condition })
        assertTrue(block.branches[0].body.single() is DilMarkdown)
    }

    @Test
    fun elementLeftOpenInsideIfIsClosedByTheBlockEnd() {
        val source = "{@body const s = true}\n<card>{#if s}<text>a{/if}<text>b</text></card>"
        val card = GenUiDocument.parse(source).islands.single().nodes.single() as DilElement
        val block = card.children[0] as DilIf
        assertEquals("text", (block.branches.single().body.single() as DilElement).name)
        assertEquals("text", (card.children[1] as DilElement).name)
    }

    @Test
    fun eachHeadSupportsDestructuringAndIndex() {
        val source = "{@body const rows = []}\n<list>{#each rows as {name, score}, idx (name)}<list-item>{name}</list-item>{/each}</list>"
        val list = GenUiDocument.parse(source).islands.single().nodes.single() as DilElement
        val each = list.children.single() as DilEach
        assertEquals("rows", each.list)
        assertEquals("{name, score}", each.item)
        assertEquals("idx", each.index)
    }

    @Test
    fun aliasesAndMisspelledHostTagsResolve() {
        val source = "<segmented-control value={v} onChange={setV} options={[]}/>\n\n<Qoute chapter={1} text=\"x\"/>"
        val doc = GenUiDocument.parse(source)
        val names = doc.islands.flatMap { it.nodes }.map { (it as DilElement).name }
        assertEquals(listOf("segmented", "Quote"), names)
    }

    @Test
    fun appBlockContentIsKeptRaw() {
        val source = "<AppBlock title=\"鼓\">\n<div>{not js}</div><script>if(a<b){}</script>\n</AppBlock>\n结束"
        val doc = GenUiDocument.parse(source)
        val raw = doc.islands.single().nodes.single() as DilRaw
        assertTrue(raw.complete)
        assertTrue(raw.content.contains("<script>if(a<b){}</script>"))
        assertTrue(doc.segments.last() is GenUiSegment.Markdown)
    }

    @Test
    fun voiceMarkersAndCitationsStayInMarkdown() {
        val source = "[语音] 你来啦\n〔原文 第3章〕「风起了」\n<card><text>x</text></card>"
        val doc = GenUiDocument.parse(source)
        val md = (doc.segments.first() as GenUiSegment.Markdown).text
        assertTrue(md.contains("[语音]"))
        assertTrue(md.contains("〔原文 第3章〕"))
    }
}
