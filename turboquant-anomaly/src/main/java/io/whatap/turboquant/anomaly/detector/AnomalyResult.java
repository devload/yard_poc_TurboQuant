package io.whatap.turboquant.anomaly.detector;

public class AnomalyResult {
    public final int oid;
    public final long timestamp;
    public final float similarity;
    public final float threshold;
    public final boolean isAnomaly;
    public final String detail;

    public AnomalyResult(int oid, long timestamp, float similarity, float threshold,
                         boolean isAnomaly, String detail) {
        this.oid = oid;
        this.timestamp = timestamp;
        this.similarity = similarity;
        this.threshold = threshold;
        this.isAnomaly = isAnomaly;
        this.detail = detail;
    }

    public String toString() {
        return String.format("[%s] oid=%d sim=%.4f threshold=%.4f %s",
                isAnomaly ? "ANOMALY" : "NORMAL", oid, similarity, threshold, detail);
    }
}
