package io.whatap.turboquant.anomaly.baseline;

import io.whatap.turboquant.search.vector.ServerStateVector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds baseline vectors for each server from historical data.
 * Computes mean and standard deviation vectors.
 */
public class BaselineBuilder {

    private final Map<Integer, BaselineEntry> baselines = new HashMap<Integer, BaselineEntry>();

    /**
     * Build baseline from a list of historical state vectors per server.
     */
    public void build(int oid, List<ServerStateVector> history) {
        if (history.isEmpty()) return;

        int dim = history.get(0).getDimension();
        float[] mean = new float[dim];
        float[] variance = new float[dim];
        int n = history.size();

        // Compute mean
        for (ServerStateVector sv : history) {
            float[] vec = sv.getVector();
            for (int i = 0; i < dim; i++) {
                mean[i] += vec[i] / n;
            }
        }

        // Compute variance
        for (ServerStateVector sv : history) {
            float[] vec = sv.getVector();
            for (int i = 0; i < dim; i++) {
                float diff = vec[i] - mean[i];
                variance[i] += diff * diff / n;
            }
        }

        float[] std = new float[dim];
        for (int i = 0; i < dim; i++) {
            std[i] = (float) Math.sqrt(variance[i]);
        }

        // Compute mean similarity using Mahalanobis-like distance
        double sumSim = 0;
        double sumSimSq = 0;
        for (ServerStateVector sv : history) {
            float sim = mahalanobisSimilarity(sv.getVector(), mean, std);
            sumSim += sim;
            sumSimSq += sim * sim;
        }
        double meanSim = sumSim / n;
        double stdSim = Math.sqrt(Math.max(0, sumSimSq / n - meanSim * meanSim));

        baselines.put(oid, new BaselineEntry(oid, mean, std, (float) meanSim, (float) stdSim, n));
    }

    public BaselineEntry getBaseline(int oid) {
        return baselines.get(oid);
    }

    public Map<Integer, BaselineEntry> getAllBaselines() {
        return baselines;
    }

    private static float mahalanobisSimilarity(float[] vec, float[] mean, float[] std) {
        double distSq = 0;
        int dim = vec.length;
        for (int i = 0; i < dim; i++) {
            double diff = vec[i] - mean[i];
            double var = std[i] * std[i];
            if (var < 1e-6) var = 1e-6;
            distSq += (diff * diff) / var;
        }
        double avgDist = Math.sqrt(distSq / dim);
        return (float) (1.0 / (1.0 + avgDist));
    }

    public static class BaselineEntry {
        public final int oid;
        public final float[] mean;
        public final float[] std;
        public final float meanSimilarity;
        public final float stdSimilarity;
        public final int sampleCount;

        public BaselineEntry(int oid, float[] mean, float[] std,
                             float meanSimilarity, float stdSimilarity, int sampleCount) {
            this.oid = oid;
            this.mean = mean;
            this.std = std;
            this.meanSimilarity = meanSimilarity;
            this.stdSimilarity = stdSimilarity;
            this.sampleCount = sampleCount;
        }
    }
}
