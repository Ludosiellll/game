package forma.app.data

import forma.app.sync.CoachApi
import forma.app.sync.CoachCall
import forma.app.sync.CoachTurn
import kotlinx.coroutines.launch

/**
 * The talk with the coach, kept on the phone ([SettingsStore.coachChat]): a question asked, the answer with the changes the
 * coach chose — those needing no «Принять» made at once ([CoachActions]) — and the rest accepted, declined or taken back
 * later. The screen only shows it.
 */
object CoachTalk {
    /** Messages of the talk the model reads with a question. */
    const val TURNS = 16

    /**
     * Questions go on even when the screen is left (another tab): the answer is kept and shown on return. Off the main
     * thread: the work is the repository's and the network's, and the screen follows the flows.
     */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /** A question is being answered. */
    val busy = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Why the last question got no answer (said on the screen), and the question itself, to put back in the field. */
    val failed = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)

    /** Asks [question] in the background ([ask]); one question at a time. */
    fun send(repo: TrainingRepository, api: CoachApi, question: String, context: suspend () -> String) {
        if (question.isBlank() || busy.value) return
        busy.value = true
        failed.value = null
        scope.launch {
            try {
                ask(repo, api, question, context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: forma.app.sync.CoachException) {
                failed.value = e.reason.text to question
            } catch (e: Exception) {
                failed.value = forma.app.sync.CoachException.Reason.FAILED.text to question
            } finally {
                busy.value = false
            }
        }
    }

    /** The talk as the model reads it: the coach's messages as it is told it said them (with what the app did). */
    fun history(chat: List<CoachChatMsg>): List<CoachTurn> = chat.takeLast(TURNS).mapNotNull { m ->
        val text = if (m.mine) m.text else m.said ?: m.text
        text.takeIf { it.isNotBlank() }?.let { CoachTurn(if (m.mine) CoachTurn.USER else CoachTurn.MODEL, it) }
    }

    private suspend fun nextId(repo: TrainingRepository): Long =
        maxOf(repo.nowMs(), (repo.store.coachChatNow().maxOfOrNull { it.id } ?: 0L) + 1)

    /**
     * Asks [question] with the summary [context] gives: the question and the answer are kept, the answer's changes
     * checked and the ones needing no «Принять» made. When there is no answer the question is taken back out (the screen
     * puts it back in the field) and the reason thrown ([forma.app.sync.CoachException]).
     */
    suspend fun ask(repo: TrainingRepository, api: CoachApi, question: String, context: suspend () -> String): CoachChatMsg {
        val q = question.trim()
        val mine = CoachChatMsg(nextId(repo), true, q)
        repo.store.editCoachChat { it + mine }
        try {
            val reply = api.askCoach(history(repo.store.coachChatNow()), context(), CoachActions.declarations)
            val acts = reply.calls.map { act(repo, it) }
            val notes = acts.map { a ->
                when (a.state) {
                    CoachActState.DONE -> "сделано: ${a.text}"
                    CoachActState.PENDING -> "ждёт «Принять»: ${a.text}"
                    else -> "не сделано: ${a.text}"
                }
            }
            // What the app did goes first (a long answer is cut at its end on the way to the model), and words of the model
            // dressed as the app's are dropped: only the app says what was done.
            val text = reply.text.lines().filterNot { it.trimStart().startsWith("[Приложение") }.joinToString("\n").trim()
            val said = ((if (notes.isEmpty()) "" else "[Приложение — ${notes.joinToString("; ")}]\n") + text).trim()
            val msg = CoachChatMsg(nextId(repo), false, text, acts, said)
            repo.store.editCoachChat { it + msg }
            return msg
        } catch (e: Exception) {
            repo.store.editCoachChat { list -> list.filter { it.id != mine.id } }
            throw e
        }
    }

    /**
     * A call of the coach's checked: made at once, waiting for «Принять», or not possible (and why). One bad call never
     * loses the answer and the changes already made with it.
     */
    private suspend fun act(repo: TrainingRepository, call: CoachCall): CoachChatAct {
        val c = runCatching { CoachActions.prepare(repo, call) }.getOrElse { return CoachChatAct(call, "Не получилось разобрать изменение.", null, CoachActState.REFUSED) }
        return when (c) {
            is CoachActions.Refused -> CoachChatAct(call, c.why, null, CoachActState.REFUSED)
            is CoachActions.Ready -> if (c.mode == CoachActions.Mode.CONFIRM) CoachChatAct(call, c.text, c.why, CoachActState.PENDING)
            else runCatching { CoachActions.make(repo, c) }.getOrNull()
                ?.let { CoachChatAct(call, c.text, c.why, CoachActState.DONE, it.id) }
                ?: CoachChatAct(call, "Не получилось: ${c.text}", c.why, CoachActState.REFUSED)
        }
    }

    private suspend fun update(repo: TrainingRepository, msgId: Long, index: Int, f: (CoachChatAct) -> CoachChatAct) {
        repo.store.editCoachChat { list ->
            list.map { m -> if (m.id != msgId) m else m.copy(acts = m.acts.mapIndexed { i, a -> if (i == index) f(a) else a }) }
        }
    }

    private suspend fun actOf(repo: TrainingRepository, msgId: Long, index: Int): CoachChatAct? =
        repo.store.coachChatNow().firstOrNull { it.id == msgId }?.acts?.getOrNull(index)

    /**
     * «Принять»: the change checked again against the data as they are now, and made; or said why it cannot be. When it
     * now says something else than the card did (the data moved on since), it is not made: the card shows the new words
     * and waits for «Принять» again.
     */
    suspend fun accept(repo: TrainingRepository, msgId: Long, index: Int): CoachChatAct? {
        val act = actOf(repo, msgId, index)?.takeIf { it.state == CoachActState.PENDING } ?: return null
        val next = when (val c = CoachActions.prepare(repo, act.call)) {
            is CoachActions.Refused -> act.copy(text = c.why, state = CoachActState.REFUSED)
            is CoachActions.Ready ->
                if (c.text != act.text) act.copy(text = c.text)
                else act.copy(text = c.text, state = CoachActState.DONE, logId = CoachActions.make(repo, c).id)
        }
        update(repo, msgId, index) { next }
        return next
    }

    /** «Не надо». */
    suspend fun decline(repo: TrainingRepository, msgId: Long, index: Int) =
        update(repo, msgId, index) { if (it.state == CoachActState.PENDING) it.copy(state = CoachActState.DECLINED) else it }

    /** «Вернуть»: the change taken back. False when it cannot be any more (the same thing changed since). */
    suspend fun undo(repo: TrainingRepository, msgId: Long, index: Int): Boolean {
        val act = actOf(repo, msgId, index) ?: return false
        val entry = repo.store.coachLogNow().firstOrNull { it.id == act.logId } ?: return false
        if (!CoachActions.undo(repo, entry)) return false
        update(repo, msgId, index) { it.copy(state = CoachActState.UNDONE) }
        return true
    }

    /** «Новый разговор»: the talk cleared (the changes made stay, with their «Вернуть» in «Что поменял тренер»). */
    suspend fun clear(repo: TrainingRepository) {
        failed.value = null
        repo.store.editCoachChat { emptyList() }
    }
}
