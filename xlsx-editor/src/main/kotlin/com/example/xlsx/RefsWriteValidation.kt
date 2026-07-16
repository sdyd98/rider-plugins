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
        val refs = entry.get("refs") ?: return null
        if (!refs.isJsonArray) return "\"refs\" must be an array"
        val arr = refs.asJsonArray
        for (i in 0 until arr.size()) {
            val ro = arr[i].takeIf { it.isJsonObject }?.asJsonObject ?: return "refs[$i] is not an object"
            val from = ro.get("from")?.takeIf { it.isJsonArray } ?: return "refs[$i] is missing \"from\" (array of column field codes)"
            if (!from.asJsonArray.all { it.isJsonPrimitive }) return "refs[$i] \"from\" elements must be field-code strings"
            if (ro.get("to")?.takeIf { it.isJsonPrimitive } == null) return "refs[$i] is missing \"to\" (target table id)"
            ro.get("by")?.takeIf { !it.isJsonNull }?.let { by ->
                if (!by.isJsonArray || !by.asJsonArray.all { it.isJsonPrimitive }) return "refs[$i] \"by\" must be an array of the TARGET's id field codes"
            }
            for (k in listOf("split", "pattern")) {
                val v = ro.get(k)?.takeIf { !it.isJsonNull } ?: continue
                if (!v.isJsonPrimitive) return "refs[$i] \"$k\" must be a string"
            }
            val w = ro.get("when")
            if (w != null) {
                if (!w.isJsonObject) return "refs[$i] \"when\" must be an object of column -> accepted value(s)"
                for ((c, v) in w.asJsonObject.entrySet()) {
                    if (!(v.isJsonPrimitive || (v.isJsonArray && v.asJsonArray.all { it.isJsonPrimitive })))
                        return "refs[$i] \"when\".$c must be a value or an array of values"
                }
            }
        }
        return null
    }

    /** A "filled" entry has a non-empty id — the overwrite guard protects exactly these. */
    fun isFilled(entry: JsonObject): Boolean =
        (runCatching { entry.getAsJsonArray("id") }.getOrNull()?.size() ?: 0) > 0

    /** Every field code the entry claims exists in ITS OWN sheet's header row: id + display +
     *  each ref's from + each ref's when-condition columns. (`by` names TARGET columns — separate.) */
    fun ownSheetCodes(entry: JsonObject): List<String> {
        val codes = ArrayList<String>()
        entry.get("id")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { codes.add(it.asString) }
        entry.get("display")?.takeIf { it.isJsonPrimitive }?.let { codes.add(it.asString) }
        entry.get("refs")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { r ->
            val ro = r.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            ro.get("from")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { codes.add(it.asString) }
            ro.get("when")?.takeIf { it.isJsonObject }?.asJsonObject?.keySet()?.forEach { codes.add(it) }
        }
        return codes.filter { it.isNotBlank() }.distinct()
    }

    /** Target table ids named by the entry's refs. */
    fun refTargets(entry: JsonObject): List<String> =
        entry.get("refs")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { r -> r.takeIf { it.isJsonObject }?.asJsonObject?.get("to")?.takeIf { it.isJsonPrimitive }?.asString }
            ?.distinct() ?: emptyList()

    /** (target table, by-codes) pairs — `by` field codes live in the TARGET's header row. */
    fun byCodesPerTarget(entry: JsonObject): List<Pair<String, List<String>>> =
        entry.get("refs")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { r ->
            val ro = r.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val to = ro.get("to")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            val by = ro.get("by")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.filter { it.isJsonPrimitive }?.map { it.asString }?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            to to by
        } ?: emptyList()

    fun fileOf(entry: JsonObject): String? = entry.get("file")?.takeIf { it.isJsonPrimitive }?.asString
    fun sheetOf(entry: JsonObject): String? = entry.get("sheet")?.takeIf { it.isJsonPrimitive }?.asString

    /** The entry's 1-based header row (same default the loader applies). */
    fun headerRowOf(entry: JsonObject): Int =
        runCatching { entry.get("headerRow")?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull() ?: 1
}
