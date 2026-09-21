package com.xiuyin.app.practice

import org.junit.Assert.*
import org.junit.Test

class PracticeSessionTest {
    private fun session(mode: CompanionMode = CompanionMode.FREE, target: Int = 2) = PracticeSession(
        "00000000-0000-0000-0000-000000000001", "TEST DATA", 4, ScoreFollowerTest.fixture(60, 62),
        PracticeConfig(mode, repeatTarget = target), 1000, "SYNTHETIC TEST EVIDENCE")
    @Test fun openingAndSilenceNeverCreatePracticedEvidence() {
        val session = session()
        val frames = ScoreFollowerTest.Frames(session::accept)
        repeat(100) { frames.frame() }
        assertEquals(0, session.report(2000).confirmedInputs)
        assertEquals(0, session.report(2000).wrong)
    }
    @Test fun reinforcementRequiresTheWholeRangeCorrectConsecutively() {
        val session = session(CompanionMode.REINFORCE)
        val frames = ScoreFollowerTest.Frames(session::accept)
        frames.note(60); frames.note(62)
        assertEquals(1, session.consecutivePasses); assertEquals(CompanionPhase.RUNNING, session.phase)
        frames.note(66)
        assertEquals(0, session.consecutivePasses); assertEquals(CompanionPhase.LOCKED, session.phase)
        frames.note(60); frames.note(62)
        assertEquals(0, session.consecutivePasses)
        repeat(2) { frames.note(60); frames.note(62) }
        assertEquals(CompanionPhase.COMPLETED, session.phase)
        assertEquals(2, session.consecutivePasses); assertEquals(1, session.wrong)
    }
    @Test fun loopContinuesAfterAWholePass() {
        val session = session(CompanionMode.LOOP)
        val frames = ScoreFollowerTest.Frames(session::accept)
        frames.note(60); frames.note(62)
        assertEquals(CompanionPhase.RUNNING, session.phase); assertEquals(1, session.completedPasses)
        assertEquals(0, session.follower.cursor)
    }
    @Test fun pauseFreezesAndResumeSupportsNewAudioClockWithoutBypassingLock() {
        val session = session(CompanionMode.STOP_ON_ERROR)
        val frames = ScoreFollowerTest.Frames(session::accept)
        frames.note(66); session.pause()
        frames.note(60); assertEquals(0, session.correct)
        session.resume(); assertEquals(CompanionPhase.LOCKED, session.phase)
        ScoreFollowerTest.Frames(session::accept).note(60)
        assertEquals(CompanionPhase.RUNNING, session.phase); assertEquals(1, session.correct)
    }
    @Test fun manualSeekDoesNotAwardACleanWholePass() {
        val session = session(CompanionMode.REINFORCE, 1)
        session.seekMeasure(1)
        ScoreFollowerTest.Frames(session::accept).note(62)
        assertEquals(0, session.consecutivePasses); assertEquals(CompanionPhase.RUNNING, session.phase)
    }
    @Test fun reportHasRevisionRawEvidenceAndNoFabricatedAccuracy() {
        val session = session()
        val frames = ScoreFollowerTest.Frames(session::accept)
        frames.note(66); frames.note(60); frames.note(62)
        val report = session.report(2000)
        assertEquals(4L, report.revisionGeneration); assertEquals(3, report.confirmedInputs)
        assertEquals(2, report.correct); assertEquals(1, report.wrong); assertTrue(report.completed)
        assertEquals(setOf(66), report.evidence.first().detectedMidi)
        assertThrows(IllegalArgumentException::class.java) { session.report(999) }
    }
}
