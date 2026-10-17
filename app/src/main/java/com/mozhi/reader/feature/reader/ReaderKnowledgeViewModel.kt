package com.mozhi.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.knowledge.*
import com.mozhi.reader.core.database.entity.BookCharacterGuideEntity
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KnowledgeUiState(
    val bookId: Long = 0, val snapshot: KnowledgeSnapshot = KnowledgeSnapshot(), val loading: Boolean = true,
    val characters: BookCharactersSnapshot = BookCharactersSnapshot(),
    val tasks: Map<Int, KnowledgeTaskState> = emptyMap(), val characterTask: KnowledgeTaskState? = null,
    val pending: KnowledgeGenerationPlan? = null, val pendingCharacters: BookCharactersPlan? = null,
    val previewingChapter: Int? = null, val preparingCharacters: Boolean = false, val error: String? = null,
    val lookThumbnails: Map<String, String> = emptyMap()
)

@HiltViewModel
class ReaderKnowledgeViewModel @Inject constructor(
    private val repository: ChapterKnowledgeRepository,
    private val characters: BookCharactersRepository,
    private val runner: KnowledgeGenerationRunner,
    private val personas: com.mozhi.reader.ai.persona.PersonaRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(KnowledgeUiState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var previewJob: Job? = null
    private val locations = Channel<Pair<Int, Int>>(Channel.BUFFERED)
    val locateEvents = locations.receiveAsFlow()

    fun bind(bookId: Long) {
        if (mutable.value.bookId != bookId) {
            previewJob?.cancel()
            mutable.value = KnowledgeUiState(bookId)
        }
        // Refresh in place. Clearing a visible snapshot collapses lazy items and loses scroll anchors.
        observation?.cancel()
        observation = viewModelScope.launch {
            try {
                combine(repository.observe(bookId), characters.observe(bookId), runner.states) { outline, people, tasks ->
                    Triple(outline, people, tasks.filterKeys { it.bookId == bookId })
                }.collect { (outline, people, tasks) -> update(bookId) {
                    it.copy(snapshot = outline, characters = people, loading = false,
                        tasks = tasks.filterKeys { it.chapterIndex != null }.mapKeys { it.key.chapterIndex!! },
                        characterTask = tasks[KnowledgeTaskKey(bookId)])
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { update(bookId) { it.copy(loading = false, error = error.message ?: "无法读取书籍资料") } }
        }
    }

    fun preview(chapterIndex: Int) {
        if (mutable.value.tasks[chapterIndex]?.active == true) return
        prepare(chapterIndex) { book ->
            val plan = repository.preview(book, chapterIndex)
            update(book) { it.copy(pending = plan) }
        }
    }

    fun previewCharacters(progressBounded: Boolean, quick: Boolean = false, targets: List<CharacterTarget> = emptyList(),
        updateExisting: Boolean = false) {
        if (mutable.value.characterTask?.active == true) return
        prepare(null) { book ->
            val plan = characters.preview(book, progressBounded, quick, targets, updateExisting)
            // 新正文里没有出现任何既有角色：没有可更新的内容，也不值得弹确认框。
            if (updateExisting && plan.targets.isEmpty()) {
                update(book) { it.copy(error = "新正文里没有出现已有角色，本次没有可更新的内容") }
            } else update(book) { it.copy(pendingCharacters = plan) }
        }
    }

    /**
     * 「更新到当前进度 / 更新全书人物」：只刷新已有角色，跳过离场人物。
     * 真正的本地筛选在 [BookCharactersRepository.preview]：只有新正文里出现过的角色才会进入请求名单。
     */
    fun updateCharacters(progressBounded: Boolean) {
        val snapshot = mutable.value.characters
        if (snapshot.outdated) {
            mutable.update { it.copy(error = "正文已变化，请先重新提取人物") }
            return
        }
        val guide = snapshot.saved?.guide ?: return
        if (snapshot.checkpointParts > 0) {
            mutable.update { it.copy(error = "请先完成上一次提取，再更新人物") }
            return
        }
        // identity 是改名前书里的正式称呼；手写改名后，正文里出现的仍是 identity，不能只认显示名。
        val targets = guide.characters.filter { !it.departed && it.name.isNotBlank() }.map { person ->
            CharacterTarget(person.identity, (listOf(person.name) + person.aliases)
                .filter { !it.equals(person.identity, true) }
                .distinct().take(CharacterScanTargets.MAX_ALIASES))
        }
        if (targets.isEmpty()) {
            mutable.update { it.copy(error = "已有角色都已标记离场，没有可更新的角色") }
            return
        }
        previewCharacters(progressBounded, quick = false, targets = targets, updateExisting = true)
    }

    private fun prepare(chapterIndex: Int?, block: suspend (Long) -> Unit) {
        if (previewJob?.isActive == true) return
        val book = mutable.value.bookId
        update(book) { it.copy(previewingChapter = chapterIndex, preparingCharacters = chapterIndex == null,
            pending = null, pendingCharacters = null, error = null) }
        previewJob = viewModelScope.launch {
            try { block(book) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { update(book) { it.copy(error = error.message ?: "无法准备生成") } }
            finally { update(book) { it.copy(previewingChapter = null, preparingCharacters = false) } }
        }
    }

    /** 只看任务进度、不读书籍资料：阅读页的进度球用它，开销与目录弹层是否打开无关。 */
    fun characterTask(bookId: Long): kotlinx.coroutines.flow.Flow<KnowledgeTaskState?> =
        runner.states.map { it[KnowledgeTaskKey(bookId)] }.distinctUntilChanged()

    fun dismissPreview() { mutable.update { it.copy(pending = null, pendingCharacters = null) } }
    fun generate() { mutable.value.pending?.let(runner::generate); dismissPreview() }
    fun generateCharacters() { mutable.value.pendingCharacters?.let(runner::generateCharacters); dismissPreview() }
    fun cancel(chapterIndex: Int) { runner.stop(mutable.value.bookId, chapterIndex) }
    fun cancelCharacters() { runner.stop(mutable.value.bookId) }

    fun delete(chapterIndex: Int) = localAction { book -> repository.delete(book, chapterIndex) }
    fun saveOutline(chapterIndex: Int, outline: String) = localAction { book -> repository.saveOutline(book, chapterIndex, outline) }
    fun deleteCharacters() = localAction { book -> characters.delete(book) }
    fun saveCharacter(originalIdentity: String?, name: String,
        manualAttributes: ManualCharacterAttributes?, departed: Boolean? = null) = localAction { book ->
        characters.saveCharacter(book, originalIdentity, name, manualAttributes, departed)
    }
    fun setDeparted(identity: String, departed: Boolean) = localAction { book ->
        characters.setDeparted(book, identity, departed)
    }
    fun cancelHighlight(identity: String, field: HighlightField, key: String = "") = localAction { book ->
        characters.cancelHighlight(book, identity, field, key)
    }
    fun clearHighlights(identity: String) = localAction { book -> characters.clearHighlights(book, identity) }
    fun deleteCharacter(identity: String) = localAction { book -> characters.deleteCharacter(book, identity) }
    fun saveCharacterCard(card: ExtractedCharacterCard) = localAction {
        personas.save(com.mozhi.reader.core.database.entity.PersonaEntity(
            name = card.name, personality = card.description, subtitle = "从书中提取的角色", isRoleplay = true,
            createdAt = System.currentTimeMillis()
        ))
        mutable.update { it.copy(error = "已保存为伴读角色，可在伴读页继续编辑") }
    }
    fun locate(entry: ChapterKnowledgeEntity, fact: KnowledgeFact) = localAction { locations.send(repository.locate(entry, fact)) }
    fun locateCharacter(entry: BookCharacterGuideEntity, evidence: CharacterEvidence) = localAction { locations.send(characters.locate(entry, evidence)) }
    fun locateAnchor(entry: BookCharacterGuideEntity, anchor: SourceAnchor) = localAction { locations.send(characters.locate(entry, anchor)) }

    private fun localAction(block: suspend (Long) -> Unit) {
        val book = mutable.value.bookId
        viewModelScope.launch {
            try { block(book) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { update(book) { it.copy(error = error.message ?: "操作失败，请重试") } }
        }
    }

    private fun update(bookId: Long, transform: (KnowledgeUiState) -> KnowledgeUiState) {
        mutable.update { if (it.bookId == bookId) transform(it) else it }
    }
}
