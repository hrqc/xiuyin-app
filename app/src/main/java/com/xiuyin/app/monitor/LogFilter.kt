package com.xiuyin.app.monitor

import com.xiuyin.app.data.LogEntry

val LOG_LEVELS = listOf("INFO", "WARNING", "ERROR", "CRITICAL")

/** Filters the given snapshot without changing its order or backing data. */
fun filterLogs(logs: List<LogEntry>, query: String, level: String? = null): List<LogEntry> {
    require(level == null || level in LOG_LEVELS) { "不支持的日志等级" }
    val search = query.trim()
    return logs.filter { entry ->
        (level == null || entry.level == level) &&
            (search.isEmpty() || listOf(entry.module, entry.message, entry.detail, entry.level)
                .any { it.contains(search, ignoreCase = true) })
    }
}
