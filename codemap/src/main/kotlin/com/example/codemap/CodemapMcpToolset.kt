package com.example.codemap

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File

/**
 * How the one-liners are written, so the batch path and the plugin's own CLI path produce notes in one
 * voice. The reader is a person mid-analysis with a concrete question, and a sentence that restates the
 * signature answers none of them.
 */
private const val WRITING_RULES =
    "WRITING THE ONE-LINERS — the reader is seeing this code for the first time with a question in mind " +
        "(who mutates this, can it block, what happens when it fails):\n" +
        "  - Do not restate the name or signature. \"HandleLogin — handles login\" is a wasted line.\n" +
        "  - Name what changes, with the real identifiers: \"m_state Authenticating -> Playing, sends " +
        "LoginAck\".\n" +
        "  - State the way out: early returns, failure branches, exceptions. That is what a reader needs " +
        "first.\n" +
        "  - Always say what blocks or locks, and how far the lock is held.\n" +
        "  - One line. If a second sentence is needed, it belongs in `gotchas`.\n" +
        "  - Start with a verb; drop \"this function ...\" padding.\n" +
        "  - The test: after reading the line, could someone pick their next question WITHOUT opening the " +
        "source? Then it is good."

/**
 * MCP tools contributed to the IDE's integrated MCP server (Settings | Tools | MCP Server) so an AI
 * client (Claude Code, etc.) can author the code-understanding notes under `.codemap/`.
 *
 * DIVISION OF LABOR — same firm rule as the xlsx-editor refs tools: these tools are JUDGMENT-FREE.
 * They hand over facts that are exactly true (line counts, `#include` lines as literally written,
 * `.h`/`.cpp` pairing by name, git history, content hashes) and store whatever note you write. They
 * never parse C++, never infer inheritance or call graphs, never guess a class's role. This is not
 * caution — the project builds from a `.sln`, so there is no compilation database, and a regex
 * "parser" over 58M lines of macro-heavy C++ would be confidently wrong often enough to poison the
 * notes. Every interpretation is yours, made by READING THE SOURCE.
 *
 * Typical flow: the developer names a file (or asks a question about one) -> [codemap_stat] (facts + where
 * the pair lives) -> read the actual source -> [codemap_write_note]. [codemap_list_stale] finds notes whose
 * code has since changed; [codemap_find_mentions] is a literal text grep for tracing a symbol by hand.
 *
 * There is no request queue: what to analyze comes from the person asking you, not from a to-do list the
 * plugin keeps. A question they typed in the panel is used the moment they press 분석 실행 and is not
 * stored, so if they mention one here it is the brief — answer it in the note rather than producing a
 * generic summary.
 *
 * Every tool returns JSON text. Paths are project-relative (absolute paths inside the project are
 * accepted and normalized).
 */
class CodemapMcpToolset : McpToolset {

    // ---- the facts ----

    @McpTool
    @McpDescription(
        "Exact facts about one or more files — nothing inferred. Per file: line count, byte size, the " +
            "`.h`/`.cpp` pair it belongs to (matched by directory + base name), its `#include` lines " +
            "VERBATIM (this is text extraction, not a preprocessor: lines inside #if/#ifdef blocks are " +
            "listed too and macro-built paths are not resolved), git history (last commit, total commits " +
            "touching it), and whether a note already exists and is still fresh. Use this to size up a " +
            "file and locate its pair before reading the source; do NOT treat the include list as the " +
            "dependency graph — decide dependencies yourself from the code.",
    )
    suspend fun codemap_stat(
        @McpDescription("Files, comma- or newline-separated. Project-relative or absolute.") paths: String,
    ): String = ioJson { store ->
        val rels = splitPaths(paths).mapNotNull { normalize(store, it) }
        json {
            addProperty("root", store.root.absolutePath)
            add("files", JsonArray().apply { rels.forEach { add(statOf(store, it)) } })
        }
    }

