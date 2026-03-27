package io.whatap.turboquant.demo.agent;

import io.whatap.turboquant.demo.generator.*;

import java.util.LinkedHashMap;
import java.util.Map;

public class VirtualAgent {
    private final long pcode;
    private final int oid;
    private final String oname;
    private final Map<String, MetricPattern> patterns;

    public VirtualAgent(long pcode, int oid, long startMs) {
        this.pcode = pcode;
        this.oid = oid;
        this.oname = "demo-agent-" + oid;
        this.patterns = new LinkedHashMap<String, MetricPattern>();

        long seed = oid * 31L;
        patterns.put("cpu", new SineWavePattern(40, 20, 300000, oid * 0.5, 3.0, seed));
        patterns.put("mem", new GradualIncreasePattern(50, 0.001, 95, 2.0, startMs, seed + 1));
        patterns.put("heap_use", new SineWavePattern(60, 15, 600000, oid * 0.3, 2.0, seed + 2));
        patterns.put("heap_tot", new SineWavePattern(80, 5, 1200000, 0, 1.0, seed + 3));
        patterns.put("tps", new SpikePattern(100, 500, 1000, 0.05, 10, seed + 4));
        patterns.put("resp_time", new RandomWalkPattern(200, 30, 50, 5000, seed + 5));
        patterns.put("service_count", new SpikePattern(50, 100, 300, 0.1, 5, seed + 6));
        patterns.put("service_error", new SpikePattern(1, 5, 20, 0.02, 0.5, seed + 7));
        patterns.put("gc_count", new RandomWalkPattern(5, 2, 0, 50, seed + 8));
        patterns.put("gc_time", new RandomWalkPattern(30, 10, 0, 500, seed + 9));
        patterns.put("sql_count", new SpikePattern(30, 80, 200, 0.08, 3, seed + 10));
        patterns.put("httpc_count", new SpikePattern(20, 50, 150, 0.06, 2, seed + 11));
        patterns.put("act_svc_count", new RandomWalkPattern(3, 1, 0, 30, seed + 12));
    }

    public Map<String, Double> tick(long timestampMs) {
        Map<String, Double> snapshot = new LinkedHashMap<String, Double>();
        for (Map.Entry<String, MetricPattern> entry : patterns.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().next(timestampMs));
        }
        return snapshot;
    }

    public long getPcode() { return pcode; }
    public int getOid() { return oid; }
    public String getOname() { return oname; }
}
