package io.whatap.turboquant.demo.sender;

import io.whatap.lang.pack.CounterPack;
import io.whatap.lang.pack.TagCountPack;
import io.whatap.lang.value.FloatValue;
import io.whatap.lang.value.MapValue;
import io.whatap.lang.value.MetricValue;
import io.whatap.lang.value.TextValue;

import java.util.Map;

public class PackBuilder {

    public static CounterPack buildCounterPack(long pcode, int oid, long time, Map<String, Double> snapshot) {
        CounterPack pack = new CounterPack();
        pack.pcode = pcode;
        pack.oid = oid;
        pack.time = time;
        pack.duration = 5;

        pack.cpu = getFloat(snapshot, "cpu");
        pack.mem = getFloat(snapshot, "mem");
        pack.heap_use = (int) getDouble(snapshot, "heap_use");
        pack.heap_tot = (int) getDouble(snapshot, "heap_tot");
        pack.tps = getFloat(snapshot, "tps");
        pack.resp_time = (int) getDouble(snapshot, "resp_time");
        pack.service_count = (int) getDouble(snapshot, "service_count");
        pack.service_error = (int) getDouble(snapshot, "service_error");
        pack.gc_count = (int) getDouble(snapshot, "gc_count");
        pack.gc_time = (long) getDouble(snapshot, "gc_time");
        pack.sql_count = (int) getDouble(snapshot, "sql_count");
        pack.httpc_count = (int) getDouble(snapshot, "httpc_count");
        pack.act_svc_count = (int) getDouble(snapshot, "act_svc_count");

        return pack;
    }

    public static TagCountPack buildTagCountPack(long pcode, int oid, String oname,
                                                  long time, Map<String, Double> snapshot) {
        TagCountPack pack = new TagCountPack();
        pack.pcode = pcode;
        pack.oid = oid;
        pack.time = time;
        pack.category = "app_counter";

        MapValue tags = new MapValue();
        tags.put("oname", new TextValue(oname));
        pack.tags = tags;

        MapValue fields = new MapValue();
        for (Map.Entry<String, Double> entry : snapshot.entrySet()) {
            MetricValue mv = new MetricValue();
            mv.add(entry.getValue());
            fields.put(entry.getKey(), mv);
        }
        pack.fields = fields;

        pack.tagHash = computeTagHash(tags);
        return pack;
    }

    private static float getFloat(Map<String, Double> m, String key) {
        Double v = m.get(key);
        return v != null ? v.floatValue() : 0f;
    }

    private static double getDouble(Map<String, Double> m, String key) {
        Double v = m.get(key);
        return v != null ? v : 0.0;
    }

    private static long computeTagHash(MapValue tags) {
        long hash = 0;
        io.whatap.util.StringEnumer keys = tags.keys();
        if (keys != null) {
            while (keys.hasMoreElements()) {
                String key = keys.nextString();
                hash = hash * 31 + key.hashCode();
                Object v = tags.get(key);
                if (v != null) {
                    hash = hash * 31 + v.hashCode();
                }
            }
        }
        return hash;
    }
}