    private fun statOf(store: NoteStore, rel: String): JsonObject = json {
        addProperty("path", rel)
        val f = store.resolve(rel)
        if (!f.isFile) {
            addProperty("exists", false)
            return@json
        }
        addProperty("exists", true)
        addProperty("bytes", f.length())
        runCatching { FileFacts.size(f) }.getOrNull()?.let { addProperty("lines", it.lines) }

        val key = store.noteKeyFor(rel)
        val dirRel = rel.substringBeforeLast('/', "")
        addProperty("noteKey", key)
        add("pair", JsonArray().apply {
            CodemapPaths.pairOf(key, f.parentFile?.list()?.toList().orEmpty())
                .forEach { add(if (dirRel.isEmpty()) it else "$dirRel/$it") }
        })
        add("includes", JsonArray().apply { FileFacts.includes(f).forEach { add(it) } })

        FileFacts.git(store.root, rel)?.let { g ->
            add("git", json {
                addProperty("lastCommit", g.lastCommit)
                addProperty("lastDate", g.lastDate)
                addProperty("lastAuthor", g.lastAuthor)
                addProperty("lastSubject", g.lastSubject)
                addProperty("commitCount", g.commitCount)
            })
        }

        val note = store.readNote(rel)
        add("note", json {
            addProperty("exists", note != null)
            addProperty("freshness", store.freshness(note).name)
            note?.get("analyzedAt")?.asString?.let { addProperty("analyzedAt", it) }
            note?.get("analyzedCommit")?.asString?.let { c ->
                addProperty("analyzedCommit", c)
                FileFacts.commitsSince(store.root, rel, c)?.let { addProperty("commitsSince", it) }
            }
        })
    }

    @McpTool
    @McpDescription(
        "Literal text search for [symbol] across source files — every file whose text CONTAINS that " +
            "exact string, with the matching line numbers. This is a grep, not a reference finder: a hit " +
            "may be a comment, a string, or an unrelated name, and a real reference reached through a " +
            "typedef or macro will NOT appear. Exact about what the text says, silent about what it means " +
            "— judge each hit yourself. Scope with [dir] on a large tree; `.git` and `.codemap` are skipped.",
    )
    suspend fun codemap_find_mentions(
        @McpDescription("The exact string to look for (case-sensitive).") symbol: String,
        @McpDescription("Project-relative subtree to search; empty = whole project.") dir: String = "",
        @McpDescription("Comma-separated file extensions to scan.") extensions: String = "h,hpp,hxx,hh,inl,cpp,cc,cxx,c",
        @McpDescription("Max files reported before truncating.") limit: Int = 200,
    ): String = ioJson { store ->
        val base = if (dir.isBlank()) store.root else store.resolve(dir)
        require(base.isDirectory) { "검색할 디렉토리를 찾을 수 없습니다: $dir" }
        require(symbol.isNotEmpty()) { "symbol이 비어 있습니다" }

        val exts = extensions.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val hits = JsonArray()
        var scanned = 0
        var matched = 0
        var truncated = false
        base.walkTopDown()
            .onEnter { it.name != ".git" && it.name != CodemapPaths.DIR }
            .filter { it.isFile && CodemapPaths.ext(it.name) in exts }
            .forEach { f ->
                if (truncated) return@forEach
                scanned++
                val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                val at = lines.withIndex().filter { it.value.contains(symbol) }.map { it.index + 1 }
                if (at.isEmpty()) return@forEach
                matched++
                if (matched > limit) { truncated = true; return@forEach }
                hits.add(json {
                    addProperty("path", store.relativize(f) ?: f.absolutePath)
                    addProperty("matchCount", at.size)
                    add("lines", JsonArray().apply { at.take(20).forEach { add(it) } })
                })
            }
        json {
            addProperty("symbol", symbol)
            addProperty("filesScanned", scanned)
            addProperty("filesMatched", if (truncated) matched - 1 else matched)
            if (truncated) addProperty("truncated", true)
            add("files", hits)
        }
    }

    // ---- the notes ----

    @McpTool
    @McpDescription("The stored note for a file (its `.h`/`.cpp` pair shares one note), or exists:false.")
    suspend fun codemap_read_note(
        @McpDescription("File path, project-relative or absolute.") path: String,
    ): String = ioJson { store ->
        val rel = normalize(store, path) ?: error("프로젝트 안의 경로가 아닙니다: $path")
        val note = store.readNote(rel)
        json {
            addProperty("path", rel)
            addProperty("exists", note != null)
            addProperty("freshness", store.freshness(note).name)
            if (note != null) {
                add("note", note)
                add("functionAnchors", anchorReport(store, note))
            }
        }
    }

