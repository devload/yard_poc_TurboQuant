package io.whatap.turboquant.demo.generator;

public interface MetricPattern {
    double next(long timestampMs);
}
