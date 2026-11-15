package com.geely.assist.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OnOffTrackerTest {
    private static final int ON = 4, OFF = 2, ACC = 3;

    @Test public void steadyOnGivesNoEdges() {
        OnOffTracker t = new OnOffTracker(ON);
        for (int i = 0; i < 5; i++) assertEquals(OnOffTracker.Edge.NONE, t.update(ON));
    }

    @Test public void aSwitchOffAndOnGivesTwoEdges() {
        OnOffTracker t = new OnOffTracker(ON);
        t.update(ON);
        assertEquals(OnOffTracker.Edge.TURNED_OFF, t.update(OFF));
        assertEquals(OnOffTracker.Edge.NONE, t.update(OFF));
        assertEquals(OnOffTracker.Edge.TURNED_ON, t.update(ON));
    }

    @Test public void theFirstReadingIsNeverAnEdge() {
        assertEquals(OnOffTracker.Edge.NONE, new OnOffTracker(ON).update(ON));
        assertEquals(OnOffTracker.Edge.NONE, new OnOffTracker(ON).update(OFF));
    }

    @Test public void startingOffAndThenOnIsTurnedOn() {
        OnOffTracker t = new OnOffTracker(ON);
        t.update(OFF);
        assertEquals(OnOffTracker.Edge.TURNED_ON, t.update(ON));
    }

    @Test public void unreadableValuesAreIgnored() {
        OnOffTracker t = new OnOffTracker(ON);
        t.update(ON);
        assertEquals(OnOffTracker.Edge.NONE, t.update(null));
        assertEquals(Integer.valueOf(ON), t.last());
        assertEquals(OnOffTracker.Edge.TURNED_OFF, t.update(OFF));
    }

    @Test public void movesBetweenNotOnValuesAreNotEdges() {
        OnOffTracker t = new OnOffTracker(ON);
        t.update(ON);
        assertEquals(OnOffTracker.Edge.TURNED_OFF, t.update(ACC));
        assertEquals(OnOffTracker.Edge.NONE, t.update(OFF));
        assertEquals(OnOffTracker.Edge.TURNED_ON, t.update(ON));
    }
}