    @McpTool
    @McpDescription(
        "Store the understanding note for a file — its `.h`/`.cpp` pair shares one note. " +
            "[note] is a JSON object; `files`, `hashes`, `analyzedAt` and " +
            "`analyzedCommit` are stamped by the tool — do not write them. Everything else is yours and " +
            "is stored verbatim, so the shape can grow without a plugin change; the 코드맵 tool window " +
            "renders these keys:\n" +
            "  purpose        string  — what this file is for, 2-3 lines\n" +
            "  roleInSystem   string  — where it sits in the server architecture\n" +
            "  classes        [{name, role}]\n" +
            "  entryPoints    [{symbol, note}]  — read these first, in this order\n" +
            "  keyState       [{member, note}]\n" +
            "  threading      {model, affinity, locks: [{name, guards, order}]}\n" +
            "  packets        [{id, dir: \"in\"|\"out\", handler, sentBy}]\n" +
            "  dependsOn      [{target, why}]\n" +
            "  usedBy         [{source, context}]\n" +
            "  flows          [{name, steps}] — PACKET sequences; see codemap_write_flows for the shape.\n" +
            "                 steps take EITHER form, and the panel draws each differently:\n" +
            "                 [string, …]                       → a flow chart (a straight chain of stages)\n" +
            "                 [{from, to, call, kind}, …]       → a SEQUENCE DIAGRAM. `from`/`to` are the\n" +
            "                 participants (a class, a thread, \"Client\", \"DB\" — whatever the flow actually\n" +
            "                 moves between), `call` is the message, and kind:\"return\" draws it as a dashed\n" +
            "                 reply. Use this form when you know who calls whom; use the plain chain when the\n" +
            "                 flow is genuinely linear, rather than inventing participants to fill it in.\n" +
            "  dataSources    [{kind: \"xlsx\"|\"db\"|\"config\"|\"proto\", ref, note}]\n" +
            "  gotchas        [string]  — traps, ordering constraints, lifetime hazards\n" +
            "  functions      [{name, anchor, purpose, ...}] — the file's function table of contents; see\n" +
            "                 codemap_write_functions for the shape. On a file of any size prefer that tool,\n" +
            "                 which upserts by name instead of making you resend everything.\n" +
            "Write only what you actually established from the source; omit a key rather than filling it " +
            "with a guess. If the developer asked a question, make sure the note answers it.\n" +
            WRITING_RULES,
    )
    suspend fun codemap_write_note(
        @McpDescription("File path, project-relative or absolute.") path: String,
        @McpDescription("The note as a JSON object.") note: String,
    ): String = ioJson { store ->
        val rel = normalize(store, path) ?: error("프로젝트 안의 경로가 아닙니다: $path")
        val parsed = runCatching { JsonParser.parseString(note) as? JsonObject }.getOrNull()
            ?: error("note가 JSON 객체가 아닙니다")
        val ignored = parsed.keySet().filter { it in store.stampedKeys }
        val stamped = store.writeNote(rel, parsed)
        json {
            addProperty("path", rel)
            addProperty("bundle", CodemapPaths.bundleFor(rel))
            addProperty("noteKey", store.noteKeyFor(rel))
            addProperty("analyzedAt", stamped.get("analyzedAt")?.asString.orEmpty())
            add("files", stamped.getAsJsonArray("files") ?: JsonArray())
            if (ignored.isNotEmpty()) {
                add("ignoredKeys", JsonArray().apply { ignored.forEach { add(it) } })
                addProperty("ignoredNote", "이 키들은 도구가 직접 기록합니다 — 전달한 값은 무시했습니다.")
            }
        }
    }

