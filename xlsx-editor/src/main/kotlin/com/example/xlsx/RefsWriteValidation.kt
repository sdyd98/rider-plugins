package com.example.xlsx

import com.google.gson.JsonObject

/**
 * PURE validation for write_table_refs — everything here is a mechanical check of what the AI wrote
 * against (a) the shapes the viewer's loader can parse and (b) facts the tools can look up (existing
 * table keys, header-row cell values). No judgment: nothing here decides layouts, ids, or refs — it
 * only rejects entries the loader would choke on or that reference things that verifiably don't exist.
 *
 * Why so strict: [loadRefSchema] wraps the WHOLE parse in one runCatching — a single wrong-typed field
 * (e.g. `"display": ["Name","Grade"]`) nulls the ENTIRE schema for the viewer and validate_refs, with
 * no pointer to the offending table. Rejecting at write time, with the table named, is the only place
 * the failure can be attributed. `_`-prefixed keys are AI metadata and are never validated.
 */
internal object RefsWriteValidation {

    /** Type/shape check of one entry — extends the original file/sheet/id/refs check with the typed
     *  fields whose wrong shape would invalidate the whole schema at load time. Null = OK. */
    fun shape(entry: JsonObject): String? {
        if (entry.get("file")?.takeIf { it.isJsonPrimitive } == null) return "entry is missing \"file\" (workbook path relative to the data folder)"
        if (entry.get("sheet")?.takeIf { it.isJsonPrimitive } == null) return "entry is missing \"sheet\""
        for (k in listOf("headerRow", "dataStartRow")) {
            val v = entry.get(k)?.takeIf { !it.isJsonNull } ?: continue
            if (!v.isJsonPrimitive || !v.asJsonPrimitive.isNumber) return "\"$k\" must be a NUMBER (1-based row)"
        }
        entry.get("id")?.let { id ->
            if (!id.isJsonArray) return "\"id\" must be an array of column field codes"
            if (!id.asJsonArray.all { it.isJsonPrimitive }) return "\"id\" elements must be field-code strings"
        }
        entry.get("display")?.takeIf { !it.isJsonNull }?.let { d ->
            // display: null is the explicit "decided: no display column" marker — allowed.
            if (!d.isJsonPrimitive) return "\"display\" must be ONE field-code string (or null for 'no display column') — multi-column display is not supported"
        }
        entry.get("rowFilter")?.takeIf { !it.isJsonNull }?.let { rf ->
            if (!rf.isJsonObject) return "\"rowFilter\" must be an object of column -> accepted value(s) (same syntax as a ref's \"when\")"
            conditionShapeError(rf.asJsonObject, "\"rowFilter\"")?.let { return it }
        }
        val refs = entry.get("refs") ?: return null
        if (!refs.isJsonArray) return "\"refs\" must be an array"
        val arr = refs.asJsonArray
        for (i in 0 until arr.size()) {
            val ro = arr[i].takeIf { it.isJsonObject }?.asJsonObject ?: return "refs[$i] is not an object"
            val from = ro.get("from")?.takeIf { it.isJsonArray } ?: return "refs[$i] is missing \"from\" (array of column field codes)"
            if (!from.asJsonArray.all { it.isJsonPrimitive }) return "refs[$i] \"from\" elements must be field-code strings"
            // "to": one table id, or a NON-EMPTY array of ids (a union of tables loaded as one logical table).
            val to = ro.get("to") ?: return "refs[$i] is missing \"to\" (target table id, or an array of ids for a union)"
            val toIsUnion = to.isJsonArray
            if (toIsUnion) {
                val ta = to.asJsonArray
                if (ta.size() == 0 || !ta.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) return "refs[$i] \"to\" array must hold at least one table-id string"
            } else if (!to.isJsonPrimitive) {
                return "refs[$i] \"to\" must be a table id or an array of table ids"
            }
            ro.get("by")?.takeIf { !it.isJsonNull }?.let { by ->
                if (!by.isJsonArray || !by.asJsonArray.all { it.isJsonPrimitive }) return "refs[$i] \"by\" must be an array of the TARGET's id field codes"
                if (toIsUnion && to.asJsonArray.size() > 1) return "refs[$i] \"by\" is not supported with a union \"to\" — group refs need a single target"
            }
            for (k in listOf("split", "pattern")) {
                val v = ro.get(k)?.takeIf { !it.isJsonNull } ?: continue
                if (!v.isJsonPrimitive) return "refs[$i] \"$k\" must be a string"
            }
            val w = ro.get("when")
            if (w != null) {
                if (!w.isJsonObject) return "refs[$i] \"when\" must be an object of column -> accepted value(s)"
                conditionShapeError(w.asJsonObject, "refs[$i] \"when\"")?.let { return it }
            }
        }
        return null
    }

