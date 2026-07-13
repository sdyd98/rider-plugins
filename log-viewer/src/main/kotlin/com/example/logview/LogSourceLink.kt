package com.example.logview

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.util.regex.Pattern

/**
 * Recognizes source references inside a log line and opens them in the IDE editor — the reason a
 * log viewer living inside the IDE beats `less`: a stack frame is one keypress from the code.
 *
 * Recognized shapes (first match wins):
 *  - JVM stack frames …………… `at pay.Gateway.call(Gateway.java:107)` (also .kt/.kts/.scala/.groovy)
 *  - .NET stack frames ………… `at Ns.Type.Method() in /src/Payment/Gateway.cs:line 42`
 *  - Python tracebacks ……… `File "/srv/app/worker.py", line 42, in handle`
 *
 * [find] is pure (headless-testable). [open] resolves an absolute path directly, or searches the
 * project index by filename (a JVM frame only carries the simple file name).
 */
object LogSourceLink {

    /** One clickable reference: [file] (simple name or path), 1-based [line], and the reference's
     *  character range in the text as `start..endExclusive` (for the renderer's underline). */
    data class Link(val file: String, val line: Int, val span: IntRange, val isPath: Boolean)

    // `(Gateway.java:107)` — the file name in a JVM frame never contains a path separator.
    private val JVM: Pattern = Pattern.compile("\\(([\\w$&+.\\-]+\\.(?:java|kts?|scala|groovy)):(\\d+)\\)")

    // `in /src/x/Gateway.cs:line 42` (Windows `C:\…` too) — path up to the extension, then `:line N`.
    private val DOTNET: Pattern = Pattern.compile("\\bin (\\S.*?\\.(?:cs|fs|vb)):line (\\d+)")

    // `File "/srv/app/worker.py", line 42`
    private val PYTHON: Pattern = Pattern.compile("File \"(.+?\\.py)\", line (\\d+)")

    /** The first source reference in [text], or null. Pure text parsing — no IDE access. */
    fun find(text: String): Link? {
        for ((pattern, isPath) in listOf(JVM to false, DOTNET to true, PYTHON to true)) {
            val m = pattern.matcher(text)
            if (m.find()) {
                val line = m.group(2).toIntOrNull() ?: continue
                return Link(m.group(1), line, m.start(1)..m.end(2), isPath)
            }
        }
        return null
    }

    /**
     * Open [link] in the editor at its line. Path links resolve directly (falling back to a
     * filename search when the logged path doesn't exist on THIS machine — e.g. a Windows build
     * path in a log read on a Mac); name links search the project index. Returns true on success.
     */
    fun open(project: Project?, link: Link): Boolean {
        if (project == null || project.isDisposed) return false
        val byPath = if (link.isPath) {
            LocalFileSystem.getInstance().findFileByPath(link.file.replace('\\', '/'))
        } else {
            null
        }
        val target = byPath ?: run {
            val name = link.file.substringAfterLast('/').substringAfterLast('\\')
            runCatching {
                ReadAction.compute<Collection<com.intellij.openapi.vfs.VirtualFile>, RuntimeException> {
                    FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.allScope(project))
                }
            }.getOrNull()?.minByOrNull { it.path.length } // prefer the shallowest match
        } ?: return false
        OpenFileDescriptor(project, target, (link.line - 1).coerceAtLeast(0), 0).navigate(true)
        return true
    }
}
