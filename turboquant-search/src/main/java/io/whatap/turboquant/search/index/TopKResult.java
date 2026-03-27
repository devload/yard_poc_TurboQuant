package io.whatap.turboquant.search.index;

public class TopKResult implements Comparable<TopKResult> {
    public final int oid;
    public final long timestamp;
    public final float score;

    public TopKResult(int oid, long timestamp, float score) {
        this.oid = oid;
        this.timestamp = timestamp;
        this.score = score;
    }

    public int compareTo(TopKResult other) {
        return Float.compare(other.score, this.score); // descending
    }

    public String toString() {
        return String.format("oid=%d, score=%.4f, time=%d", oid, score, timestamp);
    }
}