    @McpTool
    @McpDescription(
        "Add or update entries in a file's `functions` list, matched by `name` — an existing function is " +
            "replaced, a new one is appended, everything else in the note is left alone. Use this instead " +
            "of rewriting the whole note when working through a large file a few functions at a time.\n" +
            "Each function is an object:\n" +
            "  name     string  — as written in the code (`PlayerSession::HandleLogin` or `HandleLogin`)\n" +
            "  anchor   string  — the signature line copied VERBATIM from the source, e.g.\n" +
            "                     \"void PlayerSession::HandleLogin(const uint8_t* body, size_t len) {\".\n" +
            "                     The plugin finds the function by searching for this text, so a line number " +
            "is never stored and never goes stale. Copy enough of the line to be UNIQUE in the file; the " +
            "response reports `occurrences` so you can tell when an anchor is ambiguous, and `located:false` " +
            "when it matches nothing (usually a typo — fix it, don't leave it).\n" +
            "  purpose  string  — ONE line. Required for EVERY function, including trivial getters and " +
            "constructors: the list is a table of contents, and a hole in it makes the reader wonder whether " +
            "the function was skipped or simply unimportant.\n" +
            "Optional, and only worth filling in for functions that actually warrant it:\n" +
            "  thread   string  — which thread it runs on\n" +
            "  locks    [string]\n" +
            "  calls    [string]\n" +
            "  effects  [string]  — what it mutates or sends\n" +
            "  gotchas  [string]\n" +
            "List functions in DECLARATION ORDER (the order they appear in the file), so the panel reads " +
            "alongside the code rather than re-ranking it.\n" +
            WRITING_RULES,
    )
    suspend fun codemap_write_functions(
        @McpDescription("File path, project-relative or absolute.") path: String,
        @McpDescription("A JSON array of function objects.") functions: String,
    ): String = ioJson { store ->
        val rel = normalize(store, path) ?: error("프로젝트 안의 경로가 아닙니다: $path")
        val parsed = runCatching { JsonParser.parseString(functions) as? JsonArray }.getOrNull()
            ?: error("functions가 JSON 배열이 아닙니다")
        val stamped = store.writeFunctions(rel, parsed)
        json {
            addProperty("path", rel)
            addProperty("functionsTotal", (stamped.getAsJsonArray("functions") ?: JsonArray()).size())
            add("functionAnchors", anchorReport(store, stamped))
        }
    }

    @McpTool
    @McpDescription(
        "Add sequence diagrams to a note's `flows`, matched by `name` — an existing name is replaced, a " +
            "new one is appended, and nothing else in the note is touched. This is the ONLY tool to answer " +
            "one scenario a developer asked about.\n" +
            "These are PACKET sequences: the spine is packet traffic, and the internal processing between two " +
            "packets earns at most one step each.\n" +
            "Each entry: {name, steps:[{from, to, packet, id, call, kind, description}]}\n" +
            "  name        string — short label for the scenario\n" +
            "  from        string — the participant that acts (a real class/module name, never invented)\n" +
            "  to          string — the participant acted upon\n" +
            "  packet      string — the packet constant EXACTLY as the code writes it (CS_LOGIN_REQ,\n" +
            "                       ClientPacket::LoginReq). Its presence is what makes a step a packet step;\n" +
            "                       direction is from/to, so a server->client packet is not a \"return\".\n" +
            "  id          string — the packet id when the code gives one (0x01, 12, an enum value); omit\n" +
            "                       otherwise. Never invent one.\n" +
            "  call        string — for a NON-packet step: the function that connects two packets\n" +
            "  kind        string — omit for a call; \"return\" for a value coming back; \"process\" for\n" +
            "                       something one object does alone (leave `to` empty or equal to `from`);\n" +
            "                       \"note\" for a line of explanation attached to no object\n" +
            "  description string — one sentence on WHY this step happens, shown in the step list beside the\n" +
            "                       diagram. `call` is what, `description` is why. Omit where it adds nothing.\n" +
            "Steps go in execution order and are presented one at a time, so each one should stand alone. Diagrams ACCUMULATE: someone asked for each one by name, so " +
            "sending a fifth must not cost them the other four — send only the scenario you were asked for.",
    )
    suspend fun codemap_write_flows(
        @McpDescription("File path, project-relative or absolute.") path: String,
        @McpDescription("A JSON array of flow objects.") flows: String,
    ): String = ioJson { store ->
        val rel = normalize(store, path) ?: error("프로젝트 안의 경로가 아닙니다: $path")
        val parsed = runCatching { JsonParser.parseString(flows) as? JsonArray }.getOrNull()
            ?: error("flows가 JSON 배열이 아닙니다")
        require(!parsed.isEmpty) { "flows가 비어 있습니다" }
        val stamped = store.writeFlows(rel, parsed)
        json {
            addProperty("path", rel)
            // Every flow the note now holds, so the caller can see what it added to rather than replaced.
            add("flows", JsonArray().apply {
                (stamped.getAsJsonArray("flows") ?: JsonArray()).forEach { el ->
                    (el as? JsonObject)?.get("name")?.asString?.let { add(it) }
                }
            })
        }
    }

