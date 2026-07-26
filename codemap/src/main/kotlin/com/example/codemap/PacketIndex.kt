package com.example.codemap

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Every packet any note recorded, and which note recorded it.
 *
 * "누가 2999를 보내나" is a question this codebase asks constantly and had no way to answer: a note's
 * `packets` table only says what THAT file handles, and the answer usually lives in another file. Walking
 * every note gives the other half — the same trick [FlowIndex] uses for flows and [CallIndex] for calls.
 *
 * The link is exact string equality on the recorded id, and nothing else. Ids are written by hand into notes
 * (`ClientPacket::LoginReq (1001)`, `0x81`, `2999`), so [key] strips the label down to what can be compared
 * without guessing: the numeric or hex literal if there is one, otherwise the whole trimmed string.
 *
 * Pure data in, pure data out: no IDE types, covered by headless tests.
 */
object PacketIndex {

    /** One packet as one note recorded it. */
    data class Entry(
        /** Root-relative path of the note's primary file. */
        val owner: String,
        /** The id exactly as recorded, decoration and all. */
        val id: String,
        /** `"in"`, `"out"`, or empty when the note did not say. */
        val dir: String,
        /** The function that handles it (inbound), as recorded. */
        val handler: String,
        /** What sends it (outbound), as recorded. */
        val sentBy: String,
    ) {
        val ownerName: String get() = owner.substringAfterLast('/')

        /** What this entry names, for comparison across notes. */
        val key: String get() = key(id)

        val inbound: Boolean get() = dir == "in"
        val outbound: Boolean get() = dir == "out"

        /** The function this entry points at, whichever side it is. */
        val symbol: String get() = handler.ifEmpty { sentBy }
    }

    /** Every packet in the store, in note order. */
    fun index(notes: List<Pair<String, JsonObject>>): List<Entry> =
        notes.flatMap { (rel, note) ->
            (note.get("packets") as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                Entry(
                    owner = rel,
                    id = id,
                    dir = o.str("dir").orEmpty(),
                    handler = o.str("handler").orEmpty(),
                    sentBy = o.str("sentBy").orEmpty(),
                )
            }
        }

    /**
     * Where else the packets of [rel] turn up.
     *
     * Keyed by [key] so `ClientPacket::LoginReq (1001)` in one note finds `1001` in another — the two are
     * written differently by different analyses of the same protocol, and refusing to match them would make
     * the index answer nothing.
     */
    fun elsewhere(
        notes: List<Pair<String, JsonObject>>,
        rel: String,
        note: JsonObject?,
    ): Map<String, List<Entry>> {
        val mine = (note?.get("packets") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("id") }
            .associateBy { key(it) }
        if (mine.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<Entry>>()
        index(notes).filter { it.owner != rel }.forEach { entry ->
            val recorded = mine[entry.key] ?: return@forEach
            out.getOrPut(recorded) { mutableListOf() }.add(entry)
        }
        return out
    }

    /**
     * The comparable part of a recorded id.
     *
     * A number is the one thing every way of writing a packet id agrees on, so it wins when present. Without
     * one there is nothing to normalise and the whole string stands — matching then requires the two notes
     * to have written it identically, which is the honest outcome.
     */
    fun key(id: String): String {
        val hex = HEX.find(id)?.value
        if (hex != null) return hex.lowercase()
        val dec = DEC.find(id)?.value
        return dec ?: id.trim()
    }

    private val HEX = Regex("0[xX][0-9a-fA-F]+")
    private val DEC = Regex("\\d+")

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
