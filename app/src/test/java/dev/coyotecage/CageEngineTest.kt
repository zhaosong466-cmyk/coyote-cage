package dev.coyotecage

import dev.coyotecage.dglab.DglabV4Client
import dev.coyotecage.pose.PoseFrame
import dev.coyotecage.pose.PosePoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CageEngineTest {
    private class Fixture(val scope: TestScope) {
        val client = DglabV4Client().apply { connectDebug() }
        var config = CageSettings(graceMs = 0, prepareSec = 0)
        val engine = CageEngine(client, { config }, { NormRect.DEFAULT },
            StandardTestDispatcher(scope.testScheduler), { 10000 + scope.testScheduler.currentTime })
        fun pose(x: Float) = engine.onPose(PoseFrame(
            List(33) { PosePoint(x, 0.5f, 1f) },
            10000 + scope.testScheduler.currentTime, 640, 480))
        fun log() = client.debugLog.value.map { it.text }
        fun pulses() = log().count { it.startsWith("波形 ") }
        fun stop() { engine.emergency(); scope.runCurrent() }
    }

    @Test fun prepareCountdownBlocksJudgingUntilItEnds() = runTest {
        val f = Fixture(this)
        f.config = f.config.copy(prepareSec = 3)
        f.engine.start()
        runCurrent()
        assertTrue(f.engine.ui.value.preparing)

        // 倒计时期间持续喂姿态（真实相机也是每帧一喂），步长小于 900ms 的过期窗口
        repeat(4) {
            f.pose(0.95f)
            advanceTimeBy(700)
            runCurrent()
        }
        assertEquals(0, f.pulses())
        assertTrue(f.engine.ui.value.preparing)

        f.pose(0.95f)
        advanceTimeBy(700)
        runCurrent()
        assertFalse(f.engine.ui.value.preparing)
        assertEquals(1, f.pulses())
        f.stop()
    }

    @Test fun poseModeLocksReferenceAndPunishesMovement() = runTest {
        val f = Fixture(this)
        f.config = f.config.copy(frameMode = FrameMode.POSE, poseTolerance = 3)
        f.pose(0.5f)
        f.engine.start(); runCurrent()
        assertTrue(f.engine.ui.value.poseLocked)
        assertEquals(0, f.pulses())

        // 横向 2% ≈ 12.8px / 480 ≈ 2.7%，在 3% 容差内
        f.pose(0.52f); advanceTimeBy(60); runCurrent()
        assertEquals(0, f.pulses())

        // 横向 12% ≈ 76.8px / 480 ≈ 16%，超容差
        f.pose(0.62f); advanceTimeBy(60); runCurrent()
        assertEquals(1, f.pulses())
        assertTrue(f.engine.ui.value.violating)
        f.stop()
    }

    @Test fun zeroGraceRequiresFreshOutsidePose() = runTest {
        val f = Fixture(this)
        f.engine.start()
        runCurrent()
        assertEquals(0, f.pulses())
        f.pose(0.5f)
        advanceTimeBy(60); runCurrent()
        assertEquals(0, f.pulses())
        f.pose(0.95f)
        advanceTimeBy(60); runCurrent()
        assertEquals(1, f.pulses())
        f.stop()
    }

    @Test fun returningInsideOrLosingPoseZerosBothChannels() = runTest {
        for (losePose in listOf(false, true)) {
            val f = Fixture(this)
            f.pose(0.95f)
            f.engine.start(); runCurrent()
            assertEquals(1, f.pulses())
            if (losePose) advanceTimeBy(1000) else {
                f.pose(0.5f)
                advanceTimeBy(60)
            }
            runCurrent()
            assertFalse(f.engine.ui.value.violating)
            assertEquals(listOf("归零 A", "归零 B", "RPC device.op.clear"), f.log().takeLast(3))
            f.stop()
        }
    }

    @Test fun emergencyCancelsOutputBeforeItStarts() = runTest {
        val f = Fixture(this)
        f.pose(0.95f)
        f.engine.start()
        f.engine.emergency()
        runCurrent()
        advanceTimeBy(10000); runCurrent()
        assertEquals(0, f.pulses())
        assertFalse(f.engine.ui.value.running)
    }

    @Test fun emergencyCancelsQueuedAndActiveTests() = runTest {
        for (startTest in listOf(false, true)) {
            val f = Fixture(this)
            f.engine.testOnce()
            if (startTest) runCurrent()
            f.stop()
            val stopped = f.log()
            advanceTimeBy(10000); runCurrent()
            assertEquals(stopped, f.log())
            assertEquals(if (startTest) 1 else 0, f.pulses())
        }
    }

    @Test fun startCancelsOldTestCleanup() = runTest {
        val f = Fixture(this)
        f.engine.testOnce(); runCurrent()
        f.config = f.config.copy(channel = 1)
        f.pose(0.95f)
        f.engine.start(); runCurrent()
        assertEquals(2, f.pulses())
        val started = f.log()
        advanceTimeBy(800); runCurrent()
        assertEquals(started, f.log())
        f.stop()
    }

    @Test fun repeatedTestsAndTestsDuringMonitoringDoNotOverlap() = runTest {
        val f = Fixture(this)
        f.engine.testOnce()
        f.engine.testOnce(); runCurrent()
        assertEquals(1, f.pulses())
        f.engine.start(); runCurrent()
        f.engine.testOnce(); runCurrent()
        assertEquals(1, f.pulses())
        f.stop()
    }
}
