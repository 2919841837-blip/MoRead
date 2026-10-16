package com.mozhi.reader.feature.reader

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.R
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.ui.theme.MoReadTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnnotationEmptySendUiTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun emptyComposerInvitesCurrentCompanionAndRequiresAnAvailablePersona() {
        val annotation = AnnotationEntity(id = 4, bookId = 1, chapterIndex = 2, startCharOffset = 0, endCharOffset = 12,
            selectedText = "雨停了，灯塔的光依然亮着。", createdAt = 1)
        val persona = PersonaEntity(id = 3, name = "知秋", personality = "", isRoleplay = true, createdAt = 0)
        var personas by mutableStateOf(listOf(persona))
        var sent: Triple<Long, String, Long?>? = null
        compose.setContent { MoReadTheme {
            val palette = readerPalette(ReaderSettings(), false)
            ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                AnnotationDiscussionSheet(listOf(annotation), emptyList(), null, null, personas, emptyList(), palette, {},
                    { target, text, id -> sent = Triple(target.id, text, id) }, { _, _, _ -> }, {}, { _ -> }, {}, {}, {},
                    defaultRespondPersonaId = 3)
            }
        } }
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        assertEquals(Triple(4L, "", 3L), sent)
        compose.runOnIdle {
            val root = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File("build/reports/ui-qa/annotation-empty-send.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            personas = emptyList()
        }
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
    }

    /** 忘记点名是段评「发出去没人回」的唯一成因：预选角色必须随文字一起发出去。 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun typedTextKeepsThePreselectedPersonaAndCanBeOptedOut() {
        val annotation = AnnotationEntity(id = 7, bookId = 1, chapterIndex = 0, startCharOffset = 0, endCharOffset = 6,
            selectedText = "灯塔的光。", createdAt = 1)
        val persona = PersonaEntity(id = 5, name = "知秋", personality = "", isRoleplay = true, createdAt = 0)
        var sent: Triple<Long, String, Long?>? = null
        var remembered: Long? = null
        compose.setContent { MoReadTheme {
            val palette = readerPalette(ReaderSettings(), false)
            ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                AnnotationDiscussionSheet(listOf(annotation), emptyList(), null, null, listOf(persona), emptyList(), palette, {},
                    { target, text, id -> sent = Triple(target.id, text, id) }, { _, _, _ -> }, {}, { _ -> }, {}, {}, {},
                    defaultRespondPersonaId = 5, onRememberRespondPersona = { remembered = it })
            }
        } }

        compose.onNodeWithText("写下你的想法…").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("这段写得真好")
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(Triple(7L, "这段写得真好", 5L), sent)

        // 再点一次选中的胶囊 = 这条只记想法，不叫人回复；取消不写回记忆。
        compose.onNodeWithText("知秋").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("先自己记一笔")
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(Triple(7L, "先自己记一笔", null), sent)
        assertNull(remembered)
    }

    /** 点某楼下的「回复」= 往输入框写出 `@那个人 `，并且由他来回话。 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun tappingReplyMentionsTheTurnAuthorAndLetsThemAnswer() {
        val annotation = AnnotationEntity(id = 7, bookId = 1, chapterIndex = 0, startCharOffset = 0, endCharOffset = 6,
            selectedText = "灯塔的光。", createdAt = 1)
        val peers = listOf(
            PersonaEntity(id = 5, name = "知秋", personality = "", isRoleplay = true, createdAt = 0),
            PersonaEntity(id = 6, name = "观澜", personality = "", isRoleplay = true, createdAt = 0)
        )
        val replies = listOf(
            AnnotationReplyEntity(id = 1, annotationId = 7, personaId = 5, contentMarkdown = "这里埋了伏笔", createdAt = 2),
            AnnotationReplyEntity(id = 2, annotationId = 7, personaId = 6, contentMarkdown = "未必吧", createdAt = 3)
        )
        var sent: Triple<Long, String, Long?>? = null
        compose.setContent { MoReadTheme {
            val palette = readerPalette(ReaderSettings(), false)
            ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                AnnotationDiscussionSheet(listOf(annotation), replies, null, null, peers, emptyList(), palette, {},
                    { target, text, id -> sent = Triple(target.id, text, id) }, { _, _, _ -> }, {}, { _ -> }, {}, {}, {})
            }
        } }

        // 「回复」这两个字是本地化资源，测试跑在哪种语言下都要认得出它。
        val replyLabel = ApplicationProvider.getApplicationContext<Context>().getString(R.string.discussion_reply)
        // 两个 AI 楼层各一个「回复」，点哪个就点哪个的名，输入框里始终只有一条 @。
        compose.onAllNodesWithText(replyLabel)[0].performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("@知秋 ")
        compose.onAllNodesWithText(replyLabel)[1].performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("@观澜 ")
        compose.onNode(hasSetTextAction()).performTextInput("我觉得不是这样")
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(Triple(7L, "@观澜 我觉得不是这样", 6L), sent)
    }

    /** 头部「删除批注」：先确认，再整串（这一段全部批注）删掉并关面板。 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun deleteButtonConfirmsThenRemovesTheWholeThread() {
        val annotation = AnnotationEntity(id = 7, bookId = 1, chapterIndex = 0, startCharOffset = 0, endCharOffset = 6,
            selectedText = "灯塔的光。", createdAt = 1)
        val peer = PersonaEntity(id = 5, name = "知秋", personality = "", isRoleplay = true, createdAt = 0)
        var deleted: List<Long>? = null
        var dismissed = false
        compose.setContent { MoReadTheme {
            val palette = readerPalette(ReaderSettings(), false)
            AnnotationDiscussionSheet(listOf(annotation), emptyList(), null, null, listOf(peer), emptyList(), palette, {},
                { _, _, _ -> }, { _, _, _ -> }, {}, { ids -> deleted = ids }, {}, {}, onDismiss = { dismissed = true })
        } }

        compose.onNodeWithTag("discussion-delete-thread").performClick()
        val title = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.discussion_delete_thread_title)
        assertTrue(compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("discussion-delete-confirm").performClick()
        compose.runOnIdle {
            assertEquals(listOf(7L), deleted?.toList())
            assertTrue(dismissed)
        }
    }
}
