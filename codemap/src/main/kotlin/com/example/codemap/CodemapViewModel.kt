package com.example.codemap

import com.google.gson.JsonObject
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
        val pending: NoteStore.Pending?,
        val pendingTotal: Int,
        /** Commits that landed on this file since the note was written; null when unknown. */
        val commitsSince: Int?,
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
    private val generation = AtomicLong()
    private var current: VirtualFile? = null

    fun select(file: VirtualFile?) {
        // Our own graph tab is not a source file; selecting it should leave the note on screen rather
        // than replacing it with "outside the codemap root".
        if (file is CodemapGraphFile) return
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

    fun requestAnalysis(question: String) {
        val loaded = state as? CodemapState.Loaded ?: return
        val reason = if (loaded.note == null) "new" else "stale"
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { store?.addPending(loaded.rel, question, reason) }
            reload()
        }
    }

    private fun load(file: VirtualFile): CodemapState {
        val io = File(file.path)
        val s = store ?: return CodemapState.Outside(file.path, "")
        val rel = s.relativize(io) ?: return CodemapState.Outside(file.path, s.root.absolutePath)
        val note = s.readNote(rel)
        val freshness = s.freshness(note)
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
            pending = s.pendingFor(rel),
            pendingTotal = s.pending().size,
            commitsSince = since,
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
     * Apply a human correction to the current file's note and re-read it.
     *
     * Goes through [NoteStore.editNote], which preserves provenance — a wording fix is not a re-analysis,
     * so the note keeps the hashes and date it was actually written with.
     */
    fun edit(mutate: (JsonObject) -> Unit) {
        val loaded = state as? CodemapState.Loaded ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { store?.editNote(loaded.rel, mutate) }
            reload()
        }
    }



    /**
     * Hand the question "where is this actually called from?" to Rider.
     *
     * The note's call graph is curated and file-scoped; the exhaustive answer lives in the ReSharper
     * backend, which the frontend cannot query directly — but it CAN put the caret on the symbol and
     * let Rider's own action run. The resolution is then the backend's, so overloads, macros and
     * templates come out right, and there is nothing here to keep in sync with a Rider release.
     *
     * The column is found by locating the bare name inside its own anchor line: text arithmetic over a
     * line we already know, not an attempt to parse anything.
     */
    fun showUsages(loc: CodemapState.FnLoc, name: String) {
        val root = project.getService(CodemapStore::class.java).root ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(File(root, loc.rel).path) ?: return
        val bare = name.substringAfterLast("::")
        ApplicationManager.getApplication().invokeLater {
            val doc = FileDocumentManager.getInstance().getDocument(file) ?: return@invokeLater
            val lineIdx = (loc.line - 1).coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0))
            val lineText = doc.getText(TextRange(doc.getLineStartOffset(lineIdx), doc.getLineEndOffset(lineIdx)))
            val column = lineText.indexOf(bare).coerceAtLeast(0)
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, file, lineIdx, column), true) ?: return@invokeLater

            val am = ActionManager.getInstance()
            val action = am.getAction("ShowUsages") ?: am.getAction("FindUsages") ?: return@invokeLater
            val ctx = DataManager.getInstance().getDataContext(editor.contentComponent)
            ActionUtil.invokeAction(action, ctx, ActionPlaces.UNKNOWN, null, null)
        }
    }

    /**
     * Run the analysis now, instead of queueing it for someone to ask Claude about later.
     *
     * The CLI is given read-only tools and answers with JSON on stdout; [AnalysisRunner] hands that to
     * the store, so the note is still written in exactly one place with the provenance the store
     * stamps. A press costs tokens on your Claude account, which is why the button says 실행.
     */
    fun analyzeNow(question: String) {
        val loaded = state as? CodemapState.Loaded ?: return
        val s = store ?: return
        if (analysis is Analysis.Running) return

        analysis = Analysis.Running(loaded.rel)
        val r = AnalysisRunner(s).also { runner = it }
        ApplicationManager.getApplication().executeOnPooledThread {
            val explicit = com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(CodemapSettings::class.java)?.claudePath
            val result = r.analyze(loaded.rel, question, explicitPath = explicit)
            ApplicationManager.getApplication().invokeLater {
                analysis = when (result) {
                    is AnalysisRunner.Result.Ok -> Analysis.Idle
                    is AnalysisRunner.Result.Failed -> Analysis.Failed(result.reason)
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

    /** Publish only if no newer load started meanwhile. */
    private fun publish(gen: Long, produce: () -> CodemapState) {
        ApplicationManager.getApplication().invokeLater {
            if (generation.get() == gen) state = produce()
        }
    }

    /** Seed from whatever tab is already open when the tool window is first shown. */
    fun primeFromEditor() {
        select(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
    }
}
