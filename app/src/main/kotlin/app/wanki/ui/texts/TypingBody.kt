package app.wanki.ui.texts

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import app.wanki.R
import app.wanki.data.Settings
import app.wanki.data.TextPieceEntity
import app.wanki.data.TypingDraftEntity
import app.wanki.data.WordKey
import app.wanki.graph
import app.wanki.srs.Rating
import app.wanki.texts.PieceWord
import app.wanki.texts.SampleLevel
import app.wanki.texts.TypingScore
import app.wanki.texts.TypingText
import app.wanki.texts.UnitState
import app.wanki.texts.WordLook
import app.wanki.texts.WordStat
import app.wanki.ui.common.SaveErrorSnackbar
import app.wanki.ui.common.SerialSaver
import app.wanki.ui.common.rememberUiScope
import app.wanki.ui.common.ui
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

// --- rendering --------------------------------------------------------------------------------

/** A judged word (or an extra typed run, [state] null) inside a piece's text, for TalkBack and the long press. */
@Immutable
internal data class WordSpan(val unit: Int, val start: Int, val end: Int, val state: UnitState?, val correct: String)

internal enum class Deco { WAVY, DASHED, ACCENT }

@Immutable
internal data class DecoSpan(val start: Int, val end: Int, val kind: Deco, val color: Color)

/** One piece as shown: the coloured text, the caret offset (only in the piece being typed), words and underlines. */
@Immutable
internal data class PieceRender(val text: AnnotatedString, val caret: Int?, val words: List<WordSpan>, val decos: List<DecoSpan>)

internal fun stateLabel(state: UnitState?): String = when (state) {
    UnitState.OK -> "верно"
    UnitState.WRONG -> "неверно"
    UnitState.SKIPPED -> "пропущено"
    null -> "лишнее"
    else -> ""
}

/**
 * The piece as typed so far, like the Speech Practice page: typed words in their judged colour, the caret after
 * the last committed character, the IME composition over the next characters, and the rest by [TypingSession.look].
 * Words not reached are shown only while the sample is visible (level «С образцом» or the «Образец» button);
 * from memory only the cue of the word at the caret appears. After the end the rest counts as skipped.
 */
