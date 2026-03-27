package io.whatap.turboquant.anomaly.alert;

import io.whatap.turboquant.anomaly.detector.AnomalyResult;

/**
 * Builds alert messages from anomaly detection results.
 * In a full integration, this would create WhaTap EventPack objects.
 * For now, generates structured alert messages.
 */
public class EventPackBuilder {

    private final long pcode;

    public EventPackBuilder(long pcode) {
        this.pcode = pcode;
    }

    /**
     * Build an alert message from an anomaly result.
     */
    public AlertMessage build(AnomalyResult result) {
        String title = String.format("[TurboQuant] Anomaly detected on agent %d", result.oid);
        String message = String.format(
                "Cosine similarity dropped to %.4f (threshold: %.4f).\n" +
                "This indicates the server's metric pattern has significantly deviated from its baseline.\n" +
                "Details: %s",
                result.similarity, result.threshold, result.detail);

        String level = result.similarity < result.threshold * 0.7f ? "CRITICAL" : "WARNING";

        return new AlertMessage(pcode, result.oid, result.timestamp, title, message, level);
    }

    public static class AlertMessage {
        public final long pcode;
        public final int oid;
        public final long timestamp;
        public final String title;
        public final String message;
        public final String level;

        public AlertMessage(long pcode, int oid, long timestamp, String title, String message, String level) {
            this.pcode = pcode;
            this.oid = oid;
            this.timestamp = timestamp;
            this.title = title;
            this.message = message;
            this.level = level;
        }

        public String toString() {
            return String.format("[%s] %s\n  OID: %d, PCode: %d\n  %s", level, title, oid, pcode, message);
        }
    }
}
