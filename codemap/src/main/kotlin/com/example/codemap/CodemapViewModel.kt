package com.example.codemap

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** What the tool window is showing right now. */
sealed interface CodemapState {
    /** No editor tab is selected (or the selected tab is not a file). */
    data object NoFile : CodemapState

    /**
     * The file lives outside the codemap root, so it has no place in the mirror. [root] is shown
     * because this is the one confusing state — knowing which root was resolved explains it at a glance.
     */
    data class Outside(val path: String, val root: String) : CodemapState

    data class Loading(val name: String) : CodemapState

    data class Loaded(
        val rel: String,
        val note: JsonObject?,
        /** Where each function's anchor currently sits, by function name. Absent = anchor not found. */
        val functionLoc: Map<String, FnLoc>,
        val freshness: NoteStore.Freshness,
        /** Commits that landed on this file since the note was written; null when unknown. */
        val commitsSince: Int?,
        /** Someone has corrected this note by hand — those corrections outlive a re-analysis. */
        val edited: Boolean,
        /** Flows held by OTHER notes in which this file takes part — see [FlowIndex]. */
        val appearances: List<FlowIndex.Entry>,
        /** For each packet this note records, where else it turns up — see [PacketIndex]. */
        val packetElsewhere: Map<String, List<PacketIndex.Entry>>,
        /** For each packet this note records, the sequences that trace it. */
        val packetFlows: Map<String, List<FlowIndex.Entry>>,
    ) : CodemapState {
        val name: String get() = rel.substringAfterLast('/')
        val dir: String get() = rel.substringBeforeLast('/', "")
    }

    /** A resolved function location: which file of the pair, which line, and how unique the anchor was. */
    data class FnLoc(val rel: String, val line: Int, val occurrences: Int)
}

/**
 * Loads the note for the selected editor tab and holds it as Compose state.
 *
 * Deliberately does the least work that the view actually renders: the panel shows the note, not file
 * statistics, so nothing here counts lines or shells out to git unless a note exists and its drift
 * needs measuring. On a 58M-line project a per-tab-switch `git log` is a real cost, and work you do
 * not display is pure latency.
 *
 * The remaining IO (content hashing for freshness, one `git rev-list` for drift) runs on a pooled
 * thread and only the finished state is published on the EDT. Loads are sequenced so a fast tab
 * switch can never let a stale result overwrite a newer one.
 */
class CodemapViewModel(private val project: Project) {

    var state: CodemapState by mutableStateOf(CodemapState.NoFile)
        private set

    /**
     * The function the editor caret is inside, if the note records one there. This is what makes the
     * panel progressive: you keep reading code, and the note narrows to whatever you are looking at.
     */
    var focusedFunction: String? by mutableStateOf(null)
        private set

    /** Analysis progress, shown inline so a long run never looks like a dead button. */
    sealed interface Analysis {
        data object Idle : Analysis
        data class Running(val path: String) : Analysis
        data class Failed(val reason: String) : Analysis
    }

    var analysis: Analysis by mutableStateOf(Analysis.Idle)
        private set

    private var runner: AnalysisRunner? = null

    private val store: NoteStore? get() = project.getService(CodemapStore::class.java).store

    /**
     * Absolute paths whose content the current verdict depends on — the note's covered files.
     *
     * Freshness is computed from these files' hashes, so a change to any of them invalidates what the
     * panel is showing. Without this the panel keeps asserting a verdict about a file that has since
     * been edited, which is worse than showing nothing.
     */
    @Volatile
    private var watched: Set<String> = emptySet()

    fun dependsOn(path: String): Boolean = path in watched

    private val generation = AtomicLong()
    private var current: VirtualFile? = null

    fun select(file: VirtualFile?) {
        // Our own tabs are not source files; selecting one should leave the note on screen rather than
        // replacing it with "outside the codemap root".
        if (file is CodemapGraphFile || file is CodemapSequenceFile || file is CodemapChatFile) return
        current = file
        focusedFunction = null
        reload()
    }

