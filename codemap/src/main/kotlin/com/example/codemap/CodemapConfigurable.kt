package com.example.codemap

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings | Tools | 코드맵 — where the analysis engines live.
 *
 * This page exists because the panel cannot ask. Discovery covers the usual install locations and `PATH`,
 * but a GUI-launched IDE does not inherit a login shell's `PATH`, so a perfectly working `claude` can be
 * invisible to it — and until now the panel could only say "설치를 찾지 못함" with no way to answer. Editing
 * `codemap.xml` by hand was the only fix, which is not a fix.
 *
 * A platform page rather than a field in the tool window, and that is a correctness choice: a Compose text
 * field in that panel takes keyboard focus and swallows the IDE's own shortcuts. Swing here has no such
 * problem, and settings belong in Settings.
 */
class CodemapConfigurable : Configurable {

    private val claude = pathField("claude")
    private val codex = pathField("codex")
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "코드맵"

    override fun createComponent(): JComponent {
        val found = JBLabel(discovered()).apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyTop(8)
        }
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Claude Code 실행 파일"), claude, 1, false)
            .addLabeledComponent(JBLabel("Codex 실행 파일"), codex, 1, false)
            .addComponentToRightColumn(
                JBLabel("비워두면 통상 설치 위치와 PATH 에서 찾습니다.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                },
            )
            .addComponentToRightColumn(found)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .also { panel = it }
    }

    override fun isModified(): Boolean =
        claude.text.trim() != settings.claudePath || codex.text.trim() != settings.codexPath

    override fun apply() {
        settings.claudePath = claude.text.trim()
        settings.codexPath = codex.text.trim()
    }

    override fun reset() {
        claude.text = settings.claudePath
        codex.text = settings.codexPath
    }

    override fun disposeUIResources() { panel = null }

    private val settings: CodemapSettings
        get() = ApplicationManager.getApplication().getService(CodemapSettings::class.java)

    /** What discovery finds right now, so the page answers "is it actually reachable?" without a run. */
    private fun discovered(): String = Engine.entries.joinToString("    ") { e ->
        val at = e.cli.discover(settings.pathFor(e))
        "${e.label}: " + (at?.absolutePath ?: "찾지 못함")
    }

    private fun pathField(binary: String) = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileDescriptor()
                    .withTitle("$binary 실행 파일 선택"),
            ),
        )
    }
}