internal fun renderPiece(s: TypingSession, piece: TextPieceEntity, c: TypingColors): PieceRender {
    val words = s.pieceWords.getValue(piece.id)
    val content = piece.content
    val res = s.result
    val typed = s.committed
    val caret = s.caretUnit
    val ended = s.ended
    val showUntyped = s.level == SampleLevel.SAMPLE || s.sampleShown
    val caretPiece = if (caret < s.keys.size) s.keys[caret].pieceId else s.pieces.last().id
    val pieceAt = s.pieces.indexOfFirst { it.id == piece.id }
    val caretAt = s.pieces.indexOfFirst { it.id == caretPiece }
    val b = AnnotatedString.Builder()
    val spans = ArrayList<WordSpan>()
    val decos = ArrayList<DecoSpan>()
    var caretOffset: Int? = null
    var skip = 0 // characters the IME composition is laid over

    val ink = SpanStyle(color = c.ok)
    val faint = SpanStyle(color = c.faint)
    val blank = SpanStyle(color = Color.Transparent)

    fun put(text: String, style: SpanStyle) {
        if (text.isEmpty()) return
        b.pushStyle(style); b.append(text); b.pop()
    }
    fun untyped(text: String, style: SpanStyle?) {
        if (style == null) return
        var t = text
        while (skip > 0 && t.isNotEmpty()) {
            if (t[0] != '\n') skip--
            t = t.substring(Character.charCount(t.codePointAt(0)))
        }
        put(t, style)
    }
    fun extra(range: IntRange?, unit: Int) {
        if (range == null) return
        val start = b.length
        put(typed.substring(range), SpanStyle(color = c.extra, textDecoration = TextDecoration.LineThrough))
        spans += WordSpan(unit, start, b.length, null, "")
    }
    fun placeCaret() {
        if (ended) return
        caretOffset = b.length
        put(s.composing, SpanStyle(color = c.composing, textDecoration = TextDecoration.Underline))
        skip = s.composing.codePointCount(0, s.composing.length)
    }
    fun decorate(start: Int, look: WordLook?) {
        if (look == WordLook.HIGHLIGHTED || look == WordLook.MARKED) decos += DecoSpan(start, b.length, Deco.ACCENT, c.accent)
    }

    val before = pieceAt < caretAt || ended
    var pos = 0
    for ((i, w) in words.withIndex()) {
        val k = s.unitOf(WordKey(piece.id, i)) ?: continue
        val unit = res.units[k]
        val surface = content.substring(w.start, w.end)
        val gap = content.substring(pos, w.start)
        pos = w.end
        if (k < caret || ended) {
            put(gap, ink)
            extra(unit.extraBefore, k)
            val state = when {
                k < caret -> unit.state
                unit.state == UnitState.PENDING && unit.typed != null -> UnitState.WRONG
                else -> UnitState.SKIPPED
            }
            val start = b.length
            when (state) {
                UnitState.WRONG -> {
                    put(unit.typed?.let { typed.substring(it) } ?: surface, SpanStyle(color = c.wrong))
                    decos += DecoSpan(start, b.length, Deco.WAVY, c.wrong)
                }
                UnitState.SKIPPED -> {
                    put(surface, SpanStyle(color = c.skipped))
                    decos += DecoSpan(start, b.length, Deco.DASHED, c.skipped)
                }
                else -> put(surface, ink)
            }
            if (k < caret) decorate(start, s.look(piece.id, i))
            if (b.length > start) spans += WordSpan(k, start, b.length, state.takeIf { it == UnitState.OK || it == UnitState.WRONG || it == UnitState.SKIPPED } ?: UnitState.OK, surface)
            continue
        }
        val look = s.look(piece.id, i)
        if (k == caret) {
            put(gap, ink)
            extra(unit.extraBefore, k)
            val pending = unit.state == UnitState.PENDING
            if (pending) unit.typed?.let { put(typed.substring(it), ink) }
            extra(res.trailingExtra, k)
            placeCaret()
            val rest = if (!pending) surface else restOf(surface, w, s.typedColumns(k))
            val start = b.length
            when (look) {
                WordLook.SHOWN, WordLook.CUE_WORD, WordLook.HIGHLIGHTED -> untyped(rest, faint)
                WordLook.HIDDEN -> untyped(rest, blank)
                WordLook.CUE_FIRST -> if (s.typedColumns(k) == 0) {
                    untyped(surface.substring(0, Character.charCount(surface.codePointAt(0))), faint)
                }
                else -> {}
            }
            decorate(start, look)
            continue
        }
        // ahead of the caret
        if (!showUntyped) continue
        untyped(gap, faint)
        val start = b.length
        untyped(surface, if (look == WordLook.HIDDEN) blank else faint)
        decorate(start, look)
    }
    val tail = content.substring(pos)
    when {
        before -> put(tail, ink)
        piece.id == caretPiece && caret >= s.keys.size -> {
            // everything is typed: the caret goes after the piece's last character
            put(tail, ink)
            extra(res.trailingExtra, maxOf(0, s.keys.size - 1))
            placeCaret()
        }
        showUntyped -> untyped(tail, faint)
    }
    return PieceRender(b.toAnnotatedString(), caretOffset, spans, decos)
}

/** What is left to show of a half-typed word: written as typed, the typed letters are dropped; otherwise all of it. */
private fun restOf(surface: String, w: PieceWord, typedColumns: Int): String {
    val folded = TypingText.fold(surface)
    if (!folded.chars.contentEquals(TypingText.fold(w.reading).chars)) return surface
    return if (typedColumns >= folded.source.size) "" else surface.substring(folded.source[typedColumns])
}

// --- state ------------------------------------------------------------------------------------

/** The typing run of the screen; only [saved] and [recorded] outlive a recreation (the rest is reloaded). */
private class TypingHolder(var saved: TypingSnapshot? = null, var recorded: Boolean = false) {
    var session by mutableStateOf<TypingSession?>(null)
    var asking by mutableStateOf<TypingDraftEntity?>(null)
    var value by mutableStateOf(TextFieldValue())
    var words: Map<String, List<PieceWord>> = emptyMap()
    /** The word-history write of the last result; «Ещё раз» waits for it before reading the stats. */
    var recording: kotlinx.coroutines.Job? = null

    fun start(s: TypingSession) {
        session = s
        value = TextFieldValue(s.committed, TextRange(s.committed.length))
    }

