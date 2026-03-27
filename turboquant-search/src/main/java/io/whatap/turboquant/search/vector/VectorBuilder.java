package io.whatap.turboquant.search.vector;

import java.util.List;
import java.util.Map;

/**
 * Builds ServerStateVectors from metric snapshots.
 * Performs z-score normalization per metric.
 */
public class VectorBuilder {

    private final int timeSteps;

    public VectorBuilder() {
        this(ServerStateVector.DEFAULT_TIME_STEPS);
    }

    public VectorBuilder(int timeSteps) {
        this.timeSteps = timeSteps;
    }

    /**
     * Build a ServerStateVector from a list of metric snapshots (ordered by time).
     * Each snapshot is a Map from metric name to value.
     * Uses RAW values (no per-window normalization) so that absolute metric levels
     * are preserved for anomaly detection. The Mahalanobis distance in the detector
     * handles scale differences using per-dimension variance from the baseline.
     */
    public ServerStateVector build(int oid, long timestamp, List<Map<String, Double>> snapshots) {
        int numMetrics = ServerStateVector.NUM_METRICS;
        int dim = numMetrics * timeSteps;
        float[] vector = new float[dim];

        int maxT = Math.min(snapshots.size(), timeSteps);
        for (int t = 0; t < maxT; t++) {
            Map<String, Double> snap = snapshots.get(t);
            for (int m = 0; m < numMetrics; m++) {
                Double val = snap.get(ServerStateVector.METRIC_NAMES[m]);
                vector[m * timeSteps + t] = val != null ? val.floatValue() : 0f;
            }
        }

        return new ServerStateVector(oid, timestamp, vector, timeSteps);
    }

    public int getDimension() {
        return ServerStateVector.NUM_METRICS * timeSteps;
    }
}
