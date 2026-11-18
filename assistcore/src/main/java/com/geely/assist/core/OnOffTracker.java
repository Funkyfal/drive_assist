package com.geely.assist.core;

/**
 * Turns readings of an on/off style vehicle state (ignition) into edges.
 * Plain Java, no Android, tested on a desktop JVM. Not thread-safe.
 *
 * The first reading is never an edge. Leaving the "on" value is TURNED_OFF,
 * returning to it is TURNED_ON; moves between two "not on" values are not edges.
 */
public final class OnOffTracker {
    public enum Edge { NONE, TURNED_OFF, TURNED_ON }

    private final int onValue;
    private Integer last;

    public OnOffTracker(int onValue) {
        this.onValue = onValue;
    }

    /** The previous reading, or null before the first one. */
    public Integer last() { return last; }

    /** One reading (null if unreadable, which is ignored). */
    public Edge update(Integer value) {
        if (value == null) return Edge.NONE;
        Integer prev = last;
        last = value;
        if (prev == null || prev.equals(value)) return Edge.NONE;
        boolean wasOn = prev == onValue;
        boolean isOn = value == onValue;
        if (wasOn && !isOn) return Edge.TURNED_OFF;
        if (!wasOn && isOn) return Edge.TURNED_ON;
        return Edge.NONE;
    }
}