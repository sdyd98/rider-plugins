package com.example.xlsx

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * The game-data ROOT folders the user has designated in THIS project. Persisted in workspace.xml
 * ([StoragePathMacros.WORKSPACE_FILE]) — which is per-checkout and NOT committed — so every branch
 * working-copy / git worktree on the machine keeps its own roots. You designate once per checkout.
 *
 * A root is the folder that owns `refs.json`; its tables may live in nested subfolders (refs.json addresses
 * them by path relative to the root, see [SchemaInferencer]). [rootFor] resolves the data root for an open
 * file by taking the nearest designated root that contains it — so two branches checked out at once never
 * cross-contaminate, since each path resolves inside its own checkout.
 */
@Service(Service.Level.PROJECT)
@State(name = "GameDataRoots", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class GameDataRoots : PersistentStateComponent<GameDataRoots.State> {

    class State {
        var roots: MutableList<String> = mutableListOf()
    }

    // add() runs on the EDT but the platform serializes getState() off the EDT during project save, so guard
    // every access to the mutable list (and hand the serializer a defensive copy).
    private val lock = Any()
    private var state = State()

    override fun getState(): State = synchronized(lock) { State().also { it.roots = ArrayList(state.roots) } }
    override fun loadState(s: State) = synchronized(lock) { state = s }

    val roots: List<File> get() = synchronized(lock) { state.roots.map(::File) }

    /** Designate [dir] as a data root (idempotent — comparing by normalized absolute path). */
    fun add(dir: File) = synchronized(lock) {
        val key = pathKey(dir)
        if (state.roots.none { pathKey(File(it)) == key }) state.roots.add(dir.absolutePath)
        Unit
    }

    /**
     * The data root for [file]: the nearest DESIGNATED root that contains (or equals) it; failing that, the
     * nearest ANCESTOR holding a refs.json (zero-config fallback for projects that ship one but were never
     * explicitly designated). Both walks stay within the checkout, so resolution is branch-local.
     */
    fun rootFor(file: File): File? {
        val target = pathKey(file)
        // Only honour a designated root that actually holds a refs.json. A designation whose generation failed
        // (or whose refs.json was later removed) must not shadow a valid ancestor refs.json — otherwise the
        // tool window would silently regress to mock data.
        roots.filter { isAncestorOrSelf(pathKey(it), target) && File(it, "refs.json").isFile }
            .maxByOrNull { it.absolutePath.length }
            ?.let { return it }
        val start = if (file.isDirectory) file else file.parentFile
        return generateSequence(start) { it.parentFile }.firstOrNull { File(it, "refs.json").isFile }
    }

    // Path comparison honours the filesystem's case sensitivity (Windows checkouts differ only in case a lot).
    private fun pathKey(f: File): String =
        f.absoluteFile.normalize().path.let { if (SystemInfo.isFileSystemCaseSensitive) it else it.lowercase() }

    private fun isAncestorOrSelf(root: String, target: String): Boolean =
        target == root || target.startsWith(root.trimEnd(File.separatorChar) + File.separatorChar)

    companion object {
        fun getInstance(project: Project): GameDataRoots = project.service()
    }
}