    /**
     * Narrow to the function the caret landed in.
     *
     * Which function that is comes from the recorded anchors alone: the nearest anchor at or above the
     * caret, in the same file. That is arithmetic over positions the plugin already resolved, not an
     * opinion about where a function ends — so a caret in the blank space after a body still reads as
     * "in" that function, which is the useful answer anyway.
     */
    fun onCaret(file: VirtualFile, line: Int) {
        val loaded = state as? CodemapState.Loaded ?: return
        val rel = store?.relativize(File(file.path)) ?: return
        val hit = loaded.functionLoc.entries
            .filter { it.value.rel == rel && it.value.line <= line }
            .maxByOrNull { it.value.line }
            ?.key
        if (hit != focusedFunction) focusedFunction = hit
    }

    /** Re-read from disk — after a request is queued, or when the AI has written a note meanwhile. */
    fun reload() {
        val file = current
        val gen = generation.incrementAndGet()
        if (file == null || file.isDirectory) {
            publish(gen) { CodemapState.NoFile }
            return
        }
        // reload() is also called from a pooled thread (after queueing a request), so even the
        // placeholder goes through the EDT — Compose state must only ever be written there.
        focusedFunction = null
        publish(gen) { CodemapState.Loading(file.name) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val next = runCatching { load(file) }
                .getOrElse { CodemapState.Outside(file.path, store?.root?.absolutePath.orEmpty()) }
            publish(gen) { next }
        }
    }

    /** The packet ids a note records, in order — the keys of the cross-note lookups. */
    private fun packetsOf(note: JsonObject?): List<String> =
        (note?.get("packets") as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        }.orEmpty()

    private fun load(file: VirtualFile): CodemapState {
        val io = File(file.path)
        val s = store ?: return CodemapState.Outside(file.path, "")
        val rel = s.relativize(io) ?: return CodemapState.Outside(file.path, s.root.absolutePath)
        val note = s.readNote(rel)
        val freshness = s.freshness(note)
        val all = project.getService(CodemapStore::class.java).allNotes()
        watched = buildSet {
            add(io.absolutePath)
            (note?.get("files") as? com.google.gson.JsonArray)
                ?.mapNotNull { it.asString }
                ?.forEach { add(s.resolve(it).absolutePath) }
        }
        // Drift is only worth a git call when the note has actually gone stale — that is the one place
        // the number is shown.
        val since = if (freshness != NoteStore.Freshness.STALE) null else {
            note?.get("analyzedCommit")?.asString?.let { FileFacts.commitsSince(s.root, rel, it) }
        }

        return CodemapState.Loaded(
            rel = rel,
            note = note,
            functionLoc = note?.let { locateFunctions(s, it) }.orEmpty(),
            freshness = freshness,
            commitsSince = since,
            edited = s.edited(note),
            // Cheap after the first read: the cross-note index is cached until `.codemap/` changes.
            // Compared by NOTE path: with a .cpp open, `rel` is the .cpp while the note — and every owner
            // in the index — is the .h, so comparing `rel` made this file's own flows look like someone
            // else's. One walk of the store answers all three cross-note questions.
            appearances = FlowIndex.appearances(all, s.notePath(rel), note),
            packetElsewhere = PacketIndex.elsewhere(all, s.notePath(rel), note),
            packetFlows = packetsOf(note).associateWith { FlowIndex.tracing(all, it) }
                .filterValues { it.isNotEmpty() },
        )
    }

    /**
     * Resolve every function's anchor against the files the note covers.
     *
     * Searched across the whole `.h`/`.cpp` pair because a declaration and its definition live in
     * different files and the note covers both — a function whose anchor is the definition line must
     * still be found when you opened the header.
     */
    private fun locateFunctions(store: NoteStore, note: JsonObject): Map<String, CodemapState.FnLoc> {
        val fns = (note.get("functions") as? com.google.gson.JsonArray) ?: return emptyMap()
        val anchorByName = LinkedHashMap<String, String>()
        fns.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            val anchor = o.get("anchor")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            if (anchor.isNotBlank()) anchorByName[name] = anchor
        }
        if (anchorByName.isEmpty()) return emptyMap()

