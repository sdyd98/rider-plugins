package com.example.codemap

import com.intellij.find.FindSettings
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.pom.Navigatable
import com.intellij.usages.UsageInfoAdapter
import com.intellij.usages.UsageViewManager
import com.intellij.usages.rules.UsageInFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real usages, found by Rider, shown by us.
 *
 * The frontend cannot ASK the C++ backend where a symbol is used — there is no such call in the exposed
 * protocol. But it can put the caret on the symbol, run Rider's own Find Usages, and then read the
 * result: Rider materialises its answer into a platform `UsageView`, and every element implements
 * `Usage`, `UsageInFile`, `UsageInfoAdapter` and `Navigatable`. Those are public platform interfaces, so
 * what this depends on is the contract Rider fills in, not the shape of its private classes.
 *
 * Rider's own Find window is a side effect of the action, not the destination; it is hidden again once
 * the results have been read, because the point of this is to keep the answer inside the codemap panel.
 */
object UsageFinder {

    /** One usage, flattened to what a list needs: where it is, what it reads like, and how to go there. */
    data class Hit(
        val filePath: String,
        val fileName: String,
        val line: Int,
        val text: String,
        private val target: Navigatable?,
    ) {
        val canNavigate: Boolean get() = target?.canNavigate() == true
        fun navigate() = target?.takeIf { it.canNavigate() }?.navigate(true)
    }

    sealed interface Result {
        data class Found(val hits: List<Hit>) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Place the caret on [symbol] at [line] of [rel] and collect what Rider finds.
     *
     * Everything runs on the EDT; [onResult] is called there too. The poll exists because the search is
     * asynchronous and the backend answers on its own schedule — there is no completion callback we can
     * reach from here.
     */
    fun find(
        project: Project,
        root: File,
        rel: String,
        line: Int,
        symbol: String,
        onResult: (Result) -> Unit,
    ) {
        ApplicationManager.getApplication().invokeLater {
            // Where the user was, captured before anything moves — searching should not move you, and
            // this search does move the editor: the caret has to sit on the symbol for Rider's action to
            // have a target, and with a single usage the IDE then navigates straight to it.
            val originBefore = FileEditorManager.getInstance(project).selectedTextEditor
            val originFile = originBefore?.let { FileDocumentManager.getInstance().getFile(it.document) }
            val originPos = originBefore?.caretModel?.logicalPosition
            val originLine = originPos?.line ?: 0
            val originCol = originPos?.column ?: 0

            val vf = LocalFileSystem.getInstance().findFileByPath(File(root, rel).path)
                ?: return@invokeLater onResult(Result.Failed("파일을 찾을 수 없습니다: $rel"))
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, vf, 0, 0), true)
                ?: return@invokeLater onResult(Result.Failed("에디터를 열 수 없습니다"))

            val doc = editor.document
            val idx = (line - 1).coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0))
            val lineText = doc.getText(TextRange(doc.getLineStartOffset(idx), doc.getLineEndOffset(idx)))
            val bare = symbol.substringAfterLast("::")
            val col = lineText.indexOf(bare).coerceAtLeast(0)
            editor.caretModel.moveToLogicalPosition(LogicalPosition(idx, col))

            val action = ActionManager.getInstance().getAction("FindUsages")
                ?: return@invokeLater onResult(Result.Failed("FindUsages 액션이 없습니다"))

            // With one usage the IDE normally skips the results tab and jumps straight there. That would
            // navigate the editor out from under us — the panel would follow the new file and the answer
            // we asked for would never be shown. Suppressed for the duration of our own search only.
            val settings = FindSettings.getInstance()
            val skipped = settings.isSkipResultsWithOneUsage
            settings.isSkipResultsWithOneUsage = false

            // Baseline for two things: detecting the single-usage jump, and recognising the declaration
            // itself among the hits. "Who uses this" does not mean the line it is declared on.
            val placed = Placed(vf.path, idx, col)

            val ctx = DataManager.getInstance().getDataContext(editor.contentComponent)
            ActionUtil.invokeAction(action, ctx, ActionPlaces.UNKNOWN, null, null)

            poll(project, 0, placed) { result ->
                settings.isSkipResultsWithOneUsage = skipped
                if (originFile != null) {
                    FileEditorManager.getInstance(project)
                        .openTextEditor(OpenFileDescriptor(project, originFile, originLine, originCol), true)
                }
                onResult(result)
            }
        }
    }

    // 400ms × 75 = 30 seconds. The old 8-second ceiling was fine on a toy project and hopeless on a real
    // one, where the backend can still be settling long after the caret has moved.
    private const val MAX_TRIES = 75

    /** Where the caret was put before the action ran, so a jump away from it can be recognised. */
    private data class Placed(val path: String, val line: Int, val column: Int)

    private fun poll(project: Project, attempt: Int, placed: Placed, onResult: (Result) -> Unit) {
        if (attempt >= MAX_TRIES) {
            hideFindWindow(project)
            onResult(Result.Failed("결과를 받지 못했습니다 (인덱싱 중이거나 사용처가 없습니다)"))
            return
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                val usages = UsageViewManager.getInstance(project).selectedUsageView
                    ?.let { runCatching { it.usages }.getOrNull() }
                    .orEmpty()
                if (usages.isEmpty()) {
                    // With exactly one usage Rider navigates instead of building a view — no amount of
                    // waiting produces one. The jump IS the answer, so read it off the editor rather than
                    // reporting a failure for the most common case of all.
                    jumped(project, placed)?.let { onResult(Result.Found(listOf(it))); return@invokeLater }
                    poll(project, attempt + 1, placed, onResult)
                    return@invokeLater
                }
                val hits = usages.mapNotNull { u ->
                    val path = (u as? UsageInfoAdapter)?.path
                        ?: (u as? UsageInFile)?.file?.path
                        ?: return@mapNotNull null
                    Hit(
                        filePath = path,
                        fileName = path.substringAfterLast('/'),
                        line = ((u as? UsageInfoAdapter)?.line ?: 0) + 1,
                        text = runCatching { u.presentation.plainText.trim() }.getOrDefault(""),
                        target = u as? Navigatable,
                    )
                }
                    .filterNot { it.filePath == placed.path && it.line == placed.line + 1 }
                    .sortedWith(compareBy({ it.filePath }, { it.line }))
                hideFindWindow(project)
                onResult(Result.Found(hits))
            }
        }, 400, TimeUnit.MILLISECONDS)
    }

    /**
     * The single usage Rider jumped to, or null if it did not jump.
     *
     * Position comes from where the IDE actually landed — the backend's own answer — and the text from
     * that line of the document. Nothing here is inferred about the code.
     */
    private fun jumped(project: Project, placed: Placed): Hit? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val pos = editor.caretModel.logicalPosition
        if (file.path == placed.path && pos.line == placed.line) return null

        val doc = editor.document
        val idx = pos.line.coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0))
        val text = doc.getText(TextRange(doc.getLineStartOffset(idx), doc.getLineEndOffset(idx))).trim()
        return Hit(
            filePath = file.path,
            fileName = file.name,
            line = idx + 1,
            text = text,
            target = OpenFileDescriptor(project, file, idx, pos.column),
        )
    }

    /** The Find window opening is a side effect of the action; the results belong in our panel. */
    private fun hideFindWindow(project: Project) {
        runCatching { ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.FIND)?.hide(null) }
    }
}
