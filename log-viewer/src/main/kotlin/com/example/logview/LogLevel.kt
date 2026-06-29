package com.example.logview

/**
 * Severity of a log line, as detected by [LogParser]. [OTHER] means no level token was found (plain
 * text, banners, or — for continuation lines — the level is inherited from the owning block).
 *
 * Ordinal order is severity-descending (ERROR first).
 */
enum class LogLevel(val label: String) {
    ERROR("ERROR"),
    WARN("WARN"),
    INFO("INFO"),
    DEBUG("DEBUG"),
    TRACE("TRACE"),
    OTHER("·");

    companion object {
        /** The levels that carry a real severity (everything except [OTHER]) — for the level pills. */
        val REAL: List<LogLevel> = listOf(ERROR, WARN, INFO, DEBUG, TRACE)
    }
}