        val covered = (note.get("files") as? com.google.gson.JsonArray)?.mapNotNull { it.asString }.orEmpty()
        val out = HashMap<String, CodemapState.FnLoc>()
        covered.forEach { relFile ->
            val f = store.resolve(relFile).takeIf { it.isFile } ?: return@forEach
            val remaining = anchorByName.filterKeys { it !in out }
            if (remaining.isEmpty()) return@forEach
            val hits = FileFacts.findAnchors(f, remaining.values.toSet())
            remaining.forEach { (name, anchor) ->
                hits[anchor]?.let { out[name] = CodemapState.FnLoc(relFile, it.line, it.occurrences) }
            }
        }
        return out
    }

    /** Open the editor at a resolved function. Navigation is an EDT action. */
    fun jumpTo(loc: CodemapState.FnLoc) {
        val root = project.getService(CodemapStore::class.java).root ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(File(root, loc.rel).path) ?: return
        ApplicationManager.getApplication().invokeLater {
            OpenFileDescriptor(project, file, (loc.line - 1).coerceAtLeast(0), 0).navigate(true)
        }
    }

    /**
     * Apply a human correction to a field of the current file's note and re-read it.
     *
     * The field is named by [path] rather than mutated by a lambda, because the store has to RECORD what
     * a person changed — that record is what survives the next re-analysis. Provenance is preserved: a
     * wording fix is not a re-analysis, so the note keeps the hashes and date it was written with.
     */
    fun edit(path: List<String>, value: JsonElement) {
        val loaded = state as? CodemapState.Loaded ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { store?.editNote(loaded.rel, path, value) }
            reload()
        }
    }

    /** A note field the person typed over. */
    fun editField(key: String, value: String) = edit(listOf(key), JsonPrimitive(value))

    /** A field of one function's entry. */
    fun editFunction(name: String, key: String, value: String) =
        edit(listOf("functions", name, key), JsonPrimitive(value))

    /** A list of sentences (주의 and friends) the person edited as a whole. */
    fun editList(key: String, values: List<String>) =
        edit(listOf(key), JsonArray().apply { values.forEach { add(it) } })

    fun editFunctionList(name: String, key: String, values: List<String>) =
        edit(listOf("functions", name, key), JsonArray().apply { values.forEach { add(it) } })



    /** The engine the next analysis will use; remembered in settings, changed from the panel. */
    var engine: Engine by mutableStateOf(
        ApplicationManager.getApplication().getService(CodemapSettings::class.java)?.engine ?: Engine.CLAUDE,
    )
        private set

    /** Whether an engine is actually installed — checked for the panel, not at spawn time. */
    fun engineInstalled(e: Engine): Boolean {
        val settings = ApplicationManager.getApplication().getService(CodemapSettings::class.java)
        return e.cli.discover(settings?.pathFor(e)) != null
    }

    fun chooseEngine(e: Engine) {
        engine = e
        ApplicationManager.getApplication().getService(CodemapSettings::class.java)?.engine = e
    }

    /**
     * Ask for one scenario as a sequence diagram, now.
     *
     * Separate from [analyzeNow] because the two write different things: this one only ever ADDS to
     * `flows`, so asking for a fifth diagram cannot cost you the other four, and it leaves the rest of the
     * note — including anything a person corrected — untouched.
     */
    fun addSequence(scenario: String, question: String = "") {
        val loaded = state as? CodemapState.Loaded ?: return
        val s = store ?: return
        if (scenario.isBlank() || analysis is Analysis.Running) return

        analysis = Analysis.Running(loaded.rel)
        val r = AnalysisRunner(s).also { runner = it }
        val chosen = engine
        ApplicationManager.getApplication().executeOnPooledThread {
            val explicit = ApplicationManager.getApplication()
                .getService(CodemapSettings::class.java)?.pathFor(chosen)
            val result = r.analyze(
                loaded.rel, question, flow = scenario, engine = chosen, explicitPath = explicit,
            )
            ApplicationManager.getApplication().invokeLater {
                analysis = when (result) {
                    is AnalysisRunner.Result.Ok -> Analysis.Idle
                    is AnalysisRunner.Result.Failed -> Analysis.Failed(result.reason)
                    // ask() is the chat path; analyze() never returns one.
                    is AnalysisRunner.Result.Answer -> Analysis.Idle
                }
            }
            reload()
        }
    }

    /** The shared conversation for the file on screen — the panel's inline box and the tab are one talk. */
    fun chat(): ChatSessions.Session? {
        val loaded = state as? CodemapState.Loaded ?: return null
        return project.getService(ChatSessions::class.java).of(loaded.rel)
    }

    fun askInline(question: String) {
        val loaded = state as? CodemapState.Loaded ?: return
        project.getService(ChatSessions::class.java).ask(loaded.rel, question)
    }

    fun cancelChat() {
        val loaded = state as? CodemapState.Loaded ?: return
        project.getService(ChatSessions::class.java).cancel(loaded.rel)
    }

    fun pinGotcha(text: String) {
        val loaded = state as? CodemapState.Loaded ?: return
        project.getService(ChatSessions::class.java).pinAsGotcha(loaded.rel, text)
    }

    /** Open the conversation about this file. */
    fun openChat() {
        val loaded = state as? CodemapState.Loaded ?: return
        ApplicationManager.getApplication().invokeLater { openChat(project, loaded.rel) }
    }

    /** Open the sequence viewer tab on one of this file's diagrams. */
    fun openSequence(name: String) {
        val loaded = state as? CodemapState.Loaded ?: return
        ApplicationManager.getApplication().invokeLater { openSequence(project, loaded.rel, name) }
    }

    /** Open a flow that another note holds — the viewer names its owner, since it is not this file's. */
    fun openSequence(entry: FlowIndex.Entry) {
        ApplicationManager.getApplication().invokeLater { openSequence(project, entry.owner, entry.name) }
    }

    /**
     * Run the analysis now, instead of queueing it for someone to run later.
     *
     * The chosen engine is given read-only tools and answers with JSON; [AnalysisRunner] hands that to the
     * store, so the note is still written in exactly one place with the provenance the store stamps. A
     * press costs tokens on your account, which is why the button says 실행.
     */
    fun analyzeNow(question: String, symbol: String = "") {
        val loaded = state as? CodemapState.Loaded ?: return
        val s = store ?: return
        if (analysis is Analysis.Running) return

        analysis = Analysis.Running(if (symbol.isEmpty()) loaded.rel else "${loaded.name} :: $symbol")
        val r = AnalysisRunner(s).also { runner = it }
        val chosen = engine
        ApplicationManager.getApplication().executeOnPooledThread {
            val explicit = ApplicationManager.getApplication()
                .getService(CodemapSettings::class.java)?.pathFor(chosen)
            val result = r.analyze(loaded.rel, question, symbol = symbol, engine = chosen, explicitPath = explicit)
            ApplicationManager.getApplication().invokeLater {
                analysis = when (result) {
                    is AnalysisRunner.Result.Ok -> Analysis.Idle
                    is AnalysisRunner.Result.Failed -> Analysis.Failed(result.reason)
                    // ask() is the chat path; analyze() never returns one.
                    is AnalysisRunner.Result.Answer -> Analysis.Idle
                }
            }
            reload()
        }
    }

    fun cancelAnalysis() {
        runner?.cancel()
        analysis = Analysis.Idle
    }

    /** Open the stitched call graph in its own tab, centred on [name]. */
    fun openGraph(name: String) {
        ApplicationManager.getApplication().invokeLater { openCallGraph(project, name) }
    }

    /** What the usage search is doing for the function currently in focus. */

    /** Publish only if no newer load started meanwhile. */
    private fun publish(gen: Long, produce: () -> CodemapState) {
        ApplicationManager.getApplication().invokeLater {
            if (generation.get() != gen) return@invokeLater
            state = produce()
            if (state is CodemapState.Loaded) primeCaret()
        }
    }

    /**
     * Seed the focused function from where the caret already is.
     *
     * A caret listener only fires when the caret MOVES. Opening a file — including the jump from the
     * function list, which switches tabs — places the caret without moving it, so without this the panel
     * would sit empty while the caret is plainly inside a recorded function.
     */
    private fun primeCaret() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        onCaret(file, editor.caretModel.logicalPosition.line + 1)
    }

    /** Seed from whatever tab is already open when the tool window is first shown. */
    fun primeFromEditor() {
        select(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
    }
}