    /** Shape check shared by `when` and `rowFilter` clause objects: each column maps to a value, an
     *  array of values, or `{"in": ...}` / `{"notIn": ...}` with the same scalar/array payload. */
    private fun conditionShapeError(obj: JsonObject, label: String): String? {
        fun scalarOrArray(v: com.google.gson.JsonElement): Boolean =
            v.isJsonPrimitive || (v.isJsonArray && v.asJsonArray.all { it.isJsonPrimitive })
        for ((c, v) in obj.entrySet()) {
            val ok = scalarOrArray(v) || (
                v.isJsonObject && v.asJsonObject.entrySet().let { es ->
                    es.size == 1 && es.all { (k, vv) -> (k == "in" || k == "notIn") && scalarOrArray(vv) }
                }
                )
            if (!ok) return "$label.$c must be a value, an array of values, or {\"in\"/\"notIn\": value(s)}"
        }
        return null
    }

    /** A "filled" entry has a non-empty id — the overwrite guard protects exactly these. */
    fun isFilled(entry: JsonObject): Boolean =
        (runCatching { entry.getAsJsonArray("id") }.getOrNull()?.size() ?: 0) > 0

    /** Every field code the entry claims exists in ITS OWN sheet's header row: id + display +
     *  rowFilter columns + each ref's from + each ref's when-condition columns.
     *  (`by` names TARGET columns — separate.) */
    fun ownSheetCodes(entry: JsonObject): List<String> {
        val codes = ArrayList<String>()
        entry.get("id")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { codes.add(it.asString) }
        entry.get("display")?.takeIf { it.isJsonPrimitive }?.let { codes.add(it.asString) }
        entry.get("rowFilter")?.takeIf { it.isJsonObject }?.asJsonObject?.keySet()?.forEach { codes.add(it) }
        entry.get("refs")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { r ->
            val ro = r.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            ro.get("from")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { codes.add(it.asString) }
            ro.get("when")?.takeIf { it.isJsonObject }?.asJsonObject?.keySet()?.forEach { codes.add(it) }
        }
        return codes.filter { it.isNotBlank() }.distinct()
    }

    /** The (1..n) target table ids of one ref's `to` — a string, or an array for a union. */
    private fun toTargetsOf(ro: JsonObject): List<String> =
        ro.get("to")?.let { to ->
            when {
                to.isJsonPrimitive -> listOf(to.asString)
                to.isJsonArray -> to.asJsonArray.filter { it.isJsonPrimitive }.map { it.asString }
                else -> emptyList()
            }
        } ?: emptyList()

    /** Target table ids named by the entry's refs (union targets each count). */
    fun refTargets(entry: JsonObject): List<String> =
        entry.get("refs")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.flatMap { r -> r.takeIf { it.isJsonObject }?.asJsonObject?.let { toTargetsOf(it) } ?: emptyList() }
            ?.distinct() ?: emptyList()

    /** (target table, by-codes) pairs — `by` field codes live in the TARGET's header row. (`by` is
     *  rejected with a multi-target union at shape(), so a single target is guaranteed here.) */
    fun byCodesPerTarget(entry: JsonObject): List<Pair<String, List<String>>> =
        entry.get("refs")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { r ->
            val ro = r.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val to = toTargetsOf(ro).singleOrNull() ?: return@mapNotNull null
            val by = ro.get("by")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.filter { it.isJsonPrimitive }?.map { it.asString }?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            to to by
        } ?: emptyList()

    fun fileOf(entry: JsonObject): String? = entry.get("file")?.takeIf { it.isJsonPrimitive }?.asString
    fun sheetOf(entry: JsonObject): String? = entry.get("sheet")?.takeIf { it.isJsonPrimitive }?.asString

    /** Shape classification behind list_unfilled_tables — the cross-session progress query. */
    class TableProgress(
        /** No (non-empty) `id` yet — build_refs skeletons awaiting the layout/id judgment. */
        val unfilled: List<String>,
        /** Has an id but NO `refs` key — `refs: []` is the explicit "decided: no outgoing refs". */
        val undecidedRefs: List<String>,
        /** Has an id but NO `display` key — `display: null` is the explicit "decided: no name column".
         *  Orthogonal to [undecidedRefs]: a table can be undecided on both. */
        val undecidedDisplay: List<String>,
        /** Non-object entries — counted in NO bucket (previously they inflated "filled" silently). */
        val malformed: List<String>,
    )

    /** Classify every entry of a refs.json `tables` object by SHAPE only — no interpretation. */
    fun classify(tablesObj: JsonObject): TableProgress {
        val unfilled = ArrayList<String>()
        val undecidedRefs = ArrayList<String>()
        val undecidedDisplay = ArrayList<String>()
        val malformed = ArrayList<String>()
        for ((key, el) in tablesObj.entrySet()) {
            val t = el.takeIf { it.isJsonObject }?.asJsonObject
            if (t == null) {
                malformed.add(key)
                continue
            }
            if (!isFilled(t)) {
                unfilled.add(key)
                continue
            }
            if (!t.has("refs")) undecidedRefs.add(key) // key present (even as []) = decided
            if (!t.has("display")) undecidedDisplay.add(key) // key present (even as null) = decided
        }
        return TableProgress(unfilled, undecidedRefs, undecidedDisplay, malformed)
    }

    /** The entry's 1-based header row (same default the loader applies). */
    fun headerRowOf(entry: JsonObject): Int =
        runCatching { entry.get("headerRow")?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull() ?: 1
}
