package com.example.logview

import java.util.regex.Pattern

/**
 * A user-defined coloring rule, GrepConsole-style: lines whose text matches [pattern] are painted
 * with [foregroundRgb] / [backgroundRgb] (either may be null = leave default) and optionally [bold].
 * The compiled [Pattern] is cached lazily so the hot render path doesn't recompile per line.
 *
 * Colors are stored as plain RGB ints (not theme-aware) so they round-trip through persistence; the
 * renderer derives a readable variant per theme.
 */
class HighlightRule(
    var pattern: String,
    var foregroundRgb: Int? = null,
    var backgroundRgb: Int? = null,
    var bold: Boolean = false,
    var enabled: Boolean = true,
    /** When false, only matching substrings are emphasized; the bg tint still applies to the row. */
    var caseInsensitive: Boolean = true,
) {
    @Transient private var compiled: Pattern? = null
    @Transient private var compiledFor: String? = null

    /** The compiled regex, or null if [pattern] is blank / invalid. Recompiled when [pattern] changes. */
    fun matcher(): Pattern? {
        if (pattern.isBlank()) return null
        if (compiledFor != pattern) {
            compiled = runCatching {
                Pattern.compile(pattern, if (caseInsensitive) Pattern.CASE_INSENSITIVE else 0)
            }.getOrNull()
            compiledFor = pattern
        }
        return compiled
    }

    fun matches(text: String): Boolean = matcher()?.matcher(text)?.find() ?: false
}
