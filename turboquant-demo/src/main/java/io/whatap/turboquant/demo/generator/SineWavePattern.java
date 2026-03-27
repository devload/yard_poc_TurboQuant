package io.whatap.turboquant.demo.generator;

import java.util.Random;

public class SineWavePattern implements MetricPattern {
    private final double base;
    private final double amplitude;
    private final long periodMs;
    private final double phase;
    private final double noiseScale;
    private final Random rng;

    public SineWavePattern(double base, double amplitude, long periodMs, double phase, double noiseScale, long seed) {
        this.base = base;
        this.amplitude = amplitude;
        this.periodMs = periodMs;
        this.phase = phase;
        this.noiseScale = noiseScale;
        this.rng = new Random(seed);
    }

    public double next(long timestampMs) {
        double t = (2.0 * Math.PI * timestampMs) / periodMs + phase;
        double noise = rng.nextGaussian() * noiseScale;
        return Math.max(0, Math.min(100, base + amplitude * Math.sin(t) + noise));
    }
}
