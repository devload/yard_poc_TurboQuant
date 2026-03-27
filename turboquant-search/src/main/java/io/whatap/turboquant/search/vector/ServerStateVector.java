package io.whatap.turboquant.search.vector;

/**
 * Represents a server's state as a high-dimensional vector.
 * Combines multiple metrics across time steps.
 * Default: 8 metrics x 60 time steps = 480 dimensions.
 */
public class ServerStateVector {

    public static final String[] METRIC_NAMES = {
            "cpu", "mem", "heap_use", "tps", "resp_time", "gc_count", "service_error", "act_svc_count"
    };
    public static final int NUM_METRICS = METRIC_NAMES.length;
    public static final int DEFAULT_TIME_STEPS = 60;

    private final int oid;
    private final long timestamp;
    private final float[] vector;
    private final int timeSteps;

    public ServerStateVector(int oid, long timestamp, float[] vector, int timeSteps) {
        this.oid = oid;
        this.timestamp = timestamp;
        this.vector = vector;
        this.timeSteps = timeSteps;
    }

    public int getOid() { return oid; }
    public long getTimestamp() { return timestamp; }
    public float[] getVector() { return vector; }
    public int getDimension() { return vector.length; }
    public int getTimeSteps() { return timeSteps; }
}