    /** Where each recorded anchor resolves right now — the AI's check that its anchors actually work. */
    private fun anchorReport(store: NoteStore, note: JsonObject): JsonArray {
        val fns = note.getAsJsonArray("functions") ?: return JsonArray()
        val covered = (note.getAsJsonArray("files") ?: JsonArray()).mapNotNull { it.asString }
        val anchors = fns.mapNotNull { (it as? JsonObject)?.get("anchor")?.asString }.filter { it.isNotBlank() }
        val hits = HashMap<String, Pair<String, FileFacts.Anchor>>()
        covered.forEach { relFile ->
            val f = store.resolve(relFile).takeIf { it.isFile } ?: return@forEach
            FileFacts.findAnchors(f, anchors.filter { it !in hits.keys })
                .forEach { (anchor, hit) -> hits[anchor] = relFile to hit }
        }
        return JsonArray().apply {
            fns.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val anchor = o.get("anchor")?.asString
                val hit = anchor?.let { hits[it] }
                add(json {
                    addProperty("name", o.get("name")?.asString.orEmpty())
                    addProperty("located", hit != null)
                    hit?.let {
                        addProperty("file", it.first)
                        addProperty("line", it.second.line)
                        addProperty("occurrences", it.second.occurrences)
                    }
                })
            }
        }
    }

    @McpTool
    @McpDescription(
        "Notes whose code changed since they were written — compared by CONTENT HASH, so a touched-but- " +
            "unchanged file stays fresh and a reverted edit stops being stale. Each entry reports how many " +
            "commits landed on the file since the note. Use this to re-analyze in bulk.",
    )
    suspend fun codemap_list_stale(
        @McpDescription("Project-relative subtree to limit to; empty = whole store.") dir: String = "",
        @McpDescription("Max entries returned.") limit: Int = 200,
    ): String = ioJson { store ->
        val prefix = dir.trim().trim('/')
        val stale = store.allNotes()
            .filter { (rel, _) -> prefix.isEmpty() || rel == prefix || rel.startsWith("$prefix/") }
            .filter { (_, note) -> store.freshness(note) == NoteStore.Freshness.STALE }
        json {
            addProperty("staleTotal", stale.size)
            add("files", JsonArray().apply {
                stale.take(limit.coerceAtLeast(1)).forEach { (rel, note) ->
                    add(json {
                        addProperty("path", rel)
                        note.get("analyzedAt")?.asString?.let { addProperty("analyzedAt", it) }
                        note.get("analyzedCommit")?.asString?.let { c ->
                            FileFacts.commitsSince(store.root, rel, c)?.let { addProperty("commitsSince", it) }
                        }
                    })
                }
            })
        }
    }

    // ---- plumbing ----

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * Tool bodies build a JsonObject off the EDT; the MCP layer wants it rendered as JSON text.
     *
     * The project comes from the MCP framework, not from a parameter of ours: the IDE's MCP server
     * already resolves which open project a call targets (asking the client for `projectPath` when it
     * is ambiguous), and duplicating that as a `root` argument only gave callers a second, weaker way
     * to get it wrong. Resolved before switching dispatchers so the call context is still current.
     */
    private suspend fun ioJson(block: (NoteStore) -> JsonObject): String {
        val store = currentCoroutineContext().project.getService(CodemapStore::class.java).store
            ?: error("코드맵 루트를 찾을 수 없습니다 (프로젝트 경로 없음)")
        return withContext(Dispatchers.IO) { gson.toJson(block(store)) }
    }

    private fun json(build: JsonObject.() -> Unit): JsonObject = JsonObject().apply(build)



    private fun splitPaths(paths: String): List<String> =
        paths.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Accept absolute or project-relative input; return the project-relative form, or null if outside. */
    private fun normalize(store: NoteStore, path: String): String? {
        val f = File(path)
        if (f.isAbsolute) return store.relativize(f)
        return path.replace('\\', '/').trim('/')
    }
}
