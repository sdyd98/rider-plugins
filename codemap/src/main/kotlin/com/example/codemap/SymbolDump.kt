package com.example.codemap

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.io.File

/**
 * A test seam: dumps what the ReSharper backend reports for a given set of files, then gets out of the way.
 *
 * The real caller sits behind a note, a tool window, and a file being open — none of which a script can
 * drive. Without this, the only way to check that the backend still finds the right functions is for a
 * person to open a project and look, which means in practice it never gets checked.
 *
 * Off unless asked for, by system properties the sandbox is launched with:
 *
 *     -Dcodemap.symbolDump=/tmp/out.txt -Dcodemap.symbolDumpFiles=App/Net.h,App/Net.cpp
 *
 * See testdata/check-symbols.sh, which is the only thing that sets them.
 */
class SymbolDump : ProjectActivity {

    override suspend fun execute(project: Project) {
        val out = System.getProperty(OUT)?.takeIf { it.isNotBlank() } ?: return
        val files = System.getProperty(FILES).orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (files.isEmpty()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val text = buildString {
                files.forEach { rel ->
                    // One question per file, asked once. The backend waits for its own caches, so a
                    // retry here would hide it if that ever stopped being true.
                    val functions = CppSymbols.functionsIn(project, rel)
                    appendLine("# $rel ${functions.size}")
                    functions.forEach { appendLine("${it.line}\t${if (it.definition) "정의" else "선언"}\t${it.signature}") }
                }
            }
            File(out).writeText(text)
        }
    }

    private companion object {
        const val OUT = "codemap.symbolDump"
        const val FILES = "codemap.symbolDumpFiles"
    }
}
