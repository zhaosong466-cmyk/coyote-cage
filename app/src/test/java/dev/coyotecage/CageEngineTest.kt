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
        var config = CageSettings(graceMs = 0)
        val engine = CageEngine(client, { config }, { NormRect.DEFAULT },
            StandardTestDispatcher(scope.testScheduler), { 10000 + scope.testScheduler.currentTime })
        fun pose(x: Float) = engine.onPose(PoseFrame(
            List(33) { PosePoint(x, 0.5f, 1f) },
            10000 + scope.testScheduler.currentTime, 640, 480))
        fun log() = client.debugLog.value.map { it.text }
        fun pulses() = log().count { it.startsWith("波形 ") }
        fun stop() { engine.emergency(); scope.runCurrent() }
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
