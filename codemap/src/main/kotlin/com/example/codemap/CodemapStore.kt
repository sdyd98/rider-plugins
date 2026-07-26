package com.example.codemap

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Project-scoped access to the `.codemap/` store. Its only job is deciding WHERE the store is rooted;
 * everything the store does lives in [NoteStore], which knows nothing about the IDE and is covered by
 * headless tests.
 */
@Service(Service.Level.PROJECT)
class CodemapStore(private val project: Project) {

    /**
     * The codemap root — **the git working tree**, falling back to the project base when the project
     * is not in git.
     *
     * Not simply `project.basePath`: Rider's base path is the folder of the solution it opened, which
     * is often NOT the source root. Opening `examples/foo.sln` in a repo whose sources sit at the top
     * level would otherwise put every one of those sources "outside the project" and leave them unable
     * to hold a note. The git top level is the mechanical, no-judgment answer to "what is this
     * codebase", and it keeps one store per repository no matter which solution you happen to open.
     *
     * Resolved once (a `git rev-parse` per project), and never on the EDT — every caller is already on
     * a pooled thread or an MCP IO dispatcher.
     */
    val store: NoteStore? by lazy {
        val base = project.basePath?.let(::File)?.takeIf { it.isDirectory } ?: return@lazy null
        NoteStore(FileFacts.gitToplevel(base) ?: base)
    }

    val root: File? get() = store?.root
}
