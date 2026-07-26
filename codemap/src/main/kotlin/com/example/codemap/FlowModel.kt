package com.example.codemap

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * A recorded flow, parsed into the steps a presentation walks through.
 *
 * Modelled on IcePanel's flows rather than on a UML sequence diagram, because the two answer differently.
 * A lifeline chart is a second picture of the system that has to be kept in step with the first; a flow is
 * an ordered path **over the diagram you already have**, and stepping through it shows the same objects
 * lighting up in turn. For a codebase that is being learned one file at a time, "watch the call travel"
 * beats "read a chart of it".
 *
 * Pure data — no Compose, no IDE types — so the parsing is covered by headless tests.
 */
object FlowModel {

    enum class Kind {
        /**
         * A packet crossing between endpoints — the spine of a flow in a game server.
         *
         * Drawn differently from everything else because it is the thing being traced: a packet step carries
         * the constant's real name and, when the code gives one, its id.
         */
        PACKET,

        /** One object calling another: the step that connects two packets. */
        MESSAGE,

        /** A value coming back. Same edge, drawn as a return. */
        RETURN,

        /** One object doing something by itself — IcePanel's Process step. */
        PROCESS,

        /** Text with no object attached: scene-setting, a caveat, a conclusion. */
        NOTE,
    }

    /**
     * One step. [label] is what happens (a function or packet name); [description] is the sentence
     * explaining it, which the step list shows and the diagram does not.
     */
    data class Step(
        val index: Int,
        val kind: Kind,
        val from: String,
        val to: String,
        val label: String,
        val description: String,
        /** The packet id as the code writes it (`0x01`, `12`, an enum value) — empty when not recorded. */
        val packetId: String = "",
    ) {
        /** Both ends known and distinct — the only case that lights up an edge. */
        val isEdge: Boolean get() = kind != Kind.NOTE && from.isNotEmpty() && to.isNotEmpty() && from != to
    }

    /** A parsed flow: its steps and the participants in the order they first appear. */
    data class Flow(val name: String, val steps: List<Step>, val participants: List<String>)

    /**
     * Parse one `flows` entry.
     *
     * Two recorded shapes are accepted, both of which exist in notes already: `steps` of objects
     * (`{from,to,call}`) and `steps` of plain strings. A string step becomes a [Kind.NOTE] — there is no
     * object to attribute it to, and inventing one would be exactly the interpretation this plugin does not
     * do.
     */
    fun parse(flow: JsonObject): Flow {
        val name = flow.str("name").orEmpty()
        val raw = flow.get("steps") as? JsonArray ?: return Flow(name, emptyList(), emptyList())

        val steps = raw.mapIndexedNotNull { i, el ->
            when {
                el.isJsonPrimitive -> Step(
                    index = i,
                    kind = Kind.NOTE,
                    from = "",
                    to = "",
                    label = el.asString.orEmpty(),
                    description = "",
                )

                el is JsonObject -> {
                    val from = el.str("from").orEmpty()
                    val to = el.str("to").orEmpty()
                    val packet = el.str("packet")
                    val label = packet ?: el.str("call") ?: el.str("label").orEmpty()
                    val declared = el.str("kind")
                    val kind = when {
                        // A recorded packet name settles it: this step IS the packet. Checked before the
                        // declared kind so an older note that wrote {packet, kind:"return"} still reads as
                        // a packet going the other way rather than as a value coming back.
                        packet != null -> Kind.PACKET
                        declared == "return" -> Kind.RETURN
                        declared == "process" -> Kind.PROCESS
                        declared == "note" -> Kind.NOTE
                        // Not declared: a step with one end, or both ends the same, is something an object
                        // does to itself. That is arithmetic on what was recorded, not a guess.
                        from.isEmpty() || to.isEmpty() || from == to -> Kind.PROCESS
                        else -> Kind.MESSAGE
                    }
                    Step(
                        index = i,
                        kind = kind,
                        from = from.ifEmpty { to },
                        to = to.ifEmpty { from },
                        label = label,
                        description = el.str("description") ?: el.str("note").orEmpty(),
                        packetId = el.str("id").orEmpty(),
                    )
                }

                else -> null
            }
        }

        val participants = LinkedHashSet<String>().apply {
            steps.forEach { s ->
                if (s.kind == Kind.NOTE) return@forEach
                if (s.from.isNotEmpty()) add(s.from)
                if (s.to.isNotEmpty()) add(s.to)
            }
        }.toList()

        return Flow(name, steps, participants)
    }

    /** Packets only, in order — the flow's spine, without the processing between. */
    fun packets(flow: Flow): List<Step> = flow.steps.filter { it.kind == Kind.PACKET }

    /**
     * Every distinct connection the flow uses, in order of first use.
     *
     * The diagram draws one edge per connection, not one per step: two messages between the same pair are
     * the same arrow used twice, which is what makes a busy flow readable.
     */
    fun connections(flow: Flow): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        flow.steps.filter { it.isEdge }.forEach { out += it.from to it.to }
        return out.toList()
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
