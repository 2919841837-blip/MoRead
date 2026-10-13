package com.mozhi.reader.feature.reader

import com.mozhi.reader.ai.knowledge.KnowledgeProgress
import com.mozhi.reader.ai.knowledge.KnowledgeTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderKnowledgeTaskOrbTest {
    @Test fun `running tasks always show and finished ones only while lingering`() {
        assertEquals(KnowledgeOrbPhase.HIDDEN, knowledgeOrbPhase(null, lingering = true))
        assertEquals(KnowledgeOrbPhase.RUNNING, knowledgeOrbPhase(KnowledgeTaskState(active = true), lingering = false))
        assertEquals(KnowledgeOrbPhase.DONE, knowledgeOrbPhase(KnowledgeTaskState(progress = "已保存"), lingering = true))
        assertEquals(KnowledgeOrbPhase.HIDDEN, knowledgeOrbPhase(KnowledgeTaskState(progress = "已保存"), lingering = false))
        assertEquals(KnowledgeOrbPhase.FAILED, knowledgeOrbPhase(KnowledgeTaskState(error = "超时"), lingering = true))
    }

    @Test fun `a task the reader stopped disappears at once`() {
        assertEquals(KnowledgeOrbPhase.HIDDEN, knowledgeOrbPhase(KnowledgeTaskState(stopped = true), lingering = true))
    }

    @Test fun `badge counts profiles once the roster is known`() {
        assertNull(knowledgeOrbBadge(null))
        assertNull(knowledgeOrbBadge(KnowledgeProgress(KnowledgeProgress.Stage.ROSTER)))
        assertEquals("7/18", knowledgeOrbBadge(KnowledgeProgress(KnowledgeProgress.Stage.PROFILES, 7, 18)))
    }
}
