package com.example.codemap

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.content.ContentFactory
import org.jetbrains.jewel.bridge.JewelComposePanel

/**
 * Hosts the code-understanding note for whatever file is in the foreground editor tab.
 *
 * [DumbAware] so the view stays up while the IDE indexes — it reads `.codemap/` and the file itself
 * directly, and never needs the IDE's indexes.
 */
class CodemapToolWindow : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val vm = CodemapViewModel(project)

        // Follow the selected tab. Connected to the tool window's disposable so the subscription dies
        // with the content rather than leaking for the project's lifetime.
        project.messageBus.connect(toolWindow.disposable)
            .subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun selectionChanged(event: FileEditorManagerEvent) = vm.select(event.newFile)
                },
            )

        // Follow the caret too, so the note narrows to whatever function you are reading. The
        // multicaster covers every editor including ones opened later; the lookup it triggers is a
        // scan of already-resolved anchors, so this stays cheap on a caret that moves constantly.
        EditorFactory.getInstance().eventMulticaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    val editor = event.editor
                    if (editor.project != project) return
                    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
                    vm.onCaret(file, editor.caretModel.logicalPosition.line + 1)
                }
            },
            toolWindow.disposable,
        )

        // Reload when either side of the verdict changes on disk: the note itself (written over MCP, by
        // the CLI, or edited in a tab) or the SOURCE the verdict is about. Watching only the store would
        // leave the panel asserting "분석됨" about a file that has since been edited.
        project.messageBus.connect(toolWindow.disposable)
            .subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        val touched = events.any { e ->
                            e.path.contains("/${CodemapPaths.DIR}/") || vm.dependsOn(e.path)
                        }
                        if (touched) vm.reload()
                    }
                },
            )

        vm.primeFromEditor()

        val panel = JewelComposePanel { CodemapView(vm) }
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
