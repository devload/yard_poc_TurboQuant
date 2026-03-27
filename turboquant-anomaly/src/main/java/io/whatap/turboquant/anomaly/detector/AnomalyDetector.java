package io.whatap.turboquant.anomaly.detector;

import io.whatap.turboquant.anomaly.baseline.BaselineBuilder;
import io.whatap.turboquant.search.vector.ServerStateVector;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects anomalies by comparing current server state vectors against baselines.
 * Uses cosine similarity with adaptive thresholds.
 */
public class AnomalyDetector {

    private final BaselineBuilder baselineBuilder;
    private final float sigmaMultiplier;
    private final float fixedThreshold;
    private final boolean useAdaptiveThreshold;

    /**
     * @param baselineBuilder the baseline data
     * @param sigmaMultiplier for adaptive threshold: mean - sigma * multiplier
     * @param fixedThreshold fallback fixed threshold (cosine similarity)
     */
    public AnomalyDetector(BaselineBuilder baselineBuilder, float sigmaMultiplier, float fixedThreshold) {
        this.baselineBuilder = baselineBuilder;
        this.sigmaMultiplier = sigmaMultiplier;
        this.fixedThreshold = fixedThreshold;
        this.useAdaptiveThreshold = true;
    }

    public AnomalyDetector(BaselineBuilder baselineBuilder, float fixedThreshold) {
        this.baselineBuilder = baselineBuilder;
        this.sigmaMultiplier = 0;
        this.fixedThreshold = fixedThreshold;
        this.useAdaptiveThreshold = false;
    }

    /**
     * Check a server state vector for anomalies.
     * Uses normalized Euclidean distance converted to a [0,1] similarity score.
     * Score = 1 / (1 + normalized_distance). Higher = more similar.
     */
    public AnomalyResult detect(ServerStateVector current) {
        BaselineBuilder.BaselineEntry baseline = baselineBuilder.getBaseline(current.getOid());
        if (baseline == null) {
            return new AnomalyResult(current.getOid(), current.getTimestamp(),
                    0f, 0f, false, "No baseline available");
        }

        // Mahalanobis-like distance: sum of (x_i - mean_i)^2 / max(std_i^2, epsilon)
        float[] vec = current.getVector();
        double distSq = 0;
        int dim = vec.length;
        for (int i = 0; i < dim; i++) {
            double diff = vec[i] - baseline.mean[i];
            double var = baseline.std[i] * baseline.std[i];
            if (var < 1e-6) var = 1e-6;
            distSq += (diff * diff) / var;
        }
        double avgDist = Math.sqrt(distSq / dim); // normalized distance
        float similarity = (float) (1.0 / (1.0 + avgDist)); // convert to [0,1] similarity

        float threshold;
        if (useAdaptiveThreshold && baseline.stdSimilarity > 0) {
            threshold = baseline.meanSimilarity - sigmaMultiplier * baseline.stdSimilarity;
            threshold = Math.max(threshold, 0.1f);
        } else {
            threshold = fixedThreshold;
        }

        boolean isAnomaly = similarity < threshold;
        String detail = String.format("dist=%.2f, sim=%.4f, threshold=%.4f, baseline_mean_sim=%.4f",
                avgDist, similarity, threshold, baseline.meanSimilarity);

        return new AnomalyResult(current.getOid(), current.getTimestamp(),
                similarity, threshold, isAnomaly, detail);
    }

    /**
     * Batch detect for multiple servers.
     */
    public List<AnomalyResult> detectBatch(List<ServerStateVector> currentStates) {
        List<AnomalyResult> results = new ArrayList<AnomalyResult>();
        for (ServerStateVector sv : currentStates) {
            results.add(detect(sv));
        }
        return results;
    }

    private static float cosineSimilarity(float[] a, float[] b) {
        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        normA = (float) Math.sqrt(normA);
        normB = (float) Math.sqrt(normB);
        if (normA < 1e-10f || normB < 1e-10f) return 0f;
        return dot / (normA * normB);
    }
}
