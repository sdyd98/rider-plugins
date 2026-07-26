package com.example.codemap

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Every recorded flow, and which file's note holds it.
 *
 * Flows are stored per note, but they do not stay there: a scenario like "로그인부터 월드 입장까지" runs
 * through four classes, and it is filed under whichever file you happened to ask from. So reading `World.cpp`
 * would show you nothing about the three flows `World` takes part in — they belong to `PlayerSession.h`.
 * Stitching the notes together fixes that, the same way [CallIndex] does for calls.
 *
 * The link is a string comparison and nothing more: a participant matches a file when its leading identifier
 * equals the file's base name or one of the class names that file's own note recorded. No inference about
 * types, no fuzzy matching — and because two files can hold a class of the same name, the owning file is
 * always shown next to the flow so a person can tell.
 *
 * Pure data in, pure data out: no IDE types, so the whole thing is covered by headless tests.
 */
object FlowIndex {

    /** One flow, with the note that holds it. */
    data class Entry(
        /** Root-relative path of the note's primary file. */
        val owner: String,
        val name: String,
        val steps: Int,
        val participants: List<String>,
    ) {
        val ownerName: String get() = owner.substringAfterLast('/')
    }

    /** Every flow in the store, in note order. */
    fun index(notes: List<Pair<String, JsonObject>>): List<Entry> =
        notes.flatMap { (rel, note) ->
            (note.get("flows") as? JsonArray).orEmpty().mapNotNull { el ->
                val flow = el as? JsonObject ?: return@mapNotNull null
                val name = flow.str("name") ?: return@mapNotNull null
                Entry(
                    owner = rel,
                    name = name,
                    steps = (flow.get("steps") as? JsonArray)?.size() ?: 0,
                    participants = participantsOf(flow),
                )
            }
        }

    /**
     * The names by which other notes might refer to [rel]: its base name, plus whatever classes its own
     * note says it holds.
     *
     * `Server/Net/PlayerSession.h` answers to `PlayerSession`, and if its note records
     * `classes: [{name: "SessionGuard"}]` it answers to that too — a participant naming a class is naming
     * the file that declares it.
     */
    fun namesOf(rel: String, note: JsonObject?): Set<String> {
        val out = LinkedHashSet<String>()
        rel.substringAfterLast('/').substringBeforeLast('.').takeIf { it.isNotBlank() }?.let(out::add)
        (note?.get("classes") as? JsonArray)?.forEach { el ->
            (el as? JsonObject)?.str("name")?.let(out::add)
        }
        return out
    }

    /**
     * Flows held by OTHER notes in which [rel] appears as a participant.
     *
     * Its own flows are left out — the panel lists those separately, and a file appearing in its own flow is
     * not news.
     */
    fun appearances(
        notes: List<Pair<String, JsonObject>>,
        rel: String,
        note: JsonObject?,
    ): List<Entry> {
        val names = namesOf(rel, note)
        if (names.isEmpty()) return emptyList()
        return index(notes)
            .filter { it.owner != rel }
            .filter { entry -> entry.participants.any { leadingIdentifier(it) in names } }
    }

    /** Participants in order of first appearance — the same order the diagram lays its columns out in. */
    private fun participantsOf(flow: JsonObject): List<String> {
        val out = LinkedHashSet<String>()
        (flow.get("steps") as? JsonArray)?.forEach { el ->
            val step = el as? JsonObject ?: return@forEach
            step.str("from")?.let(out::add)
            step.str("to")?.let(out::add)
        }
        return out.toList()
    }

    /**
     * The identifier a recorded participant starts with.
     *
     * Participants are written by hand and pick up decoration — `PlayerSession(등록 세션들)`, `World *`.
     * Taking the leading identifier is what lets those still match the file they name, without matching on
     * substrings (which would make `Session` hit `PlayerSession`).
     */
    internal fun leadingIdentifier(participant: String): String {
        val t = participant.trim()
        val end = t.indexOfFirst { !(it.isLetterOrDigit() || it == '_') }
        return if (end < 0) t else t.take(end)
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
