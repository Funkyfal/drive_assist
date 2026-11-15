package com.geely.assist.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModePolicyTest {
    private static final int ECO = 1, COMFORT = 2, SPORT = 3;

    private static ModePolicy policy(boolean learning, Integer saved) {
        ModePolicy p = new ModePolicy(ECO, COMFORT, SPORT);
        p.setLearning(learning);
        p.load(saved);
        return p;
    }

    @Test public void savedModeIsWrittenBackInParkAfterWake() {
        ModePolicy p = policy(false, ECO);
        p.onWake(0);
        assertEquals(Integer.valueOf(ECO), p.observe(COMFORT, true, 0).write);
        assertNull(p.observe(ECO, true, 2_000).write);
        assertFalse(p.isPending());
    }

    @Test public void nothingIsWrittenWhileNotParked() {
        ModePolicy p = policy(false, ECO);
        p.onWake(0);
        assertNull(p.observe(COMFORT, false, 0).write);
        assertNull(p.observe(COMFORT, false, 30_000).write);
        assertEquals(Integer.valueOf(ECO), p.observe(COMFORT, true, 31_000).write);
    }

    @Test public void retriesAreSpacedAndLimited() {
        ModePolicy p = policy(false, ECO);
        p.onWake(0);
        int writes = 0;
        for (long t = 2_000; t <= 200_000; t += 2_000) {
            if (p.observe(COMFORT, true, t).write != null) writes++;
        }
        assertEquals(ModePolicy.MAX_WRITES, writes);
        assertFalse(p.isPending());
    }

    @Test public void givesUpAfterTheWindow() {
        ModePolicy p = policy(false, ECO);
        p.onWake(0);
        assertNull(p.observe(COMFORT, false, ModePolicy.RESTORE_MS + 1).write);
        assertFalse(p.isPending());
        assertNull(p.observe(COMFORT, true, ModePolicy.RESTORE_MS + 5_000).write);
    }

    @Test public void aModePickedByTheDriverStopsTheRestore() {
        ModePolicy p = policy(false, ECO);
        p.onWake(0);
        assertEquals(Integer.valueOf(ECO), p.observe(COMFORT, true, 0).write);  // the car had reset it
        assertNull(p.observe(SPORT, true, 20_000).write);                        // the driver picks Sport
        assertFalse(p.isPending());
        assertNull(p.observe(SPORT, true, 40_000).write);
    }

    @Test public void learningOffNeverChangesTheSavedMode() {
        ModePolicy p = policy(false, ECO);
        for (long t = 100_000; t < 400_000; t += 2_000) {
            assertNull(p.observe(SPORT, true, t).learned);
        }
        assertEquals(Integer.valueOf(ECO), p.saved());
    }

    @Test public void learningOnTakesAStableChange() {
        ModePolicy p = policy(true, ECO);
        long t = 100_000;
        assertNull(p.observe(SPORT, true, t).learned);
        assertNull(p.observe(SPORT, true, t + 4_000).learned);
        assertEquals(Integer.valueOf(SPORT), p.observe(SPORT, true, t + ModePolicy.STABLE_MS).learned);
        assertEquals(Integer.valueOf(SPORT), p.saved());
    }

    @Test public void aFlickerIsNotLearned() {
        ModePolicy p = policy(true, ECO);
        long t = 100_000;
        p.observe(SPORT, true, t);
        p.observe(COMFORT, true, t + 3_000);
        p.observe(SPORT, true, t + 6_000);
        assertNull(p.observe(SPORT, true, t + 9_000).learned);   // only 3 s since the last flip
        assertEquals(Integer.valueOf(ECO), p.saved());
        assertEquals(Integer.valueOf(SPORT), p.observe(SPORT, true, t + 6_000 + ModePolicy.STABLE_MS).learned);
    }

    @Test public void nothingIsLearnedWhileTheCarIsGoingOff() {
        ModePolicy p = policy(true, ECO);
        p.onGoingOff();
        for (long t = 0; t < 100_000; t += 2_000) {
            assertNull(p.observe(COMFORT, true, t).learned);     // the car resets the mode on the way down
        }
        assertEquals(Integer.valueOf(ECO), p.saved());
        p.onWake(200_000);
        assertEquals(Integer.valueOf(ECO), p.observe(COMFORT, true, 200_000).write);
    }

    @Test public void nothingIsLearnedRightAfterAWake() {
        ModePolicy p = policy(true, ECO);
        p.onWake(0);
        for (long t = 0; t < ModePolicy.QUIET_MS; t += 2_000) {
            assertNull(p.observe(COMFORT, false, t).learned);
        }
        assertEquals(Integer.valueOf(ECO), p.saved());
    }

    @Test public void aResetThatCouldNotBeRestoredIsNotLearnedLater() {
        ModePolicy p = policy(true, ECO);
        p.onWake(0);
        long t = 0;
        // the driver leaves Park at once, so the restore never gets to write
        for (; t < ModePolicy.RESTORE_MS + 60_000; t += 2_000) {
            assertNull(p.observe(COMFORT, false, t).learned);
        }
        assertEquals(Integer.valueOf(ECO), p.saved());
        // a real choice afterwards is still learned
        assertNull(p.observe(SPORT, false, t).learned);
        assertEquals(Integer.valueOf(SPORT), p.observe(SPORT, false, t + ModePolicy.STABLE_MS).learned);
    }

    @Test public void anEarlyChoiceIsLearnedAfterTheQuietPeriod() {
        ModePolicy p = policy(true, ECO);
        p.onWake(0);
        assertEquals(Integer.valueOf(ECO), p.observe(COMFORT, true, 0).write);
        assertNull(p.observe(ECO, true, 2_000).write);
        assertNull(p.observe(SPORT, true, 20_000).learned);       // inside the quiet period
        long t = ModePolicy.QUIET_MS + 1_000;
        assertNull(p.observe(SPORT, true, t).learned);
        assertEquals(Integer.valueOf(SPORT), p.observe(SPORT, true, t + ModePolicy.STABLE_MS).learned);
    }

    @Test public void theFirstValueIsRememberedOnlyWhenLearning() {
        ModePolicy off = policy(false, null);
        assertNull(off.observe(COMFORT, true, 0).learned);
        assertNull(off.saved());
        ModePolicy on = policy(true, null);
        assertEquals(Integer.valueOf(COMFORT), on.observe(COMFORT, true, 0).learned);
    }

    @Test public void unknownValuesAreIgnored() {
        ModePolicy p = policy(true, ECO);
        p.onWake(0);
        assertNull(p.observe(null, true, 0).write);
        assertNull(p.observe(0, true, 2_000).write);
        assertNull(p.observe(99, true, 4_000).write);
        assertEquals(Integer.valueOf(ECO), p.saved());
    }

    @Test public void chooseSavesAndRestoresAtOnce() {
        ModePolicy p = policy(false, null);
        assertTrue(p.choose(SPORT, 0));
        assertEquals(Integer.valueOf(SPORT), p.observe(COMFORT, true, 0).write);
        assertFalse(p.choose(99, 0));
        assertEquals(Integer.valueOf(SPORT), p.saved());
    }

    @Test public void forgetClearsTheSavedMode() {
        ModePolicy p = policy(false, ECO);
        p.forget();
        assertNull(p.saved());
        p.onWake(0);
        assertNull(p.observe(COMFORT, true, 0).write);
    }
}