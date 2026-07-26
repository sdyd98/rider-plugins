package com.example.codemap

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.LocalDate

/**
 * The `.codemap/` store rooted at one directory: per-directory note bundles plus the pending-analysis
 * queue. Plain `java.io` and Gson only — no IDE types — so the whole store is covered by headless
 * tests against a real temp repository ([NoteStoreTest]). [CodemapStore] is the thin project-scoped
 * wrapper that decides which directory this is rooted at.
 *
 * Notes are treated as OPAQUE JSON. The store stamps the provenance it can prove — the files a note
 * covers, their content hashes, the analysis date, the commit HEAD was at — and passes everything
 * else through untouched. That keeps the note schema the AI's to evolve (new fields need no plugin
 * release) and keeps the plugin free of any opinion about what a note should say.
 *
 * `.codemap/` is local-only by decision: it self-ignores via its own `.gitignore` (written on first
 * use) so it never lands in a commit and never touches the project's own `.gitignore`.
 */
class NoteStore(val root: File, private val today: () -> String = { LocalDate.now().toString() }) {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    val codemapDir: File get() = File(root, CodemapPaths.DIR)

    /** Root-relative, '/'-separated path of [file], or null when it lives outside the root. */
    fun relativize(file: File): String? {
        val rel = runCatching { root.toPath().relativize(file.toPath()).toString() }.getOrNull() ?: return null
        if (rel.startsWith("..")) return null
        return rel.replace(File.separatorChar, '/')
    }

    fun resolve(relPath: String): File = File(root, relPath)

    // ---- notes ----

    private fun bundleFile(relFile: String): File = File(codemapDir, CodemapPaths.bundleFor(relFile))

    private fun readJson(f: File): JsonObject? = runCatching {
        if (!f.isFile) null else JsonParser.parseString(f.readText(Charsets.UTF_8)) as? JsonObject
    }.getOrNull()

    private fun notesOf(f: File): JsonObject? = readJson(f)?.get("notes") as? JsonObject

    private fun writeJson(f: File, obj: JsonObject) {
        f.parentFile?.mkdirs()
        f.writeText(gson.toJson(obj), Charsets.UTF_8)
        ensureSelfIgnored()
    }

    private fun siblingsOf(relFile: String): List<String> =
        resolve(relFile).parentFile?.list()?.toList().orEmpty()

    /** The note key [relFile] is filed under — resolves the .h/.cpp pair against the real directory. */
    fun noteKeyFor(relFile: String): String = CodemapPaths.noteKey(relFile, siblingsOf(relFile))

    fun readNote(relFile: String): JsonObject? =
        notesOf(bundleFile(relFile))?.get(noteKeyFor(relFile)) as? JsonObject

    /** Provenance the store writes itself; a note may not author these. */
    val stampedKeys = setOf("files", "hashes", "analyzedAt", "analyzedCommit")

    /**
     * Where human corrections are remembered, so a re-analysis cannot quietly undo them.
     *
     * A shadow of the note holding only the fields a person edited — `{"purpose": "…",
     * "functions": {"HandleLogin": {"purpose": "…"}}}`. It is not a note field: the store strips it from
     * anything incoming, carries it forward itself, and overlays it after every analysis. Typing a
     * sentence costs a person real effort; an agent can regenerate its half in seconds, so when the two
     * disagree the person wins.
     */
    val MANUAL = "_manual"

    /**
     * Store [note] for [relFile] (its pair shares the entry), stamping provenance. Returns the stamped
     * note. Any pending request for the same note is cleared — writing the note IS answering it.
     */
    fun writeNote(relFile: String, note: JsonObject): JsonObject {
        val key = noteKeyFor(relFile)
        val dirRel = relFile.substringBeforeLast('/', "")
        val previous = readNote(relFile)

        val stamped = note.deepCopy()
        stampedKeys.forEach(stamped::remove)
        stamped.remove(MANUAL)

        // Keep what the incoming note does not mention. An analysis is not a licence to delete: a big
        // file is written a few functions at a time, and an answer that covers ten of forty functions
        // must not take the other thirty with it.
        if (previous != null) {
            listOf("functions", "flows").forEach { key ->
                stamped.add(key, mergeByName(previous.get(key) as? JsonArray, stamped.get(key) as? JsonArray))
                if (stamped.get(key).let { it is JsonArray && it.isEmpty }) stamped.remove(key)
            }
            // Human corrections last: they are restored on top of whatever the analysis produced.
            (previous.get(MANUAL) as? JsonObject)?.let { manual ->
                overlayManual(stamped, manual)
                stamped.add(MANUAL, manual.deepCopy())
            }
        }

        val files = JsonArray()
        val hashes = JsonObject()
        CodemapPaths.pairOf(key, siblingsOf(relFile)).forEach { name ->
            val rel = if (dirRel.isEmpty()) name else "$dirRel/$name"
            files.add(rel)
            resolve(rel).takeIf { it.isFile }?.let { hashes.addProperty(name, FileFacts.sha256(it)) }
        }
        stamped.add("files", files)
        stamped.add("hashes", hashes)
        stamped.addProperty("analyzedAt", today())
        stamped.addProperty("analyzedCommit", FileFacts.headCommit(root))

        putNote(relFile, stamped)
        removePending(relFile)
        return stamped
    }

