package io.whatap.turboquant.hitmap.correlate;

import java.util.*;

/**
 * 개별 이상 이벤트. 어떤 인덱스에서, 언제, 무엇이 이상한지.
 */
public class AnomalyEvent {
    public enum Source { HITMAP, COUNTER, QUANTILE }

    public final Source source;
    public final long time;
    public final String id;       // oid, url, pcode 등
    public final float similarity; // 베이스라인 대비 유사도
    public final Map<String, Float> details; // 어떤 메트릭이 벗어났는지

    public AnomalyEvent(Source source, long time, String id, float similarity, Map<String, Float> details) {
        this.source = source;
        this.time = time;
        this.id = id;
        this.similarity = similarity;
        this.details = details != null ? details : new LinkedHashMap<String, Float>();
    }

    public String timeString() {
        return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(time));
    }
}
