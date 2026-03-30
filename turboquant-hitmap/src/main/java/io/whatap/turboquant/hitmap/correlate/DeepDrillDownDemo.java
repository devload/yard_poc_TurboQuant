package io.whatap.turboquant.hitmap.correlate;

import io.whatap.turboquant.hitmap.correlate.AnomalyEvent.Source;
import io.whatap.turboquant.hitmap.correlate.DeepDrillDown.*;
import io.whatap.turboquant.hitmap.correlate.RootCauseAnalyzer.IncidentGroup;

import java.util.*;

/**
 * Phase 12b: 심층 원인 추적 데모.
 * 3가지 시나리오에서 "왜?"를 끝까지 따라간다.
 */
public class DeepDrillDownDemo {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  Phase 12b: 장애 원인 심층 추적              ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        runScenario1_DbPool();
        runScenario2_ExternalApi();
        runScenario3_MemoryLeak();
    }

    // === 시나리오 1: DB 커넥션 풀 고갈 ===
    static void runScenario1_DbPool() {
        System.out.println("━━━ 시나리오 1: DB 커넥션 풀 고갈 — 심층 추적 ━━━");
        System.out.println();

        long base = System.currentTimeMillis();
        List<AnomalyEvent> events = new ArrayList<AnomalyEvent>();

        Map<String, Float> db = m("mem", 45f, "sql_time", 80f, "act_svc_count", 60f);
        events.add(new AnomalyEvent(Source.COUNTER, base, "db-2001", 0.42f, db));

        Map<String, Float> db2 = m("sql_time", 150f, "resp_time", 120f, "mem", 50f);
        events.add(new AnomalyEvent(Source.COUNTER, base + 5000, "db-2001", 0.31f, db2));

        Map<String, Float> web1 = m("resp_time", 85f, "sql_time", 130f, "gc_count", 40f);
        events.add(new AnomalyEvent(Source.COUNTER, base + 10000, "web-1001", 0.38f, web1));

        Map<String, Float> web2 = m("resp_time", 90f, "sql_time", 125f, "gc_count", 35f);
        events.add(new AnomalyEvent(Source.COUNTER, base + 15000, "web-1002", 0.35f, web2));

        events.add(new AnomalyEvent(Source.HITMAP, base + 20000, "pcode-12345", 0.006f, null));
        events.add(new AnomalyEvent(Source.QUANTILE, base + 20000, "/api/order", 0.45f, null));
        events.add(new AnomalyEvent(Source.QUANTILE, base + 20000, "/api/payment", 0.41f, null));

        // 서버 상세 정보 제공
        ServerDetailProvider serverDetails = new ServerDetailProvider() {
            public ServerDetail getDetail(String id, long time) {
                ServerDetail d = new ServerDetail();
                if (id.startsWith("db")) {
                    d.heapUsagePercent = 88;
                    d.gcTimePercent = 5;
                    d.threadPoolUsage = 0.6f;
                    d.diskIoWait = 45;
                    d.dbConnectionPool = new DbConnectionPool(50, 48, 12);
                } else {
                    d.heapUsagePercent = 72;
                    d.gcTimePercent = 15;
                    d.threadPoolUsage = 0.85f;
                    d.diskIoWait = 3;
                    d.dbConnectionPool = new DbConnectionPool(20, 19, 8);
                }
                return d;
            }
        };

        // 슬로우 쿼리 정보 제공
        QueryStatsProvider queryStats = new QueryStatsProvider() {
            public List<SlowQuery> getSlowQueries(String id, long time) {
                List<SlowQuery> list = new ArrayList<SlowQuery>();
                list.add(new SlowQuery("0xAB3F",
                        "SELECT * FROM orders WHERE created_at > ? AND status IN (?, ?) ORDER BY id DESC",
                        200, 8500, 342));
                list.add(new SlowQuery("0xCD12",
                        "SELECT COUNT(*) FROM order_items oi JOIN products p ON oi.product_id = p.id WHERE ...",
                        80, 3200, 128));
                return list;
            }
            public List<SlowUrl> getSlowUrls(String id, long time) {
                List<SlowUrl> list = new ArrayList<SlowUrl>();
                list.add(new SlowUrl("/api/order", 150, 4200, 89));
                list.add(new SlowUrl("/api/payment", 200, 3800, 45));
                list.add(new SlowUrl("/api/order/detail", 100, 2100, 67));
                return list;
            }
        };

        analyze(events, serverDetails, queryStats);
    }

    // === 시나리오 2: 외부 API 장애 ===
    static void runScenario2_ExternalApi() {
        System.out.println("━━━ 시나리오 2: 외부 결제 API 장애 — 심층 추적 ━━━");
        System.out.println();

        long base = System.currentTimeMillis();
        List<AnomalyEvent> events = new ArrayList<AnomalyEvent>();

        Map<String, Float> web1 = m("httpc_time", 200f, "resp_time", 95f, "act_svc_count", 70f);
        events.add(new AnomalyEvent(Source.COUNTER, base, "web-1001", 0.29f, web1));

        Map<String, Float> web2 = m("httpc_time", 180f, "resp_time", 88f, "act_svc_count", 65f);
        events.add(new AnomalyEvent(Source.COUNTER, base + 5000, "web-1002", 0.33f, web2));

        events.add(new AnomalyEvent(Source.HITMAP, base + 10000, "pcode-12345", 0.01f, null));
        events.add(new AnomalyEvent(Source.QUANTILE, base + 10000, "/api/payment", 0.30f, null));
        events.add(new AnomalyEvent(Source.QUANTILE, base + 10000, "/api/checkout", 0.28f, null));

        ServerDetailProvider serverDetails = new ServerDetailProvider() {
            public ServerDetail getDetail(String id, long time) {
                ServerDetail d = new ServerDetail();
                d.heapUsagePercent = 55;
                d.gcTimePercent = 3;
                d.threadPoolUsage = 0.92f;
                d.diskIoWait = 2;
                d.dbConnectionPool = new DbConnectionPool(20, 8, 0);
                return d;
            }
        };

        QueryStatsProvider queryStats = new QueryStatsProvider() {
            public List<SlowQuery> getSlowQueries(String id, long time) {
                return new ArrayList<SlowQuery>(); // SQL은 정상
            }
            public List<SlowUrl> getSlowUrls(String id, long time) {
                List<SlowUrl> list = new ArrayList<SlowUrl>();
                list.add(new SlowUrl("/api/payment", 180, 12000, 34));
                list.add(new SlowUrl("/api/checkout", 250, 15000, 22));
                return list;
            }
        };

        analyze(events, serverDetails, queryStats);
    }

    // === 시나리오 3: 메모리 릭 ===
    static void runScenario3_MemoryLeak() {
        System.out.println("━━━ 시나리오 3: 점진적 메모리 릭 — 심층 추적 ━━━");
        System.out.println();

        long base = System.currentTimeMillis();
        List<AnomalyEvent> events = new ArrayList<AnomalyEvent>();

        Map<String, Float> m1 = m("heap_use", 35f, "gc_count", 25f);
        events.add(new AnomalyEvent(Source.COUNTER, base, "web-1001", 0.65f, m1));

        Map<String, Float> m2 = m("heap_use", 60f, "gc_count", 70f, "gc_time", 85f, "resp_time", 40f);
        events.add(new AnomalyEvent(Source.COUNTER, base + 60000, "web-1001", 0.40f, m2));

        Map<String, Float> m3 = m("heap_use", 80f, "gc_count", 120f, "gc_time", 150f, "resp_time", 90f);
        events.add(new AnomalyEvent(Source.COUNTER, base + 120000, "web-1001", 0.15f, m3));

        events.add(new AnomalyEvent(Source.HITMAP, base + 120000, "pcode-12345", 0.02f, null));

        ServerDetailProvider serverDetails = new ServerDetailProvider() {
            public ServerDetail getDetail(String id, long time) {
                ServerDetail d = new ServerDetail();
                d.heapUsagePercent = 94;
                d.gcTimePercent = 38;
                d.threadPoolUsage = 0.45f;
                d.diskIoWait = 5;
                d.dbConnectionPool = new DbConnectionPool(20, 5, 0);
                return d;
            }
        };

        QueryStatsProvider queryStats = new QueryStatsProvider() {
            public List<SlowQuery> getSlowQueries(String id, long time) {
                return new ArrayList<SlowQuery>();
            }
            public List<SlowUrl> getSlowUrls(String id, long time) {
                List<SlowUrl> list = new ArrayList<SlowUrl>();
                list.add(new SlowUrl("/api/users", 50, 800, 120));
                list.add(new SlowUrl("/api/products", 30, 600, 95));
                return list;
            }
        };

        analyze(events, serverDetails, queryStats);
    }

    // === 분석 실행 + 출력 ===
    static void analyze(List<AnomalyEvent> events,
                         ServerDetailProvider serverDetails,
                         QueryStatsProvider queryStats) {

        RootCauseAnalyzer analyzer = new RootCauseAnalyzer(300000);
        List<IncidentGroup> groups = analyzer.correlate(events);

        for (IncidentGroup group : groups) {
            DrillDownReport report = DeepDrillDown.analyze(group, serverDetails, queryStats);
            printReport(report);
        }
    }

    static void printReport(DrillDownReport report) {
        System.out.println("  ┌─────────────────────────────────────────────────");
        System.out.println("  │");

        // 타임라인
        System.out.println("  │ [타임라인]");
        for (String line : report.group.timeline) {
            System.out.println("  │   " + line);
        }

        // 1단계: 증상
        System.out.println("  │");
        System.out.println("  │ [1단계: 증상]");
        System.out.println("  │   " + report.level1_symptom);

        // 2단계: 서버 상세
        if (report.level2_analysis != null) {
            System.out.println("  │");
            System.out.println("  │ [2단계: 서버 상세] " + report.level2_server);
            System.out.println("  │   " + report.level2_analysis);
            if (report.level2_detail != null) {
                ServerDetail d = report.level2_detail;
                System.out.println(String.format("  │   힙: %.0f%%, GC: %.0f%%, 스레드풀: %.0f%%, 디스크IO: %.0f%%",
                        d.heapUsagePercent, d.gcTimePercent, d.threadPoolUsage * 100, d.diskIoWait));
                if (d.dbConnectionPool != null) {
                    DbConnectionPool pool = d.dbConnectionPool;
                    System.out.println(String.format("  │   DB풀: %d/%d 사용 (대기: %d건, 사용률: %.0f%%)",
                            pool.active, pool.total, pool.waitCount, pool.activeRatio * 100));
                }
            }
        }

        // 3단계: 쿼리/URL
        if (report.level3_analysis != null) {
            System.out.println("  │");
            System.out.println("  │ [3단계: 슬로우 쿼리/URL]");
            System.out.println("  │   " + report.level3_analysis);

            if (report.level3_slowQueries != null) {
                for (SlowQuery q : report.level3_slowQueries) {
                    System.out.println(String.format("  │   SQL [%s]: %.0fms → %.0fms (%.0f배, %d건)",
                            q.sqlHash, q.normalAvgMs, q.currentAvgMs,
                            q.currentAvgMs / Math.max(1, q.normalAvgMs), q.count));
                    if (q.sqlText.length() > 60) {
                        System.out.println("  │       " + q.sqlText.substring(0, 60) + "...");
                    } else {
                        System.out.println("  │       " + q.sqlText);
                    }
                }
            }

            if (report.level3_slowUrls != null) {
                for (SlowUrl u : report.level3_slowUrls) {
                    System.out.println(String.format("  │   URL %-25s: %.0fms → %.0fms (%.0f배, %d건)",
                            u.url, u.normalAvgMs, u.currentAvgMs,
                            u.currentAvgMs / Math.max(1, u.normalAvgMs), u.count));
                }
            }
        }

        // 원인 체인
        System.out.println("  │");
        System.out.println("  │ [원인 체인]");
        for (int i = 0; i < report.causeChain.size(); i++) {
            CauseChainStep step = report.causeChain.get(i);
            String prefix = i == report.causeChain.size() - 1 ? "  │   ★ " : "  │   → ";
            System.out.println(String.format("%s[%s] %s", prefix, step.level, step.description));
            if (step.detail != null && !step.detail.isEmpty()) {
                System.out.println("  │     " + step.detail);
            }
        }

        System.out.println("  │");
        System.out.println("  └─────────────────────────────────────────────────");
        System.out.println();
    }

    // 헬퍼
    static Map<String, Float> m(Object... kv) {
        Map<String, Float> map = new LinkedHashMap<String, Float>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], ((Number) kv[i + 1]).floatValue());
        }
        return map;
    }
}
