package com.example.logview.ssh

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.util.UUID
import javax.swing.JComponent

/**
 * Collects (or edits) an [SshProfile]. The secret (password / key passphrase) is written to the secure
 * [SshSecrets] keyed by the profile id — never serialized with the profile. A "연결 테스트" button does a
 * throwaway connect so the user gets immediate feedback before saving.
 */
class AddConnectionDialog(
    private val project: Project?,
    private val existing: SshProfile? = null,
    // The stored secret, read OFF the EDT by the caller (PasswordSafe.get is a prohibited slow op on
    // the EDT). Also the value restored if a "Test" is run and then the dialog is cancelled.
    private val initialSecret: String? = null,
) : DialogWrapper(project) {

    private val profileId = existing?.id ?: UUID.randomUUID().toString()

    private val nameField = JBTextField(existing?.name ?: "")
    private val hostField = JBTextField(existing?.host ?: "")
    private val portField = JBTextField((existing?.port ?: 22).toString())
    private val userField = JBTextField(existing?.user ?: "")
    private val authCombo = com.intellij.openapi.ui.ComboBox(SshAuth.entries.toTypedArray()).apply {
        selectedItem = existing?.auth ?: SshAuth.PASSWORD
    }
    private val passwordField = JBPasswordField().apply { text = initialSecret ?: "" }
    private val keyPathField = TextFieldWithBrowseButton().apply {
        text = existing?.privateKeyPath ?: ""
        addActionListener {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, false).withTitle("개인 키 선택")
            FileChooser.chooseFile(descriptor, project, null)?.let { text = it.path }
        }
    }
    private val tailField = JBTextField((existing?.tailLines ?: 5000).toString())

    // Rider's saved SSH configs (Settings → Tools → SSH Configurations) for the import dropdown.
    private val riderItems: List<Pair<String, SshProfile>> =
        project?.let { runCatching { RiderSshConfigs.importable(it) }.getOrDefault(emptyList()) } ?: emptyList()
    private val riderCombo = com.intellij.openapi.ui.ComboBox(
        (listOf(DIRECT_INPUT) + riderItems.map { it.first }).toTypedArray(),
    ).apply {
        addActionListener { if (selectedIndex in 1..riderItems.size) applyToFields(riderItems[selectedIndex - 1].second) }
    }

    @Volatile private var okPressed = false

    init {
        title = if (existing == null) "원격 연결 추가" else "원격 연결 편집"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
        // Reuse a host the user already configured in Rider — pick it from the dropdown to prefill.
        if (riderItems.isNotEmpty()) {
            builder.addLabeledComponent("Rider SSH 설정", riderCombo).addSeparator()
        }
        return builder
            .addLabeledComponent("이름", nameField)
            .addLabeledComponent("호스트", hostField)
            .addLabeledComponent("포트", portField)
            .addLabeledComponent("사용자", userField)
            .addLabeledComponent("인증 방식", authCombo)
            .addLabeledComponent("비밀번호", passwordField)
            .addLabeledComponent("개인 키 파일", keyPathField)
            .addLabeledComponent("초기 tail 줄 수", tailField)
            .panel
    }

    override fun doValidate(): ValidationInfo? {
        if (hostField.text.isBlank()) return ValidationInfo("호스트를 입력하세요.", hostField)
        if (userField.text.isBlank()) return ValidationInfo("사용자를 입력하세요.", userField)
        if (portField.text.toIntOrNull() == null) return ValidationInfo("포트는 숫자여야 합니다.", portField)
        return null
    }

    override fun createActions() = arrayOf(testAction(), *super.createActions())

    private fun testAction() = object : javax.swing.AbstractAction("연결 테스트") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = testConnection()
    }

    private fun testConnection() {
        val profile = buildProfile() ?: return
        val secret = String(passwordField.password).ifEmpty { null }
        var error: Throwable? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            // PasswordSafe.set + the SSH connect are both slow ops — this block runs OFF the EDT.
            SshSecrets.setSecret(profile.id, secret)
            try {
                SshConnectionManager.getInstance().testConnection(profile)
            } catch (t: Throwable) {
                error = t
            }
        }, "연결 테스트 중…", true, project)
        val failure = error
        if (failure == null) {
            Messages.showInfoMessage(project, "연결 성공: ${profile.user}@${profile.host}", "연결 테스트")
        } else {
            Messages.showErrorDialog(project, "연결 실패: ${failure.message}", "연결 테스트")
        }
    }

    /** Prefill the form from an imported Rider config (secret is NOT imported — enter it / use a key). */
    private fun applyToFields(p: SshProfile) {
        if (p.name.isNotBlank()) nameField.text = p.name
        hostField.text = p.host
        portField.text = p.port.toString()
        userField.text = p.user
        authCombo.selectedItem = p.auth
        keyPathField.text = p.privateKeyPath
    }

    private fun buildProfile(): SshProfile? {
        val port = portField.text.toIntOrNull() ?: return null
        return SshProfile(
            id = profileId,
            name = nameField.text.trim(),
            host = hostField.text.trim(),
            port = port,
            user = userField.text.trim(),
            auth = authCombo.selectedItem as? SshAuth ?: SshAuth.PASSWORD,
            privateKeyPath = keyPathField.text.trim(),
            // No log dir is asked up front — connect at the root and let the user pin dirs via right-click.
            roots = (existing?.roots ?: mutableListOf("/")),
            tailLines = tailField.text.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 5000,
        )
    }

    /** The edited/created profile (call only after the dialog is OK'd). */
    fun result(): SshProfile = buildProfile()!!

    /** The typed secret (password / passphrase), or null. The caller persists it OFF the EDT. */
    fun enteredSecret(): String? = String(passwordField.password).ifEmpty { null }

    override fun doOKAction() {
        if (buildProfile() == null) return
        okPressed = true
        super.doOKAction() // the caller persists the profile + secret (secret off the EDT)
    }

    override fun dispose() {
        // A "Test" writes the typed secret to PasswordSafe before any save; if the user cancelled,
        // restore the original value. PasswordSafe.set is a slow op → do it off the EDT. (No-op-safe if
        // no test ran: it just rewrites the same value / clears a never-set new-profile id.)
        if (!okPressed) {
            val id = profileId
            val orig = initialSecret
            ApplicationManager.getApplication().executeOnPooledThread { SshSecrets.setSecret(id, orig) }
        }
        super.dispose()
    }

    companion object {
        private const val DIRECT_INPUT = "(직접 입력)"
    }
}