    companion object {
        val Saver: Saver<TypingHolder, List<String>> = Saver(
            save = { h ->
                // while the draft question is open nothing is saved: a recreated screen asks again
                val snap = if (h.asking != null) null else h.session?.snapshot() ?: h.saved
                snap?.let {
                    listOf(
                        it.typed, it.level.name, it.elapsedMs.toString(), it.sampleShown.toString(), it.ended.toString(),
                        h.recorded.toString(), encodeKeys(it.hinted), encodeIds(it.samplePieces),
                    )
                } ?: emptyList()
            },
            restore = { l ->
                if (l.size < 8) TypingHolder()
                else TypingHolder(
                    TypingSnapshot(
                        typed = l[0], level = SampleLevel.valueOf(l[1]), elapsedMs = l[2].toLong(), hinted = decodeKeys(l[6]),
                        samplePieces = decodeIds(l[7]),
                        sampleShown = l[3].toBoolean(), ended = l[4].toBoolean(),
                    ),
                    recorded = l[5].toBoolean(),
                )
            },
        )
    }
}

private fun epochDay(ms: Long): Long =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()

// --- screen -----------------------------------------------------------------------------------

/** «Печать»: the request's pieces (in a review, the current due piece) typed over the sample or from memory. */
@Composable
internal fun TypingBody(state: StudyState, snackbar: SnackbarHostState) {
    val request = state.request
    val targets = if (request.kind == StudyKind.REVIEW) listOfNotNull(state.pieces.getOrNull(state.reviewIndex)) else state.pieces
    if (targets.isEmpty()) return
    val key = draftKey(request, if (request.kind == StudyKind.REVIEW) targets.first().id else null)
    key(key) { TypingRun(state, targets, key, snackbar) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypingRun(state: StudyState, targets: List<TextPieceEntity>, draftKey: String, snackbar: SnackbarHostState) {
    val graph = LocalContext.current.graph
    val texts = graph.texts
    val settings = graph.settings
    val scope = rememberUiScope()
    val holder = rememberSaveable(saver = TypingHolder.Saver) { TypingHolder() }
    val saver = remember {
        SerialSaver(graph.appScope) {
            val s = holder.session ?: return@SerialSaver
            if (s.ended || s.typedAll.isEmpty()) texts.clearDraft(draftKey)
            else {
                val now = System.currentTimeMillis()
                texts.saveDraft(TypingDraftEntity(draftKey, s.typedAll, encodeDraftLevel(s.snapshot()), s.startedAt, epochDay(now), now))
            }
        }
    }

    LaunchedEffect(Unit) {
        if (holder.session != null) return@LaunchedEffect
        ui {
            holder.words = texts.words(targets)
            val stats = texts.wordStats(targets.map { it.id })
            val saved = holder.saved
            if (saved != null) {
                holder.saved = null
                holder.start(TypingSession(targets, holder.words, stats, saved.level).also { it.resume(saved) })
                return@ui
            }
            val draft = texts.draft(draftKey)
            holder.start(TypingSession(targets, holder.words, stats, defaultLevel(state.request.kind)))
            if (draft != null && draft.typed.isNotEmpty()) {
                if (draft.day == LocalDate.now().toEpochDay()) holder.asking = draft else texts.clearDraft(draftKey)
            }
        }
    }

    val session = holder.session ?: return
    val colors = typingColors()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val ended = session.ended

    fun restart(reloadStats: Boolean) = scope.launch {
        holder.recording?.join()
        val stats = if (reloadStats) texts.wordStats(targets.map { it.id }) else null
        ui {
            val level = session.level
            holder.recorded = false
            holder.start(TypingSession(targets, holder.words, stats ?: session.stats, level))
            saver.request()
        }
    }

    // the result: word history once, the unfinished attempt is gone
    LaunchedEffect(session, ended) {
        if (!ended) return@LaunchedEffect
        keyboard?.hide()
        saver.request()
        if (holder.recorded) return@LaunchedEffect
        holder.recorded = true
        val words = session.finish().words
        // on the app scope: leaving the screen right away («Готово») must not cancel the write
        holder.recording = graph.appScope.launch {
            try {
                texts.recordWords(words)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                holder.recorded = false
                snackbar.showSnackbar("Не удалось сохранить историю слов")
            }
        }
    }
    LaunchedEffect(session, holder.asking, ended) {
        if (holder.asking == null && !ended) runCatching { focus.requestFocus() }
    }

    var elapsed by remember { mutableLongStateOf(session.elapsedMs) }
    LaunchedEffect(session, ended) {
        while (true) {
            elapsed = session.elapsedMs
            if (session.ended) break
            delay(1000)
        }
    }

    val language = state.language(targets.first())
    val fontSp = settings.typingFontSp
    val base = if (app.wanki.texts.Words.isRtl(language)) pieceStyle(language) else MaterialTheme.typography.bodyLarge
    val textStyle = base.copy(fontSize = fontSp.sp, lineHeight = (fontSp * 1.6f).sp)
    val scroll = rememberScrollState()
    LaunchedEffect(ended) { if (ended) scroll.animateScrollTo(0) }

    Column(Modifier.fillMaxSize().imePadding()) {
        // after the end the result card has the final counts (the rest of the text counts as skipped)
        if (!ended) TypingStats(session, elapsed)
        if (!ended) TypingTools(session, settings, onRestart = { restart(reloadStats = false) }, onChange = saver::request)
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(session) {
                detectTapGestures { if (!session.ended) { focus.requestFocus(); keyboard?.show() } }
            },
        ) {
            // the input sits behind the text: the soft keyboard and a hardware one both type into it
            if (!ended) {
                BasicTextField(
                    value = holder.value,
                    onValueChange = { v ->
                        holder.value = session.onValue(v)
                        saver.request()
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Text,
                    ),
                    modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus).testTag("typing-input"),
                )
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (ended) TypingResultCard(state, session, elapsed)
                Directional(language) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        var lastText: String? = null
                        for (p in targets) {
                            if (state.request.kind == StudyKind.MANZIL && p.textId != lastText) {
                                lastText = p.textId
                                Text(state.title(p.textId), style = MaterialTheme.typography.titleSmall)
                            }
                            val render = renderPiece(session, p, colors)
                            if (render.text.isEmpty() && render.caret == null) continue
                            PieceTyping(render, textStyle, colors, onTap = { if (!session.ended) { focus.requestFocus(); keyboard?.show() } })
                        }
                    }
                }
            }
        }
        if (ended) TypingResultButtons(state, session, scope, onAgain = { restart(reloadStats = true) })
        SaveErrorSnackbar(saver)
    }

    holder.asking?.let { draft ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Продолжить попытку?") },
            text = { Text("Сегодня вы уже начали печатать этот текст.") },
            confirmButton = {
                TextButton(onClick = {
                    holder.asking = null
                    // hints and sample use come back with the text: leaving does not clean a run
                    session.resume(decodeDraftLevel(draft.level, draft.typed, (draft.updatedAt - draft.startedAt).coerceAtLeast(0)))
                    holder.start(session)
                }, modifier = Modifier.testTag("typing-continue")) { Text("Продолжить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    holder.asking = null
                    scope.launch { texts.clearDraft(draftKey) }
                }, modifier = Modifier.testTag("typing-fresh")) { Text("Сначала") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypingStats(s: TypingSession, elapsed: Long) {
    val r = s.result
    val progress = if (r.total == 0) 100 else r.end * 100 / r.total
    val accuracy = if (s.judged == 0) 100 else s.judgedCorrect * 100 / s.judged
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).testTag("typing-stats"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val style = MaterialTheme.typography.labelLarge
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Text("$progress%", style = style)
        Text("Точность $accuracy%", style = style, color = muted)
        Text("Ошибок ${r.mistakes}", style = style, color = if (r.mistakes > 0) MaterialTheme.colorScheme.error else muted)
        Text(clock(elapsed), style = style, color = muted)
    }
}

private fun clock(ms: Long): String {
    val sec = ms / 1000
    return "%d:%02d".format(sec / 60, sec % 60)
}

@Composable
private fun TypingTools(s: TypingSession, settings: Settings, onRestart: () -> Unit, onChange: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var fontDialog by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        FilterChip(
            selected = s.sampleShown,
            onClick = { if (s.sampleShown) s.hideSample() else s.showSample() },
            label = { Text("Образец") },
            modifier = Modifier.testTag("typing-sample"),
        )
        Spacer(Modifier.size(8.dp))
        TextButton(onClick = { if (s.typedAll.isNotEmpty()) confirm = true else onRestart() }, modifier = Modifier.testTag("typing-restart")) {
            Text("Сначала")
        }
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("typing-menu")) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Ещё")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SampleLevel.entries.forEach { l ->
                    DropdownMenuItem(
                        text = { Text((if (s.level == l) "✓ " else "    ") + levelLabel(l)) },
                        onClick = { menu = false; s.level = l; onChange() },
                        modifier = Modifier.testTag("typing-level-${l.name}"),
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Размер шрифта") }, onClick = { menu = false; fontDialog = true }, modifier = Modifier.testTag("typing-font"))
                DropdownMenuItem(text = { Text("Закончить") }, onClick = { menu = false; s.end() }, modifier = Modifier.testTag("typing-finish"))
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Начать заново?") },
            text = { Text("Напечатанное сотрётся.") },
            confirmButton = { TextButton(onClick = { confirm = false; onRestart() }, modifier = Modifier.testTag("typing-restart-confirm")) { Text("Сначала") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
        )
    }
    if (fontDialog) {
        AlertDialog(
            onDismissRequest = { fontDialog = false },
            title = { Text("Размер шрифта") },
            text = {
                Column {
                    Text("${settings.typingFontSp} sp", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = settings.typingFontSp.toFloat(),
                        onValueChange = { settings.typingFontSp = it.roundToInt() },
                        valueRange = Settings.TYPING_FONT_MIN.toFloat()..Settings.TYPING_FONT_MAX.toFloat(),
                        steps = Settings.TYPING_FONT_MAX - Settings.TYPING_FONT_MIN - 1,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { fontDialog = false }) { Text("Готово") } },
        )
    }
}

private fun levelLabel(l: SampleLevel) = when (l) {
    SampleLevel.SAMPLE -> "С образцом"
    SampleLevel.MEMORY -> "По памяти"
    SampleLevel.STRICT -> "Строго"
}

/** One piece: the text, its underlines and caret, a TalkBack node per judged word, and the correct spelling on a long press. */
@Composable
private fun PieceTyping(render: PieceRender, style: TextStyle, colors: TypingColors, onTap: () -> Unit) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var popup by remember { mutableStateOf<WordSpan?>(null) }
    val requester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxWidth().bringIntoViewRequester(requester).pointerInput(render) {
            detectTapGestures(
                onTap = { onTap() },
                onLongPress = { pos ->
                    val l = layout ?: return@detectTapGestures
                    val at = l.getOffsetForPosition(pos)
                    popup = render.words.firstOrNull { it.state == UnitState.WRONG && at >= it.start && at <= it.end }
                },
            )
        },
    ) {
        Text(
            render.text,
            style = style,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth().drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                if (l.layoutInput.text != render.text) return@drawWithContent
                for (d in render.decos) underline(l, d)
                render.caret?.let { c ->
                    val r = l.getCursorRect(c.coerceAtMost(render.text.length))
                    drawLine(colors.accent, Offset(r.left, r.top), Offset(r.left, r.bottom), strokeWidth = 2.dp.toPx())
                }
            },
        )
        val l = layout
        if (l != null && l.layoutInput.text == render.text) {
            for (w in render.words) {
                val r = l.getPathForRange(w.start, w.end).getBounds()
                val typedWord = render.text.text.substring(w.start, w.end)
                val shown = if (w.state == UnitState.WRONG) "$typedWord, правильно: ${w.correct}" else typedWord
                Box(
                    Modifier.offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                        .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                        .semantics { contentDescription = shown; stateDescription = stateLabel(w.state) }
                        .testTag(if (w.state == null) "typing-extra-${w.unit}" else "typing-word-${w.unit}"),
                )
            }
            popup?.let { w ->
                val r = l.getPathForRange(w.start, w.end).getBounds()
                Popup(
                    offset = IntOffset(r.left.roundToInt(), (r.top - with(density) { 44.dp.toPx() }).roundToInt()),
                    onDismissRequest = { popup = null },
                ) {
                    Surface(shape = MaterialTheme.shapes.small, tonalElevation = 6.dp, shadowElevation = 6.dp) {
                        Text(w.correct, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("typing-correct"))
                    }
                }
            }
            LaunchedEffect(render.caret, l) {
                val c = render.caret ?: return@LaunchedEffect
                val r = l.getCursorRect(c.coerceAtMost(render.text.length))
                val margin = with(density) { 48.dp.toPx() }
                requester.bringIntoView(r.copy(top = r.top - margin, bottom = r.bottom + margin))
            }
        }
    }
}

