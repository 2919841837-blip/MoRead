package com.mozhi.reader.core.text

import org.junit.Assert.*
import org.junit.Test

class QuoteMatcherTest {
    private val text = "他推开门，说道：“我回来了……”屋里没人应答。"

    @Test fun exactQuotesKeepTheirOffsets() {
        val span = QuoteMatcher.find(text, "屋里没人应答")!!
        assertEquals("屋里没人应答", text.substring(span.start, span.end))
    }

    @Test fun punctuationWidthQuoteStyleSpacesAndEllipsisDriftMapBackToSourceText() {
        val span = QuoteMatcher.find(text, "说道: \"我回来了...\"")!!
        assertEquals("说道：“我回来了……”", text.substring(span.start, span.end))
    }

    @Test fun inventedTextIsNeverMatched() {
        assertNull(QuoteMatcher.find(text, "他转身离开了屋子"))
        assertNull(QuoteMatcher.bestOverlap(text, "他转身离开了屋子"))
    }

    @Test fun overlapRecoversAQuoteWithExtraWordsButNeedsMostOfIt() {
        val span = QuoteMatcher.bestOverlap(text, "屋里没人应答，只有风声")!!
        assertEquals("屋里没人应答", text.substring(span.start, span.end))
        assertNull(QuoteMatcher.bestOverlap(text, "屋里一片漆黑，没有任何回应与动静"))
    }

}
