package com.example.logview

import com.example.logview.ssh.AddConnectionDialog
import com.example.logview.ssh.RemoteConnectionStore
import com.example.logview.ssh.RemoteEntry
import com.example.logview.ssh.RemoteLogReader
import com.example.logview.ssh.RiderSshConfigs
import com.example.logview.ssh.SshAuth
import com.example.logview.ssh.SshConnectionManager
import com.example.logview.ssh.SshProfile
import com.example.logview.ssh.SshSecrets
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Paths
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The Log Viewer tool window: a left **sources tree** (saved SSH connections + local root folders) and
 * a right area of live-tail session tabs.
 *
 * The key UX: you register a connection / local folder with just a **root directory**, then expand it
 * to get a **list of the log files inside** (lazily SFTP- / disk-listed, newest first — so a rotated
 * `app.log` / `app.2026-06-28.log` set shows the current file on top). Double-click a file to tail it;
 * no need to wire a separate connection per rotated file. Each session is a reusable [LogViewerPanel].
 */
class LogToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val store = RemoteConnectionStore.getInstance()
    private val manager get() = SshConnectionManager.getInstance()

    private val root = DefaultMutableTreeNode("sources")
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = SourceRenderer()
    }

    private val sessions = JBTabbedPane()
    private val sessionCards = JPanel(java.awt.CardLayout()) // swaps the welcome screen ⇄ the session tabs
    private val openPanels = LinkedHashMap<JComponent, LogViewerPanel>()
    private val openByKey = HashMap<String, JComponent>() // source key → tab, so re-opening a file reuses its tab

    // Repaint the tree's connection status dots whenever a session connects/disconnects.
    private val statusListener: () -> Unit = { ApplicationManager.getApplication().invokeLater { tree.repaint() } }

    init {
        // A draggable divider lets the user resize the sources tree vs the session area.
        val split = com.intellij.ui.OnePixelSplitter(false, 0.24f).apply {
            firstComponent = buildSources()
            secondComponent = buildSessions()
        }
        add(split, BorderLayout.CENTER)
        rebuildRoot()
        wireTree()
        manager.addStatusListener(statusListener)

        // Ctrl+Alt+X — close all session tabs but the active one (works from the tree or a viewer).
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK), "closeOthers")
        actionMap.put("closeOthers", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = closeOtherSessions()
        })
    }

    // ---- Source kinds (tree node user objects) ----

    private sealed class Src {
        class Header(val title: String) : Src() // a non-actionable section header (원격 연결 / 로컬 폴더)
        class Conn(val profile: SshProfile) : Src()
        class RemoteDir(val profile: SshProfile, val path: String, val label: String) : Src()
        class RemoteFile(val profile: SshProfile, val path: String, val name: String, val meta: String) : Src()
        class LocalRoot(val path: String) : Src()
        class LocalDir(val path: String, val label: String) : Src()
        class LocalFile(val path: String, val name: String, val meta: String) : Src()
        object Loading : Src()
    }

    private fun expandable(src: Src): DefaultMutableTreeNode =
        DefaultMutableTreeNode(src).apply { add(DefaultMutableTreeNode(Src.Loading)) }

    // ---- UI ----

    private fun buildSources(): JComponent {
        val group = DefaultActionGroup().apply {
            add(act("연결 추가", AllIcons.General.Add) { addConnection() })
            add(act("로컬 폴더 추가", AllIcons.Nodes.Folder) { addLocalRoot() })
            add(act("파일 열기", AllIcons.Actions.MenuOpen) { openLocalFile() })
            addSeparator()
            add(act("새로고침", AllIcons.Actions.Refresh) { rebuildRoot() })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("LogViewerSources", group, true).apply {
            targetComponent = tree
        }
        tree.rowHeight = JBUI.scale(24) // roomier, modern spacing
        tree.isOpaque = true
        tree.putClientProperty(RenderingUtil.PAINT_HOVERED_BACKGROUND, true) // native New-UI hover highlight
        wireTreeVim()
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(280), 0)
            minimumSize = Dimension(JBUI.scale(140), 0)
            add(toolbar.component, BorderLayout.NORTH)
            add(JBScrollPane(tree).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }
    }

    private fun buildSessions(): JComponent {
        val welcome = org.jetbrains.jewel.bridge.JewelComposePanel {
            EmptyState(
                glyph = "🛰",
                title = "로그 뷰어",
                subtitle = "왼쪽에서 연결 또는 로컬 폴더를 추가하고, 펼쳐서 로그 파일을 선택하세요.",
                actionLabel = "연결 추가",
                onAction = { addConnection() },
            )
        }
        sessionCards.add(welcome, "welcome")
        sessionCards.add(JPanel(BorderLayout()).apply { add(sessions, BorderLayout.CENTER) }, "tabs")
        showSessionCard()
        return sessionCards
    }

    /** Welcome screen when no logs are open; the tab strip once a session exists (no standalone "시작" tab). */
    private fun showSessionCard() {
        (sessionCards.layout as java.awt.CardLayout).show(sessionCards, if (sessions.tabCount == 0) "welcome" else "tabs")
    }

    private fun wireTree() {
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                if (needsLoad(node)) loadChildren(node)
            }
            override fun treeCollapsed(event: TreeExpansionEvent) {}
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedSrc()?.let { openIfFile(it) }
            }
            override fun mousePressed(e: MouseEvent) = maybePopup(e)
            override fun mouseReleased(e: MouseEvent) = maybePopup(e)
        })
    }

    private fun selectedNode(): DefaultMutableTreeNode? = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
    private fun selectedSrc(): Src? = selectedNode()?.userObject as? Src

    private fun needsLoad(node: DefaultMutableTreeNode): Boolean =
        node.childCount == 1 && (node.firstChild as? DefaultMutableTreeNode)?.userObject == Src.Loading

    // ---- Lazy loading ----

    private fun loadChildren(node: DefaultMutableTreeNode) {
        when (val src = node.userObject as? Src) {
            is Src.Conn -> loadConnRoots(node, src.profile)
            is Src.RemoteDir -> loadRemote(node, src.profile, src.path)
            is Src.LocalRoot -> loadLocal(node, src.path)
            is Src.LocalDir -> loadLocal(node, src.path)
            else -> {}
        }
    }

    /** A connection is a GROUP: its children are its log roots (starts at `/`; pin more via right-click). */
    private fun loadConnRoots(node: DefaultMutableTreeNode, profile: SshProfile) {
        node.removeAllChildren()
        val roots = profile.roots.filter { it.isNotBlank() }.distinct().ifEmpty { listOf("/") }
        if (roots.isEmpty()) node.add(infoNode("(루트 없음)"))
        for (r in roots) node.add(expandable(Src.RemoteDir(profile, r, if (r == "/") "/  (루트)" else r)))
        treeModel.nodeStructureChanged(node)
    }

    private fun isRiderProfile(profile: SshProfile) = profile.id.startsWith("rider:")

    // ---- Vim navigation in the sources tree (j/k/h/l/Enter) ----

    private fun wireTreeVim() {
        val im = tree.getInputMap(JComponent.WHEN_FOCUSED)
        val am = tree.actionMap
        fun bind(stroke: KeyStroke, name: String, run: () -> Unit) {
            im.put(stroke, name)
            am.put(name, object : AbstractAction() { override fun actionPerformed(e: ActionEvent) = run() })
        }
        bind(KeyStroke.getKeyStroke('j'), "treevim.j") { moveTreeSel(1) }
        bind(KeyStroke.getKeyStroke('k'), "treevim.k") { moveTreeSel(-1) }
        bind(KeyStroke.getKeyStroke('l'), "treevim.l") { treeEnter() }
        bind(KeyStroke.getKeyStroke('h'), "treevim.h") { treeCollapse() }
        bind(KeyStroke.getKeyStroke('g'), "treevim.g") { moveTreeTo(0) }
        bind(KeyStroke.getKeyStroke('G'), "treevim.G") { moveTreeTo(tree.rowCount - 1) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK), "treevim.cd") { treeHalfPage(1) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK), "treevim.cu") { treeHalfPage(-1) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "treevim.enter") { treeEnter() }
    }

    private fun treeHalfPage(dir: Int) {
        val rows = maxOf(1, tree.visibleRect.height / maxOf(1, tree.rowHeight))
        moveTreeSel(dir * maxOf(1, rows / 2))
    }

    /** Focus the tree (selecting the first row if nothing is selected) — the viewer's `h`-past-Time target. */
    private fun focusTree() {
        if (tree.selectionCount == 0 && tree.rowCount > 0) tree.setSelectionRow(0)
        tree.requestFocusInWindow()
    }

    private fun moveTreeSel(d: Int) {
        if (tree.rowCount == 0) return
        val cur = if (tree.leadSelectionRow >= 0) tree.leadSelectionRow else 0
        moveTreeTo((cur + d).coerceIn(0, tree.rowCount - 1))
    }

    private fun moveTreeTo(row: Int) {
        if (row in 0 until tree.rowCount) { tree.setSelectionRow(row); tree.scrollRowToVisible(row) }
    }

    /** `l` / Enter: open a log file (focus jumps into the viewer) or expand a folder. */
    private fun treeEnter() {
        val path = tree.selectionPath ?: run { moveTreeTo(0); return }
        when (val src = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Src) {
            is Src.RemoteFile -> if (src.path.isNotBlank()) openRemote(src.profile, src.path)
            is Src.LocalFile -> openLocalPath(src.path, src.name)
            null -> {}
            else -> if (!tree.isExpanded(path)) tree.expandPath(path) else moveTreeSel(1)
        }
    }

    /** `h`: collapse an open folder, else step up to the parent. */
    private fun treeCollapse() {
        val path = tree.selectionPath ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode
        if (node != null && !node.isLeaf && tree.isExpanded(path)) {
            tree.collapsePath(path)
        } else {
            val parent = path.parentPath
            if (parent != null && parent.lastPathComponent !== root) moveTreeTo(tree.getRowForPath(parent))
        }
    }

    private fun loadRemote(node: DefaultMutableTreeNode, profile: SshProfile, dir: String) =
        ensureSecret(profile) { doLoadRemote(node, profile, dir) }

    /**
     * Ensure we have a password before connecting. Key auth + already-cached secrets proceed straight
     * through; a password-auth host with no cached secret (e.g. a Rider config, whose stored password is
     * a protected API we can't read) is prompted ONCE and cached in [SshSecrets] keyed by the profile id.
     */
    private fun ensureSecret(profile: SshProfile, onReady: () -> Unit) {
        if (profile.auth == SshAuth.KEY) { onReady(); return }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (SshSecrets.getSecret(profile.id) != null) {
                ApplicationManager.getApplication().invokeLater { onReady() }
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater {
                val pw = Messages.showPasswordDialog(project, "${profile.label()}\nSSH 비밀번호를 입력하세요 (한 번만 저장됩니다).", "SSH 비밀번호", null)
                if (!pw.isNullOrEmpty()) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        SshSecrets.setSecret(profile.id, pw)
                        ApplicationManager.getApplication().invokeLater { onReady() }
                    }
                }
            }
        }
    }

    /** Clear a (Rider) host's cached password and re-prompt — for a wrong/changed password. */
    private fun resetSecret(profile: SshProfile) {
        ApplicationManager.getApplication().executeOnPooledThread {
            SshSecrets.clear(profile.id)
            manager.disconnect(profile.id)
            ApplicationManager.getApplication().invokeLater { ensureSecret(profile) { tree.repaint() } }
        }
    }

    private fun doLoadRemote(node: DefaultMutableTreeNode, profile: SshProfile, dir: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val listed = try {
                manager.listDir(profile, dir)
            } catch (e: Throwable) {
                ApplicationManager.getApplication().invokeLater { setError(node, "조회 실패: ${e.message}") }
                return@executeOnPooledThread
            }
            // Only .log files and folders that (recursively) hold .log files — decided by one `find`.
            val entries = filterRemoteEntries(profile, dir, listed)
            ApplicationManager.getApplication().invokeLater {
                node.removeAllChildren()
                if (entries.isEmpty()) node.add(infoNode("(.log 파일 없음)"))
                for (e in entries) {
                    val full = joinPath(dir, e.name)
                    node.add(
                        if (e.isDirectory) expandable(Src.RemoteDir(profile, full, e.name))
                        else DefaultMutableTreeNode(Src.RemoteFile(profile, full, e.name, "${humanSize(e.size)} · ${relTime(e.mtimeSec)}")),
                    )
                }
                treeModel.nodeStructureChanged(node)
            }
        }
    }

    private fun loadLocal(node: DefaultMutableTreeNode, dir: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val all = File(dir).listFiles()?.toList() ?: emptyList()
            // Only .log files and folders that (recursively) hold .log files — hide everything else.
            val budget = intArrayOf(2000)
            val files = all.filter { if (it.isDirectory) localDirHasLog(it, 0, budget) else isLogFile(it.name) }
            val sorted = files.sortedWith(
                compareByDescending<File> { it.isDirectory }
                    .thenByDescending { if (it.isDirectory) 0L else it.lastModified() }
                    .thenBy { it.name.lowercase() },
            )
            ApplicationManager.getApplication().invokeLater {
                node.removeAllChildren()
                if (sorted.isEmpty()) node.add(infoNode("(.log 파일 없음)"))
                for (f in sorted) {
                    node.add(
                        if (f.isDirectory) expandable(Src.LocalDir(f.path, f.name))
                        else DefaultMutableTreeNode(Src.LocalFile(f.path, f.name, "${humanSize(f.length())} · ${relTime(f.lastModified() / 1000)}")),
                    )
                }
                treeModel.nodeStructureChanged(node)
            }
        }
    }

    private fun infoNode(text: String) = DefaultMutableTreeNode(Src.RemoteFile(SshProfile(), "", text, "")) // leaf, non-openable (blank path)

    private fun setError(node: DefaultMutableTreeNode, message: String) {
        node.removeAllChildren()
        node.add(infoNode(message))
        treeModel.nodeStructureChanged(node)
    }

    private fun refreshNode(node: DefaultMutableTreeNode) {
        node.removeAllChildren()
        node.add(DefaultMutableTreeNode(Src.Loading))
        treeModel.nodeStructureChanged(node)
        loadChildren(node)
    }

    private fun rebuildRoot() {
        root.removeAllChildren()
        // "원격 연결" = the connections the user picked from Rider's SSH configs (see addConnection).
        if (store.profiles.isNotEmpty()) {
            val h = DefaultMutableTreeNode(Src.Header("원격 연결"))
            store.profiles.forEach { h.add(expandable(Src.Conn(it))) }
            root.add(h)
        }
        if (store.localRoots.isNotEmpty()) {
            val h = DefaultMutableTreeNode(Src.Header("로컬 폴더"))
            store.localRoots.forEach { h.add(expandable(Src.LocalRoot(it))) }
            root.add(h)
        }
        treeModel.nodeStructureChanged(root)
        // Section headers open by default so connections / folders are visible at a glance.
        for (i in 0 until root.childCount) {
            tree.expandPath(javax.swing.tree.TreePath((root.getChildAt(i) as DefaultMutableTreeNode).path))
        }
    }

    // ---- Open / context menu ----

    private fun openIfFile(src: Src) {
        when (src) {
            is Src.RemoteFile -> if (src.path.isNotBlank()) openRemote(src.profile, src.path)
            is Src.LocalFile -> openLocalPath(src.path, src.name)
            else -> {}
        }
    }

    private fun maybePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        tree.selectionPath = path
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val group = DefaultActionGroup()
        when (val src = node.userObject as? Src) {
            is Src.Conn -> {
                group.add(act("로그 디렉터리 추가…", AllIcons.General.Add) { addRootToConn(src) })
                group.add(act("직접 경로 열기…", AllIcons.Actions.MenuOpen) { openProfilePrompt(src.profile) })
                group.add(act("새로고침", AllIcons.Actions.Refresh) { refreshNode(node) })
                if (manager.isConnected(src.profile.id)) {
                    val id = src.profile.id
                    group.add(act("연결 끊기", AllIcons.Actions.Suspend) { pooled { manager.disconnect(id) } })
                }
                group.addSeparator()
                if (isRiderProfile(src.profile)) {
                    if (src.profile.auth == SshAuth.PASSWORD) group.add(act("비밀번호 다시 입력", AllIcons.General.Settings) { resetSecret(src.profile) })
                } else {
                    group.add(act("편집…", AllIcons.Actions.Edit) { editConnection(src.profile) })
                }
                group.add(act("목록에서 제거", AllIcons.General.Remove) { deleteConnection(src.profile) })
            }
            is Src.RemoteDir -> {
                group.add(act("이 폴더를 로그 루트로 고정", AllIcons.General.Add) { pinRemoteRoot(src) })
                group.add(act("새로고침", AllIcons.Actions.Refresh) { refreshNode(node) })
            }
            is Src.RemoteFile -> if (src.path.isNotBlank()) group.add(act("열기", AllIcons.Actions.MenuOpen) { openRemote(src.profile, src.path) })
            is Src.LocalRoot -> {
                group.add(act("새로고침", AllIcons.Actions.Refresh) { refreshNode(node) })
                group.add(act("목록에서 제거", AllIcons.General.Remove) { store.removeLocalRoot(src.path); rebuildRoot() })
            }
            is Src.LocalDir -> group.add(act("새로고침", AllIcons.Actions.Refresh) { refreshNode(node) })
            is Src.LocalFile -> group.add(act("열기", AllIcons.Actions.MenuOpen) { openLocalPath(src.path, src.name) })
            else -> return
        }
        if (group.childrenCount == 0) return
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataContext.EMPTY_CONTEXT, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .show(RelativePoint(tree, e.point))
    }

    private fun act(text: String, icon: Icon, run: () -> Unit) =
        object : DumbAwareAction(text, null, icon) { override fun actionPerformed(e: AnActionEvent) = run() }

    private fun pooled(run: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread(run)
    }

    /** Pin a log directory under a connection (persisted to the profile's roots). */
    private fun addRootToConn(src: Src.Conn) {
        val path = Messages.showInputDialog(project, "추가할 로그 디렉터리 경로", "로그 디렉터리 추가 — ${src.profile.label()}", null, "/var/log/", null)
        if (path.isNullOrBlank()) return
        pinRoot(src.profile, path.trim())
    }

    private fun pinRemoteRoot(src: Src.RemoteDir) = pinRoot(src.profile, src.path)

    private fun pinRoot(profile: SshProfile, path: String) {
        if (path !in profile.roots) profile.roots.add(path)
        store.add(profile)
        rebuildRoot()
    }

    // ---- Connections ----

    /** "연결 추가" — choose from Rider's SSH configs ONLY (no manual host entry). Picked configs are
     *  added to "원격 연결"; their host/user/key come from Rider, the password is prompted once on use. */
    private fun addConnection() {
        val available = runCatching { RiderSshConfigs.profiles(project) }.getOrDefault(emptyList())
            .filter { store.find(it.id) == null } // hide already-added ones
        if (available.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "추가할 수 있는 Rider SSH 설정이 없습니다.\nRider의 Settings → Tools → SSH Configurations 에서 먼저 등록하세요.\n(이미 추가된 연결은 목록에 나타나지 않습니다.)",
                "원격 연결 추가",
            )
            return
        }
        val group = DefaultActionGroup()
        for (p in available) group.add(act(p.label(), AllIcons.Webreferences.Server) { store.add(p); rebuildRoot() })
        JBPopupFactory.getInstance()
            .createActionGroupPopup("Rider SSH 설정에서 선택", group, DataContext.EMPTY_CONTEXT, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .show(RelativePoint(tree, java.awt.Point(0, 0)))
    }

    private fun editConnection(profile: SshProfile) = openConnectionDialog(profile)

    /**
     * Read the existing secret OFF the EDT (PasswordSafe.get is a prohibited slow op on the EDT), show
     * the dialog, then persist the entered secret OFF the EDT too. Profile metadata is saved on the EDT.
     */
    private fun openConnectionDialog(existing: SshProfile?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val initialSecret = existing?.let { SshSecrets.getSecret(it.id) }
            ApplicationManager.getApplication().invokeLater {
                val dialog = AddConnectionDialog(project, existing, initialSecret)
                if (dialog.showAndGet()) {
                    val profile = dialog.result()
                    val secret = dialog.enteredSecret()
                    store.add(profile)
                    rebuildRoot()
                    ApplicationManager.getApplication().executeOnPooledThread { SshSecrets.setSecret(profile.id, secret) }
                }
            }
        }
    }

    private fun deleteConnection(profile: SshProfile) {
        val ok = Messages.showYesNoDialog(project, "'${profile.label()}' 연결을 삭제할까요?", "연결 삭제", null) == Messages.YES
        if (ok) {
            store.remove(profile.id)
            rebuildRoot()
            val id = profile.id
            // Clearing the secret (PasswordSafe.set) and closing the session are slow ops → off the EDT.
            ApplicationManager.getApplication().executeOnPooledThread {
                SshSecrets.clear(id)
                manager.disconnect(id)
            }
        }
    }

    // ---- Local roots ----

    private fun addLocalRoot() {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false).withTitle("로그 루트 폴더 선택")
        val dir = FileChooser.chooseFile(descriptor, project, null) ?: return
        store.addLocalRoot(dir.path)
        rebuildRoot()
    }

    // ---- Opening sessions ----

    private fun openProfilePrompt(profile: SshProfile) {
        val default = profile.roots.firstOrNull().orEmpty().ifBlank { "/var/log/" }
        val path = Messages.showInputDialog(project, "원격 로그 경로", "로그 열기 — ${profile.label()}", null, default, null)
        if (!path.isNullOrBlank()) openRemote(profile, path)
    }

    private fun openRemote(profile: SshProfile, path: String) = ensureSecret(profile) {
        val key = "${profile.user}@${profile.host}:$path"
        if (focusExisting(key)) return@ensureSecret // already open → reuse the tab (keeps its position/focus)
        val label = "${profile.name.ifBlank { profile.host }}:${path.substringAfterLast('/')}"
        addSession(label, key, { cs -> RemoteLogReader(manager, profile, path, profile.tailLines, cs) }, followByDefault = true)
    }

    private fun openLocalFile() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false).withTitle("로컬 로그 열기")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        openLocalPath(file.path, file.name)
    }

    /** Open a local file path as a live-tail session (called by the "Open in Log Viewer" action too). */
    fun openLocalPath(path: String, name: String) {
        if (focusExisting(path)) return // already open → reuse the tab
        addSession(name, path, { cs -> LocalLogReader(Paths.get(path), cs) }, followByDefault = true)
    }

    /** If [key]'s source is already open, select + focus that tab and return true; else false. */
    private fun focusExisting(key: String): Boolean {
        val comp = openByKey[key] ?: return false
        if (sessions.indexOfComponent(comp) < 0) { openByKey.remove(key); return false }
        sessions.selectedComponent = comp
        showSessionCard()
        openPanels[comp]?.preferredFocus?.requestFocusInWindow()
        return true
    }

    private fun addSession(
        title: String,
        sourceLabel: String,
        makeReader: (Charset) -> LogReader,
        followByDefault: Boolean,
    ) {
        val panel = LogViewerPanel(
            project, sourceLabel, makeReader, followByDefault,
            onExitLeft = { focusTree() },
            onSwitchTab = { dir -> switchSessionTab(dir) },
        )
        val comp = panel.component
        openPanels[comp] = panel
        openByKey[sourceLabel] = comp
        sessions.addTab(title, comp)
        val index = sessions.indexOfComponent(comp)
        sessions.setTabComponentAt(index, closableTabHeader(title, comp))
        sessions.selectedComponent = comp
        showSessionCard()
        panel.start()
        panel.preferredFocus.requestFocusInWindow()
    }

    private fun closableTabHeader(title: String, comp: JComponent): JComponent {
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        header.add(JBLabel(title))
        header.add(JBLabel(" ✕").apply {
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = closeSession(comp)
            })
        })
        return header
    }

    private fun closeSession(comp: JComponent) {
        val index = sessions.indexOfComponent(comp)
        if (index >= 0) sessions.removeTabAt(index)
        openPanels.remove(comp)?.dispose()
        openByKey.values.remove(comp)
        showSessionCard()
    }

    /** Ctrl+Alt+X — close every session tab except the active one. */
    private fun closeOtherSessions() {
        val keep = sessions.selectedComponent ?: return
        for (comp in openPanels.keys.toList()) if (comp !== keep) closeSession(comp)
    }

    /** `gt` / `gT` from inside a viewer — cycle session tabs and focus the new one's grid. */
    private fun switchSessionTab(dir: Int) {
        val count = sessions.tabCount
        if (count <= 1) return
        sessions.selectedIndex = ((sessions.selectedIndex + dir) % count + count) % count
        openPanels[sessions.selectedComponent]?.preferredFocus?.requestFocusInWindow()
    }

    // ---- Helpers ----

    private fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) dir + name else "$dir/$name"

    // ---- .log filtering: only show .log files + folders that (recursively) contain them ----

    private fun isLogFile(name: String): Boolean = LOG_FILE_RE.matches(name)

    /** True if [dir] holds a .log file within [MAX_SCAN_DEPTH]; a shared listing [budget] caps the scan
     *  (when exhausted the folder is kept/shown rather than scanned further, so it never hangs). */
    private fun localDirHasLog(dir: File, depth: Int, budget: IntArray): Boolean {
        if (budget[0] <= 0) return true
        if (depth > MAX_SCAN_DEPTH) return false
        budget[0]--
        val children = dir.listFiles() ?: return false
        if (children.any { !it.isDirectory && isLogFile(it.name) }) return true
        for (c in children) if (c.isDirectory && localDirHasLog(c, depth + 1, budget)) return true
        return false
    }

    private fun remoteDirHasLog(profile: SshProfile, path: String, depth: Int, budget: IntArray): Boolean {
        if (budget[0] <= 0 || depth > MAX_SCAN_DEPTH) return false // give up → hide (don't show log-less folders)
        budget[0]--
        val entries = runCatching { manager.listDir(profile, path) }.getOrNull() ?: return false
        if (entries.any { !it.isDirectory && isLogFile(it.name) }) return true
        for (e in entries) if (e.isDirectory && remoteDirHasLog(profile, joinPath(path, e.name), depth + 1, budget)) return true
        return false
    }

    /** Show, among the immediate [listed] entries, the .log files + the subdirs that lead to a .log (one `find`). */
    private fun filterRemoteEntries(profile: SshProfile, dir: String, listed: List<RemoteEntry>): List<RemoteEntry> {
        val logPaths = runCatching { manager.findLogs(profile, dir, MAX_SCAN_DEPTH) }.getOrNull()
        if (logPaths != null) {
            val base = if (dir.endsWith("/")) dir else "$dir/"
            val subdirs = HashSet<String>()
            for (p in logPaths) {
                if (!p.startsWith(base)) continue
                val rel = p.substring(base.length)
                val slash = rel.indexOf('/')
                if (slash > 0) subdirs.add(rel.substring(0, slash))
            }
            return listed.filter { if (it.isDirectory) it.name in subdirs else isLogFile(it.name) }
        }
        // Fallback when `find` is unavailable: bounded recursive SFTP (hides folders with no .log in budget).
        val budget = intArrayOf(120)
        return listed.filter { if (it.isDirectory) remoteDirHasLog(profile, joinPath(dir, it.name), 0, budget) else isLogFile(it.name) }
    }

    private fun humanSize(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> "${b / 1024}KB"
        else -> "%.1fMB".format(b / (1024.0 * 1024))
    }

    private fun relTime(epochSec: Long): String {
        if (epochSec <= 0) return ""
        val diff = System.currentTimeMillis() / 1000 - epochSec
        return when {
            diff < 0 -> ""
            diff < 60 -> "방금"
            diff < 3600 -> "${diff / 60}분 전"
            diff < 86400 -> "${diff / 3600}시간 전"
            else -> "${diff / 86400}일 전"
        }
    }

    private inner class SourceRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val s = node.userObject) {
                is Src.Header -> append(s.title, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.GRAY))
                is Src.Conn -> {
                    icon = AllIcons.Webreferences.Server
                    val connected = manager.isConnected(s.profile.id)
                    append("● ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, if (connected) CONNECTED_DOT else JBColor.GRAY))
                    append(s.profile.name.ifBlank { s.profile.host }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${s.profile.user}@${s.profile.host}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is Src.RemoteDir -> { icon = AllIcons.Nodes.Folder; append(s.label) }
                is Src.RemoteFile -> appendFile(s.name, s.meta, s.path.isNotBlank())
                is Src.LocalRoot -> {
                    icon = AllIcons.Nodes.Folder
                    val f = File(s.path)
                    append(f.name.ifBlank { s.path }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    f.parent?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                is Src.LocalDir -> { icon = AllIcons.Nodes.Folder; append(s.label) }
                is Src.LocalFile -> appendFile(s.name, s.meta, true)
                Src.Loading -> append("로딩…", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                else -> append(node.userObject?.toString() ?: "")
            }
        }

        private fun appendFile(name: String, meta: String, openable: Boolean) {
            if (openable) icon = AllIcons.FileTypes.Text
            append(name)
            if (meta.isNotEmpty()) append("   $meta", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    override fun dispose() {
        manager.removeStatusListener(statusListener)
        openPanels.values.forEach { it.dispose() }
        openPanels.clear()
    }

    companion object {
        private const val MAX_SCAN_DEPTH = 4 // how deep to look for a .log when deciding to show a folder
        private val LOG_FILE_RE = Regex(""".+\.log(\.[\w.\-]+)?""", RegexOption.IGNORE_CASE) // app.log, app.log.1, app.2026-06-28.log
        private val CONNECTED_DOT = JBColor(0x59A869, 0x5FAD65) // green = live SSH session
    }
}