/** Wavy (wrong), dashed (skipped) or solid accent (hard word) line under [d], line by line. */
private fun DrawScope.underline(l: TextLayoutResult, d: DecoSpan) {
    if (d.end <= d.start || d.end > l.layoutInput.text.length) return
    val first = l.getLineForOffset(d.start)
    val last = l.getLineForOffset(d.end - 1)
    val stroke = 1.5.dp.toPx()
    for (line in first..last) {
        val from = maxOf(d.start, l.getLineStart(line))
        val to = minOf(d.end, l.getLineEnd(line, visibleEnd = true))
        if (to <= from) continue
        val a = l.getHorizontalPosition(from, usePrimaryDirection = true)
        val z = l.getHorizontalPosition(to, usePrimaryDirection = true)
        val x0 = minOf(a, z)
        val x1 = maxOf(a, z)
        val y = l.getLineBaseline(line) + 4.dp.toPx()
        when (d.kind) {
            Deco.WAVY -> {
                val step = 3.dp.toPx()
                val amp = 1.5.dp.toPx()
                val path = Path().apply {
                    moveTo(x0, y)
                    var x = x0
                    var up = true
                    while (x < x1) {
                        val nx = minOf(x + step, x1)
                        lineTo(nx, if (up) y - amp else y + amp)
                        x = nx
                        up = !up
                    }
                }
                drawPath(path, d.color, style = Stroke(width = stroke))
            }
            Deco.DASHED -> drawLine(
                d.color, Offset(x0, y), Offset(x1, y), strokeWidth = stroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
            Deco.ACCENT -> drawLine(d.color, Offset(x0, y), Offset(x1, y), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun TypingResultCard(state: StudyState, s: TypingSession, elapsed: Long) {
    val run = remember(s) { s.finish() }
    val words = run.pieces.values.sumOf { it.words }
    val correct = run.pieces.values.sumOf { it.correct }
    val errors = run.pieces.values.sumOf { it.errors }
    val hints = run.pieces.values.sumOf { it.hints }
    Card(Modifier.fillMaxWidth().testTag("typing-result")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Итог", style = MaterialTheme.typography.titleMedium)
            Text("Ошибок: $errors")
            Text("Точность: ${if (words == 0) 100 else correct * 100 / words}%")
            if (hints > 0) Text("Подсказок: $hints")
            Text("Время: ${clock(elapsed)}")
            if (state.request.kind == StudyKind.REVIEW) {
                run.pieces.values.firstOrNull()?.let { Text("Оценка: ${ratingName(TypingScore.rating(it, easy = false))}", style = MaterialTheme.typography.titleSmall) }
            }
            if (s.pieces.size > 1) {
                Spacer(Modifier.size(4.dp))
                for (p in s.pieces) {
                    val r = run.pieces.getValue(p.id)
                    val acc = if (r.words == 0) 100 else r.correct * 100 / r.words
                    Text(
                        "${p.pieceIndex + 1}. ошибок ${r.errors}, точность $acc%" + if (r.hints > 0) ", подсказок ${r.hints}" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (r.errors > 0 || r.hints > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun ratingName(r: Rating) = when (r) {
    Rating.AGAIN -> "снова"
    Rating.HARD -> "трудно"
    Rating.GOOD -> "хорошо"
    Rating.EASY -> "легко"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypingResultButtons(state: StudyState, s: TypingSession, scope: kotlinx.coroutines.CoroutineScope, onAgain: () -> Unit) {
    val run = remember(s) { s.finish() }
    val clean = run.pieces.isNotEmpty() && run.pieces.values.all(TypingScore::clean)
    val duration = s.elapsedMs
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.request.kind == StudyKind.LEARN && !clean) {
            Text(
                "Порция засчитывается после печати по памяти без ошибок и подсказок.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.request.kind) {
                StudyKind.LEARN -> {
                    if (clean) Button(onClick = { scope.launch { state.learnTyped(duration) } }, enabled = !state.busy, modifier = Modifier.testTag("typing-learned")) { Text("Выучено") }
                    OutlinedButton(onClick = onAgain, enabled = !state.busy, modifier = Modifier.testTag("typing-again")) { Text("Ещё раз") }
                }
                StudyKind.REVIEW -> {
                    val piece = run.pieces.values.first()
                    Button(
                        onClick = { scope.launch { state.rateTyped(TypingScore.rating(piece, easy = false), piece.hints, duration, error = piece.errors > 0) } },
                        enabled = !state.busy, modifier = Modifier.testTag("typing-next"),
                    ) { Text("Дальше") }
                    if (clean) OutlinedButton(
                        onClick = { scope.launch { state.rateTyped(TypingScore.rating(piece, easy = true), piece.hints, duration, error = piece.errors > 0) } },
                        enabled = !state.busy, modifier = Modifier.testTag("typing-easy"),
                    ) { Text("Легко") }
                }
                else -> Button(
                    onClick = { scope.launch { state.typedDone(run.recitalErrors(), run.pieces.values.sumOf { it.hints }, duration) } },
                    enabled = !state.busy, modifier = Modifier.testTag("typing-done"),
                ) { Text("Готово") }
            }
        }
    }
}