    /** Name-keyed upsert of [incoming] over [existing]: replaced in place, appended when new, never dropped. */
    private fun mergeByName(existing: JsonArray?, incoming: JsonArray?): JsonArray {
        val merged = JsonArray()
        val fresh = LinkedHashMap<String, JsonObject>()
        incoming?.forEach { el -> (el as? JsonObject)?.let { f -> nameOf(f)?.let { fresh[it] = f } } }
        existing?.forEach { el ->
            val old = el as? JsonObject ?: return@forEach
            val name = nameOf(old)
            merged.add(if (name != null && fresh.containsKey(name)) fresh.remove(name) else old)
        }
        fresh.values.forEach(merged::add)
        return merged
    }

    /** Write every field recorded in [manual] back over [note], creating nothing that is not already there. */
    private fun overlayManual(note: JsonObject, manual: JsonObject) {
        manual.entrySet().forEach { (key, value) ->
            if (key != "functions") {
                note.add(key, value.deepCopy())
                return@forEach
            }
            val perFunction = value as? JsonObject ?: return@forEach
            val functions = note.get("functions") as? JsonArray ?: return@forEach
            perFunction.entrySet().forEach { (name, fields) ->
                val target = functions.mapNotNull { it as? JsonObject }.firstOrNull { nameOf(it) == name }
                    ?: return@forEach
                (fields as? JsonObject)?.entrySet()?.forEach { (f, v) -> target.add(f, v.deepCopy()) }
            }
        }
    }

    /** Store [note] under [relFile]'s key without touching provenance or the queue. */
    private fun putNote(relFile: String, note: JsonObject) {
        val bundle = bundleFile(relFile)
        val obj = readJson(bundle) ?: JsonObject()
        obj.addProperty("version", 1)
        obj.addProperty("dir", relFile.substringBeforeLast('/', ""))
        val notes = obj.get("notes") as? JsonObject ?: JsonObject().also { obj.add("notes", it) }
        notes.add(noteKeyFor(relFile), note)
        writeJson(bundle, obj)
    }

    /**
     * Apply a human correction to an existing note, and remember that a human made it.
     *
     * [path] is either `["purpose"]`-style (a top-level field) or `["functions", name, field]`. Recording
     * it under [MANUAL] is what lets [writeNote] restore the correction after a re-analysis; without that
     * record the next 재분석 실행 would silently overwrite it.
     *
     * Distinct from [writeNote] in one more way that matters: provenance is **preserved, not re-stamped**.
     * Fixing a sentence is not an analysis — the note still describes the same source at the same hashes,
     * and moving `analyzedAt` to today would claim a re-read that never happened. For the same reason this
     * does not clear the pending queue: a wording fix does not answer the question someone asked.
     *
     * Returns the saved note, or null when there is nothing to edit or the path names nothing.
     */
    fun editNote(relFile: String, path: List<String>, value: JsonElement): JsonObject? {
        val existing = readNote(relFile) ?: return null
        val next = existing.deepCopy()

        when {
            path.size == 1 -> next.add(path[0], value)

            path.size == 3 && path[0] == "functions" -> {
                val functions = next.get("functions") as? JsonArray ?: return null
                val target = functions.mapNotNull { it as? JsonObject }.firstOrNull { nameOf(it) == path[1] }
                    ?: return null
                target.add(path[2], value)
            }

            else -> return null
        }

        val manual = (next.get(MANUAL) as? JsonObject ?: JsonObject()).also { next.add(MANUAL, it) }
        if (path.size == 1) {
            manual.add(path[0], value.deepCopy())
        } else {
            val perFunction = manual.get("functions") as? JsonObject
                ?: JsonObject().also { manual.add("functions", it) }
            val fields = perFunction.get(path[1]) as? JsonObject
                ?: JsonObject().also { perFunction.add(path[1], it) }
            fields.add(path[2], value.deepCopy())
        }

        stampedKeys.forEach { k ->
            val original = existing.get(k)
            if (original == null) next.remove(k) else next.add(k, original)
        }
        putNote(relFile, next)
        return next
    }

