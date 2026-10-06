package com.example.cs16minecraft.weapon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CS 1.6-style bomb beep pattern, pure maths. Beeps are scheduled BACKWARDS from the explosion so the last beep
 * always lands right before the blast, they start about 1 s apart and tighten steadily, the beep file steps
 * c4_beep1 -> c4_beep5 through the countdown, and the pitch starts deep and rises.
 */
public final class C4Schedule {
    private C4Schedule() {}

    /** Seconds between beeps when the fraction f (0..1) of the fuse is still left. */
    public static double interval(double f) {
        f = Math.max(0, Math.min(1, f));
        return 0.10 + 0.90 * Math.pow(f, 1.15);
    }

    /** Absolute beep times for a bomb planted at t0 that explodes at t0 + fuse. */
    public static double[] build(double t0, double fuse) {
        double end = t0 + fuse;
        List<Double> times = new ArrayList<>();
        double t = end - 0.18; // last beep just before the explosion
        while (t > t0 + 0.4) {
            times.add(t);
            t -= interval((end - t) / fuse);
        }
        Collections.reverse(times);
        double[] out = new double[times.size()];
        for (int i = 0; i < out.length; i++) out[i] = times.get(i);
        return out;
    }

    /** Which c4_beepN.wav (1..5) to use when fraction f of the fuse is left. */
    public static int wave(double f) {
        f = Math.max(0, Math.min(1, f));
        return 1 + Math.min(4, (int) ((1.0 - f) * 5));
    }

    /** Deep at the start of the countdown, higher near the end. */
    public static double pitch(double f) {
        f = Math.max(0, Math.min(1, f));
        return 0.82 + 0.30 * (1.0 - f);
    }
}
