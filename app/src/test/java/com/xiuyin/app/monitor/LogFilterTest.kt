package com.xiuyin.app.monitor

import com.xiuyin.app.data.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LogFilterTest {
    private val logs = listOf(
        LogEntry(3, 3000, "ERROR", "Database", "保存失败", "SQLiteException: disk full"),
        LogEntry(2, 2000, "WARNING", "App", "数据尚未保存"),
        LogEntry(1, 1000, "INFO", "App", "应用启动"),
    )

    @Test
    fun emptyQueryRetainsAllEntriesAndNewestFirstOrder() {
        assertEquals(logs, filterLogs(logs, "  "))
        assertEquals(listOf(3L, 2L, 1L), logs.map { it.id })
    }

    @Test
    fun searchIncludesDetailAndModuleAndIgnoresAsciiCase() {
        assertEquals(listOf(logs[0]), filterLogs(logs, " sqliteexception "))
        assertEquals(listOf(logs[0]), filterLogs(logs, "DATABASE"))
        assertEquals(listOf(logs[0], logs[1]), filterLogs(logs, "保存"))
    }

    @Test
    fun levelAndTextBothNeedToMatch() {
        assertEquals(listOf(logs[1]), filterLogs(logs, "保存", "WARNING"))
        assertEquals(emptyList<LogEntry>(), filterLogs(logs, "保存", "INFO"))
        assertEquals(listOf(logs[0]), filterLogs(logs, "", "ERROR"))
    }

    @Test
    fun filteringNeverMutatesFrozenSnapshotAndUnknownLevelIsRejected() {
        val frozen = logs.toList()
        filterLogs(frozen, "App", "INFO")
        assertEquals(logs, frozen)
        assertThrows(IllegalArgumentException::class.java) { filterLogs(logs, "", "DEBUG") }
    }
}