    /** Has a person corrected this note? Drives the 수정됨 badge — an edited note reads differently. */
    fun edited(note: JsonObject?): Boolean =
        (note?.get(MANUAL) as? JsonObject)?.size()?.let { it > 0 } == true

    /**
     * Merge [functions] into the note's `functions` array, matched by `name`: an existing entry is
     * replaced in place, a new one is appended. Returns the stamped note.
     *
     * Upsert rather than replace because a 6,000-line file is analyzed a few functions at a time — with
     * whole-note writes the AI would have to resend every function it already wrote just to add one
     * more, and any interruption would lose the lot.
     *
     * Provenance is re-stamped: the caller just read the file to write these, so the note now describes
     * the file as it is now. Completeness ("are all functions covered yet?") is a separate question the
     * store deliberately does not judge.
     */
    fun writeFunctions(relFile: String, functions: JsonArray): JsonObject {
        val existing = readNote(relFile) ?: JsonObject()
        val next = existing.deepCopy()
        next.add("functions", mergeByName(existing.get("functions") as? JsonArray, functions))
        return writeNote(relFile, next)
    }

    /**
     * Add [flows] to the note's `flows`, matched by `name` — the same upsert as [writeFunctions], for the
     * same reason turned up a level.
     *
     * A sequence diagram is something a person asked for by name ("로그인부터 월드 입장까지"), one at a
     * time. Each answer must therefore ARRIVE ALONGSIDE the ones already there: replacing the array would
     * make every new scenario cost you the last one, which is the opposite of how they get collected.
     */
    fun writeFlows(relFile: String, flows: JsonArray): JsonObject {
        val existing = readNote(relFile) ?: JsonObject()
        val next = existing.deepCopy()
        next.add("flows", mergeByName(existing.get("flows") as? JsonArray, flows))
        return writeNote(relFile, next)
    }

    /** Drop one flow by name — a scenario that came back wrong should not be permanent. */
    fun removeFlow(relFile: String, name: String): JsonObject? {
        val existing = readNote(relFile) ?: return null
        val flows = existing.get("flows") as? JsonArray ?: return null
        val kept = JsonArray().apply {
            flows.mapNotNull { it as? JsonObject }.filter { nameOf(it) != name }.forEach(::add)
        }
        val next = existing.deepCopy()
        if (kept.isEmpty) next.remove("flows") else next.add("flows", kept)
        // Provenance untouched: removing a diagram someone asked for is not a re-reading of the source.
        putNote(relFile, next)
        return next
    }

    private fun nameOf(f: JsonObject): String? =
        f.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    /** Every note in the store, as (root-relative primary file, note). Bounded by note count. */
    fun allNotes(): List<Pair<String, JsonObject>> {
        val dir = codemapDir.takeIf { it.isDirectory } ?: return emptyList()
        val out = ArrayList<Pair<String, JsonObject>>()
        dir.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { f ->
            val bundleRel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
            if (CodemapPaths.isBookkeeping(bundleRel)) return@forEach
            val srcDir = CodemapPaths.dirOfBundle(bundleRel)
            val notes = notesOf(f) ?: return@forEach
            notes.entrySet().forEach { (key, value) ->
                val obj = value as? JsonObject ?: return@forEach
                out += (if (srcDir.isEmpty()) key else "$srcDir/$key") to obj
            }
        }
        return out
    }

    // ---- staleness ----

    enum class Freshness { NO_NOTE, FRESH, STALE, UNKNOWN }

    /**
     * Whether [note] still describes what is on disk: every covered file's content hash is recompared.
     * Content hashing (not mtime) is what makes this exact — a touched-but-unchanged file stays fresh,
     * and a reverted edit stops being stale.
     */
    fun freshness(note: JsonObject?): Freshness {
        if (note == null) return Freshness.NO_NOTE
        val hashes = note.get("hashes") as? JsonObject ?: return Freshness.UNKNOWN
        val files = note.get("files") as? JsonArray ?: return Freshness.UNKNOWN
        if (files.isEmpty) return Freshness.UNKNOWN
        files.forEach { el ->
            val rel = el.asString
            val recorded = hashes.get(rel.substringAfterLast('/'))?.asString ?: return Freshness.UNKNOWN
            val f = resolve(rel)
            if (!f.isFile) return Freshness.STALE
            if (FileFacts.sha256(f) != recorded) return Freshness.STALE
        }
        return Freshness.FRESH
    }

    // ---- pending queue ----

