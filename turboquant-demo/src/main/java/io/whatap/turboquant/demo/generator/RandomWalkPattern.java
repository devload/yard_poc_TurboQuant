package io.whatap.turboquant.demo.generator;

import java.util.Random;

public class RandomWalkPattern implements MetricPattern {
    private double current;
    private final double step;
    private final double min;
    private final double max;
    private final Random rng;

    public RandomWalkPattern(double initial, double step, double min, double max, long seed) {
        this.current = initial;
        this.step = step;
        this.min = min;
        this.max = max;
        this.rng = new Random(seed);
    }

    public double next(long timestampMs) {
        current += rng.nextGaussian() * step;
        current = Math.max(min, Math.min(max, current));
        return current;
    }
}
