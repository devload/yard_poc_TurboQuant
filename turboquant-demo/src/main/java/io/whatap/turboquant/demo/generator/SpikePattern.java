package io.whatap.turboquant.demo.generator;

import java.util.Random;

public class SpikePattern implements MetricPattern {
    private final double baseline;
    private final double spikeMin;
    private final double spikeMax;
    private final double spikeProbability;
    private final double noiseScale;
    private final Random rng;

    public SpikePattern(double baseline, double spikeMin, double spikeMax,
                        double spikeProbability, double noiseScale, long seed) {
        this.baseline = baseline;
        this.spikeMin = spikeMin;
        this.spikeMax = spikeMax;
        this.spikeProbability = spikeProbability;
        this.noiseScale = noiseScale;
        this.rng = new Random(seed);
    }

    public double next(long timestampMs) {
        double noise = rng.nextGaussian() * noiseScale;
        if (rng.nextDouble() < spikeProbability) {
            double spike = spikeMin + rng.nextDouble() * (spikeMax - spikeMin);
            return spike + noise;
        }
        return Math.max(0, baseline + noise);
    }
}