    /**
     * A queued analysis request: the file, the optional question that motivated it, and an optional
     * [symbol] narrowing it to one function — on a 6,000-line file "look at Dispatch" is a very
     * different ask from "analyze this file", and they queue independently.
     */
    data class Pending(
        val path: String,
        val question: String,
        val requestedAt: String,
        val reason: String,
        val symbol: String = "",
        /** A scenario to draw as a sequence diagram, in the requester's own words. */
        val flow: String = "",
    )

    private val pendingFile: File get() = File(codemapDir, CodemapPaths.PENDING)

    fun pending(): List<Pending> {
        val arr = readJson(pendingFile)?.get("requests") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Pending(
                path = o.get("path")?.asString ?: return@mapNotNull null,
                question = o.get("question")?.asString.orEmpty(),
                requestedAt = o.get("requestedAt")?.asString.orEmpty(),
                reason = o.get("reason")?.asString.orEmpty(),
                symbol = o.get("symbol")?.asString.orEmpty(),
                flow = o.get("flow")?.asString.orEmpty(),
            )
        }
    }

    /**
     * Queue (or re-queue) [relFile] for analysis. Requests are keyed by note — asking twice about the
     * same file updates the entry instead of piling up duplicates.
     *
     * A blank [question] never erases one already queued: the question is the expensive part (someone
     * typed it), the button press is cheap, so pressing 분석 요청 again without typing must not silently
     * throw away what you asked for last time. Passing a non-blank question does replace it.
     */
    fun addPending(
        relFile: String,
        question: String,
        reason: String,
        symbol: String = "",
        flow: String = "",
    ) {
        val path = canonicalPendingPath(relFile)
        // A file request, a "just this function" request and each requested scenario are different asks on
        // the same file, so they queue independently instead of overwriting one another.
        val key = Triple(path, symbol.trim(), flow.trim())
        val all = pending()
        val existing = all.firstOrNull { keyOf(it) == key }
        val kept = all.filter { keyOf(it) != key }
        val q = question.trim().ifEmpty { existing?.question.orEmpty() }
        writePending(kept + Pending(path, q, today(), reason, key.second, key.third))
    }

    private fun keyOf(p: Pending) = Triple(canonicalPendingPath(p.path), p.symbol, p.flow)

    /** Clear requests for [relFile]; with [symbol] only that one, otherwise every request on the file. */
    fun removePending(relFile: String, symbol: String? = null) {
        val key = canonicalPendingPath(relFile)
        val all = pending()
        val kept = all.filter { canonicalPendingPath(it.path) != key || (symbol != null && it.symbol != symbol) }
        if (kept.size != all.size) writePending(kept)
    }

    /** Clear one requested scenario — writing that diagram IS answering it. */
    fun removeFlowPending(relFile: String, flow: String) {
        val key = canonicalPendingPath(relFile)
        val all = pending()
        val kept = all.filter { canonicalPendingPath(it.path) != key || it.flow != flow }
        if (kept.size != all.size) writePending(kept)
    }

    /** Scenarios still queued for [relFile] — shown next to the diagrams that already exist. */
    fun flowRequests(relFile: String): List<Pending> {
        val key = canonicalPendingPath(relFile)
        return pending().filter { canonicalPendingPath(it.path) == key && it.flow.isNotEmpty() }
    }

    /** The pending entry covering [relFile], if any (a .cpp finds the request filed under its .h). */
    fun pendingFor(relFile: String): Pending? {
        val key = canonicalPendingPath(relFile)
        return pending().firstOrNull { canonicalPendingPath(it.path) == key }
    }

    private fun canonicalPendingPath(relFile: String): String {
        val dir = relFile.substringBeforeLast('/', "")
        val key = noteKeyFor(relFile)
        return if (dir.isEmpty()) key else "$dir/$key"
    }

    private fun writePending(requests: List<Pending>) {
        val obj = JsonObject()
        obj.addProperty("version", 1)
        obj.add("requests", JsonArray().apply {
            requests.forEach { p ->
                add(JsonObject().apply {
                    addProperty("path", p.path)
                    if (p.question.isNotEmpty()) addProperty("question", p.question)
                    if (p.symbol.isNotEmpty()) addProperty("symbol", p.symbol)
                    if (p.flow.isNotEmpty()) addProperty("flow", p.flow)
                    addProperty("requestedAt", p.requestedAt)
                    addProperty("reason", p.reason)
                })
            }
        })
        writeJson(pendingFile, obj)
    }

    /** `.codemap/` is per-machine scratch: keep it out of git without editing the project's .gitignore. */
    private fun ensureSelfIgnored() {
        val ignore = File(codemapDir, ".gitignore")
        if (!ignore.exists()) runCatching { ignore.writeText("*\n", Charsets.UTF_8) }
    }
}
