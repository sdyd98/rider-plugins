package com.example.codemap

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The call graph that the notes, taken together, already describe.
 *
 * Every analyzed file records `functions[].calls`, so stitching those across notes yields a graph that
 * spans files without anyone drawing it. It is built entirely from recorded text — no code is parsed
 * and nothing is inferred — which also fixes its meaning precisely: this is **what the notes say**, over
 * **the files that have been analyzed**. It is not, and must not be presented as, a complete answer to
 * "who calls this"; that question belongs to Rider's Find Usages.
 *
 * Pure data in, pure data out: no IDE types, so the layering is covered by headless tests.
 */
class CallIndex(notes: List<Pair<String, JsonObject>>) {

    /**
     * A function in the graph. [file] is the note it came from, or empty when the notes only ever
     * mention it as a call target — an unanalyzed neighbour. Those are kept rather than dropped: "this
     * calls something we have not looked at yet" is information, and hiding the edge would make the
     * graph look more complete than it is.
     */
    data class Node(val name: String, val file: String) {
        val analyzed: Boolean get() = file.isNotEmpty()
    }

    /** [layers] runs from the most distant caller to the most distant callee; [focusIndex] is the middle. */
    data class Graph(val layers: List<List<Node>>, val focusIndex: Int, val edges: Set<Pair<String, String>>)

    private val nodes = LinkedHashMap<String, Node>()
    private val calls = HashMap<String, MutableSet<String>>()
    private val callers = HashMap<String, MutableSet<String>>()

    /** Bare name (after the last `::`) → the full names that end with it. Used to link across files. */
    private val byBareName = HashMap<String, MutableList<String>>()

    init {
        notes.forEach { (rel, note) ->
            (note.get("functions") as? JsonArray)?.forEach { el ->
                val f = el as? JsonObject ?: return@forEach
                val name = f.str("name") ?: return@forEach
                nodes.putIfAbsent(name, Node(name, rel))
                byBareName.getOrPut(bare(name)) { mutableListOf() }.let { if (name !in it) it += name }
            }
        }
        // Second pass: edges, once every name is known, so a call can bind to a function declared later.
        notes.forEach { (_, note) ->
            (note.get("functions") as? JsonArray)?.forEach { el ->
                val f = el as? JsonObject ?: return@forEach
                val from = f.str("name") ?: return@forEach
                (f.get("calls") as? JsonArray)?.forEach { c ->
                    val raw = (c.takeIf { it.isJsonPrimitive })?.asString?.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    // Unresolvable (never analyzed, or an ambiguous bare name) becomes a leaf node.
                    val to = resolve(raw) ?: raw.also { nodes.putIfAbsent(it, Node(it, "")) }
                    if (to == from) return@forEach
                    calls.getOrPut(from) { linkedSetOf() } += to
                    callers.getOrPut(to) { linkedSetOf() } += from
                }
            }
        }
    }

    val size: Int get() = nodes.size

    fun node(name: String): Node? = nodes[name]

    /**
     * Map a recorded call target onto a known function.
     *
     * Exact match wins. Otherwise the bare name is tried, and ONLY when it identifies exactly one
     * function — an ambiguous `Send` that could be three different classes' methods is left unlinked
     * rather than wired to a guess.
     *
     * Only ANALYZED functions are candidates. The unanalyzed leaves this class creates for unresolved
     * call targets must not become resolution targets themselves, or the first unresolved `Send` would
     * make every later `Send` "resolve" to it.
     */
    fun resolve(raw: String): String? {
        nodes[raw]?.takeIf { it.analyzed }?.let { return raw }
        return byBareName[bare(raw)].orEmpty().singleOrNull()
    }

    /**
     * Callers above [focus] and callees below, out to [depth] hops each way.
     *
     * A node is placed in the first layer that reaches it, so a function that is both a caller and a
     * callee (a cycle, or a helper used at two levels) appears once rather than fanning out forever.
     */
    fun around(focus: String, depth: Int): Graph {
        // Focusing directly on an unanalyzed leaf is allowed — it just has no callees to show.
        val center = resolve(focus) ?: focus.takeIf { nodes.containsKey(it) }
            ?: return Graph(emptyList(), 0, emptySet())
        val layerOf = LinkedHashMap<String, Int>()
        layerOf[center] = 0

        fun walk(step: Int, next: (String) -> Set<String>) {
            var frontier = setOf(center)
            for (d in 1..depth) {
                val found = LinkedHashSet<String>()
                frontier.forEach { n -> next(n).forEach { if (it !in layerOf) found += it } }
                if (found.isEmpty()) break
                found.forEach { layerOf[it] = step * d }
                frontier = found
            }
        }
        walk(-1) { callers[it].orEmpty() }
        walk(1) { calls[it].orEmpty() }

        val grouped = layerOf.entries.groupBy({ it.value }, { it.key })
        val indices = grouped.keys.sorted()
        val layers = indices.map { i -> grouped.getValue(i).mapNotNull { nodes[it] } }
        val present = layerOf.keys
        val edges = present.flatMap { from ->
            calls[from].orEmpty().filter { it in present }.map { from to it }
        }.toSet()
        return Graph(layers, indices.indexOf(0).coerceAtLeast(0), edges)
    }

    private fun bare(name: String) = name.substringAfterLast("::")

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
