package com.example.xlsx

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * "refs.json 초안 생성" — infers a relationship-schema draft from the workbook data (see
 * [SchemaInferencer]) and writes it next to the data. An existing refs.json is never clobbered: the draft
 * goes to refs.draft.json so the user can diff/merge.
 */
class GenerateRefsAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null && dirOf(e) != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = dirOf(e) ?: return
        generateRefsDraft(project, dir)
    }

    private fun dirOf(e: AnActionEvent): File? {
        val project = e.project ?: return null
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            ?: return null
        val path = (if (vf.isDirectory) vf else vf.parent)?.path ?: return null
        return File(path)
    }
}

/** Run the inference off the EDT, write the draft, then open it + report. Shared by the action and the
 *  relationship tool window's empty state. */
fun generateRefsDraft(project: Project, dir: File) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Inferring refs.json from workbook data", true) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            val json = runCatching { SchemaInferencer.draftRefsJson(dir) }
                .getOrElse { notify(project, "refs.json 초안 생성 실패: ${it.message}", NotificationType.ERROR); return }
            val existing = File(dir, "refs.json")
            val target = if (existing.exists()) File(dir, "refs.draft.json") else existing
            runCatching { target.writeText(json) }
                .onFailure { notify(project, "파일 쓰기 실패: ${it.message}", NotificationType.ERROR); return }
            ApplicationManager.getApplication().invokeLater {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(target.path.replace('\\', '/'))?.let {
                    it.refresh(false, false)
                    FileEditorManager.getInstance(project).openFile(it, true)
                }
                val kept = if (existing.exists() && target != existing) " (기존 refs.json은 보존됨)" else ""
                notify(project, "${target.name} 생성 — 검토 후 refs.json으로 사용하세요$kept", NotificationType.INFORMATION)
            }
        }
    })
}

private fun notify(project: Project, message: String, type: NotificationType) =
    NotificationGroupManager.getInstance().getNotificationGroup("관계도")
        .createNotification(message, type).notify(project)
