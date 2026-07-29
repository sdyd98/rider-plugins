package com.example.codemap

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.jetbrains.rd.ide.model.codemapModel

import com.jetbrains.rider.projectView.solution

/**
 * What ReSharper knows about where a function is.
 *
 * The plugin's own answer to "where is this function" is a text match against a signature the AI copied
 * into the note. That is exact when it matches and useless when it does not — a reformatted parameter
 * list or a macro in the way and the function goes grey, with no way to tell a stale note from an
 * unlucky one. C++ semantics live in Rider's .NET backend, so this asks the process that actually
 * parsed the code.
 *
 * Still no judgement: what comes back is signatures and offsets. Which of them corresponds to a note's
 * function is decided by string comparison, the same way the cross-note indexes work.
 */
object CppSymbols {

    private val log = logger<CppSymbols>()

    /** One declaration as the backend reported it. */
    data class Function(val signature: String, val line: Int, val definition: Boolean)

    /**
     * Every function declaration the backend finds in [rel], or empty if it cannot answer.
     *
     * Empty covers three different situations on purpose — no backend, not a C++ file, nothing declared.
     * The caller's behaviour is the same in all three: fall back to what the note says.
     *
     * Blocking, so call it off the EDT.
     */
    fun functionsIn(project: Project, rel: String): List<Function> = runCatching {
        project.solution.codemapModel.functionsIn
            .sync(rel)
            .map { Function(it.signature, it.line, it.definition) }
    }.getOrElse { e ->
        // Not worth surfacing: the note's own anchors still work, and this is expected in any IDE that
        // is not Rider with the backend loaded.
        log.debug("백엔드 심볼 조회 실패 ($rel)", e)
        emptyList()
    }

    /**
     * The line a function called [name] is on, from [functions], or null.
     *
     * Matching is deliberately narrow: the declarator has to contain the name as a whole word. A
     * substring test would make `Close` match `CloseSocket`, and a fuzzier one would be the tool making
     * a judgement about which function you meant.
     *
     * A definition wins over a declaration when both exist — that is where the body is, which is what
     * someone clicking a function name is looking for.
     */
    fun lineOf(functions: List<Function>, name: String): Int? {
        val word = Regex("(^|[^A-Za-z0-9_])" + Regex.escape(name) + "\\s*\\(")
        val hits = functions.filter { word.containsMatchIn(it.signature) }
        if (hits.isEmpty()) return null
        return (hits.firstOrNull { it.definition } ?: hits.first()).line
    }
}
