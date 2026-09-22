package com.xiuyin.app.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Storage recovery evidence, explicitly TEST DATA; not an audio recognition accuracy test. */
@RunWith(AndroidJUnit4::class)
class FieldSessionRecoveryTest {
    @Test fun incompleteSessionSurvivesProcessRestartAndExports() = runBlocking {
        val phaseStartedAtMillis = System.currentTimeMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val marker = File(context.cacheDir, "field-recovery-test-owned-id.txt")
        val report = File(context.getExternalFilesDir(null), "field-recovery-result.json")
        val preparedPidFile = File(context.cacheDir, "field-recovery-test-owned-pid.txt")
        when (val phase = InstrumentationRegistry.getArguments().getString("fieldRecoveryPhase")) {
            "prepare" -> {
                report.writeText(FieldJson.encode(mapOf("phase" to "prepare", "phaseStartedAtMillis" to phaseStartedAtMillis, "chainPassed" to false, "status" to "STARTED")))
                val diagnostics = FieldDiagnostics.get(context)
                diagnostics.refresh()
                check(diagnostics.state.value.activeSession == null) { "存在活动会话，不覆盖用户数据" }
                check(!marker.exists()) { "上次测试恢复标记尚未处理" }
                val session = diagnostics.startSession("TEST DATA process-recovery", mapOf("purpose" to "instrumentation-crash-recovery"))
                diagnostics.recordLog("INFO", "Test", "ProcessRecoveryEvidence", "Actual local write before force-stop")
                diagnostics.refresh()
                marker.writeText(session.id)
                preparedPidFile.writeText(android.os.Process.myPid().toString())
                report.writeText(FieldJson.encode(mapOf("phase" to "prepare", "sessionId" to session.id,
                    "startedAtMillis" to session.startedAtMillis, "sessionStartedAtMillis" to session.startedAtMillis,
                    "phaseStartedAtMillis" to phaseStartedAtMillis, "preparedPid" to android.os.Process.myPid(),
                    "chainPassed" to false, "status" to "PREPARED", "recoveredStatus" to null)))
                assertTrue(File(context.filesDir, "field-sessions/${session.id}/app.log").readText().contains("ProcessRecoveryEvidence"))
                // Deliberately do not end: the external runner must force-stop before verify.
            }
            "verify" -> {
                report.writeText(FieldJson.encode(mapOf("phase" to "verify", "phaseStartedAtMillis" to phaseStartedAtMillis, "chainPassed" to false, "status" to "STARTED")))
                check(marker.isFile) { "缺少真实 prepare 阶段；禁止用空烟测冒充跨进程恢复" }
                val id = marker.readText().trim()
                check(id.matches(Regex("[a-zA-Z0-9-]+")))
                val diagnostics = FieldDiagnostics.get(context)
                diagnostics.refresh()
                val recovered = diagnostics.state.value.sessions.single { it.id == id }
                check(recovered.label == "TEST DATA process-recovery")
                assertEquals(FieldSessionStatus.INCOMPLETE, recovered.status)
                assertNull(recovered.endedAtMillis)
                val exported = diagnostics.exportSession(id)
                ZipFile(exported).use { zip ->
                    FieldSessionStore.REQUIRED_FILES.forEach { assertNotNull(it, zip.getEntry(it)) }
                    val log = zip.getInputStream(zip.getEntry("app.log")).reader().readText()
                    assertTrue(log.contains("ProcessRecoveryEvidence"))
                    assertTrue(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText().contains("INCOMPLETE"))
                }
                val preparedPid = preparedPidFile.readText().trim().toInt()
                assertNotEquals("force-stop 后必须为新进程", preparedPid, android.os.Process.myPid())
                val exportedSuccessfully = exported.isFile
                diagnostics.clearSessions(setOf(id))
                check(preparedPidFile.delete())
                check(marker.delete())
                report.writeText(FieldJson.encode(mapOf("phase" to "verify", "sessionId" to id,
                    "startedAtMillis" to recovered.startedAtMillis, "sessionStartedAtMillis" to recovered.startedAtMillis,
                    "phaseStartedAtMillis" to phaseStartedAtMillis, "preparedPid" to preparedPid, "currentPid" to android.os.Process.myPid(),
                    "chainPassed" to true, "status" to "PASSED", "recoveredStatus" to recovered.status.name,
                    "testEndTime" to null, "exported" to exportedSuccessfully, "logEvidenceCount" to 1)))
            }
            null -> {
                // Normal complete suite still performs a real production-store reopen on device.
                val owned = File(context.cacheDir, "field-store-recovery-${UUID.randomUUID()}")
                try {
                    val root = File(owned, "sessions"); val exports = File(owned, "exports")
                    val first = FieldSessionStore(root, exports, availableBytes = { android.os.StatFs(it.absolutePath).availableBytes })
                    val session = first.start("TEST DATA device-reopen", mapOf("appVersion" to "test"), mapOf("sampleRate" to 48000))
                    first.append("audio-events.jsonl", mapOf("frameIndex" to 42, "audioStreamId" to 3, "rawOnsetScores" to List(88) { .25 }))
                    first.checkpoint()
                    val recovered = FieldSessionStore(root, exports, availableBytes = { android.os.StatFs(it.absolutePath).availableBytes })
                    assertEquals(FieldSessionStatus.INCOMPLETE, recovered.sessions().single().status)
                    ZipFile(recovered.export(session.id)).use { zip ->
                        FieldSessionStore.REQUIRED_FILES.forEach { assertNotNull(it, zip.getEntry(it)) }
                        assertTrue(zip.getInputStream(zip.getEntry("audio-events.jsonl")).reader().readText().contains("42"))
                    }
                } finally {
                    check(owned.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                    check(!owned.exists() || owned.deleteRecursively())
                }
            }
            else -> error("非法 fieldRecoveryPhase：$phase")
        }
    }
}