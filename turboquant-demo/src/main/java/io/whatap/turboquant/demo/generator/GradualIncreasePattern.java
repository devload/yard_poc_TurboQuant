package io.whatap.turboquant.demo.generator;

import java.util.Random;

public class GradualIncreasePattern implements MetricPattern {
    private final double base;
    private final double ratePerSec;
    private final double ceiling;
    private final double noiseScale;
    private final long startMs;
    private final Random rng;

    public GradualIncreasePattern(double base, double ratePerSec, double ceiling, double noiseScale, long startMs, long seed) {
        this.base = base;
        this.ratePerSec = ratePerSec;
        this.ceiling = ceiling;
        this.noiseScale = noiseScale;
        this.startMs = startMs;
        this.rng = new Random(seed);
    }

    public double next(long timestampMs) {
        double elapsedSec = (timestampMs - startMs) / 1000.0;
        double noise = rng.nextGaussian() * noiseScale;
        return Math.min(ceiling, base + ratePerSec * elapsedSec + noise);
    }
}
