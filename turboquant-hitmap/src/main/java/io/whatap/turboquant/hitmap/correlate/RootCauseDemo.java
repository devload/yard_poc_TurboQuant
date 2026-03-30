package io.whatap.turboquant.hitmap.correlate;

import io.whatap.turboquant.hitmap.correlate.AnomalyEvent.Source;
import io.whatap.turboquant.hitmap.correlate.RootCauseAnalyzer.IncidentGroup;

import java.util.*;

/**
 * Phase 12: 장애 근본 원인 분석 데모.
 *
 * 시나리오: DB 커넥션 풀 고갈 → SQL 느려짐 → 웹서버 응답시간 증가 → 히트맵 이상
 *
 * 시간 흐름:
 *   10:00:00  DB서버 메모리 증가 시작
 *   10:00:05  DB서버 SQL 처리시간 증가
 *   10:00:10  웹서버1 응답시간 증가 + GC 증가
 *   10:00:15  웹서버2 응답시간 증가 + GC 증가
 *   10:00:20  히트맵 분포 변화 감지
 *   10:00:20  /api/order, /api/payment 느려짐
 */
public class RootCauseDemo {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  Phase 12: 장애 근본 원인 분석 데모          ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        // === 시나리오 1: DB 커넥션 풀 고갈 ===
        System.out.println("━━━ 시나리오 1: DB 커넥션 풀 고갈 ━━━");
        System.out.println();
        runScenario1();

        // === 시나리오 2: 외부 API 장애 전파 ===
        System.out.println();
        System.out.println("━━━ 시나리오 2: 외부 API 장애 전파 ━━━");
        System.out.println();
        runScenario2();

        // === 시나리오 3: 메모리 릭 ===
        System.out.println();
        System.out.println("━━━ 시나리오 3: 점진적 메모리 릭 ━━━");
        System.out.println();
        runScenario3();
    }

    /**
     * 시나리오 1: DB 커넥션 풀 고갈
     * DB서버 → 웹서버 → 히트맵 → URL 순으로 전파
     */
    static void runScenario1() {
        long base = System.currentTimeMillis();
        List<AnomalyEvent> events = new ArrayList<AnomalyEvent>();

        // t+0: DB서버 메모리 이상
        Map<String, Float> dbMetrics = new LinkedHashMap<String, Float>();
        dbMetrics.put("mem", 45f);
        dbMetrics.put("sql_time", 80f);
        dbMetrics.put("act_svc_count", 60f);
        events.add(new AnomalyEvent(Source.COUNTER, base, "db-2001", 0.42f, dbMetrics));

        // t+5s: DB서버 SQL 시간 급등
        Map<String, Float> dbMetrics2 = new LinkedHashMap<String, Float>();
        dbMetrics2.put("sql_time", 150f);
        dbMetrics2.put("resp_time", 120f);
        dbMetrics2.put("mem", 50f);
        events.add(new AnomalyEvent(Source.COUNTER, base + 5000, "db-2001", 0.31f, dbMetrics2));

        // t+10s: 웹서버1 이상
        Map<String, Float> web1 = new LinkedHashMap<String, Float>();
        web1.put("resp_time", 85f);
        web1.put("sql_time", 130f);
        web1.put("gc_count", 40f);
        web1.put("act_svc_count", 55f);
        events.add(new AnomalyEvent(Source.COUNTER, base + 10000, "web-1001", 0.38f, web1));

        // t+15s: 웹서버2 이상
        Map<String, Float> web2 = new LinkedHashMap<String, Float>();
        web2.put("resp_time", 90f);
        web2.put("sql_time", 125f);
        web2.put("gc_count", 35f);
        web2.put("act_svc_count", 50f);
        events.add(new AnomalyEvent(Source.COUNTER, base + 15000, "web-1002", 0.35f, web2));

        // t+20s: 히트맵 이상
        events.add(new AnomalyEvent(Source.HITMAP, base + 20000, "pcode-12345", 0.006f, null));

        // t+20s: URL 느려짐
        events.add(new AnomalyEvent(Source.QUANTILE, base + 20000, "/api/order", 0.45f, null));
        events.add(new AnomalyEvent(Source.QUANTILE, base + 20000, "/api/payment", 0.41f, null));

        analyzeAndPrint(events);
    }

    /**
     * 시나리오 2: 외부 API 장애 전파
     * 외부 결제 API → 웹서버 httpc 시간 → 응답시간 → 히트맵
     */
    static void runScenario2() {
        long base = System.currentTimeMillis();
        List<AnomalyEvent> events = new ArrayList<AnomalyEvent>();

        // t+0: 웹서버1 외부 호출 시간 급등
        Map<String, Float> web1 = new LinkedHashMap<String, Float>();
        web1.put("httpc_time", 200f);
        web1.put("resp_time", 95f);
        web1.put("act_svc_count", 70f);
        events.add(new AnomalyEvent(Source.COUNTER, base, "web-1001", 0.29f, web1));

        // t+5s: 웹서버2도 같은 증상
        Map<String, Float> web2 = new LinkedHashMap<String, Float>();
        web2.put("httpc_time", 180f);
        web2.put("resp_time", 88f);
        web2.put("act_svc_count", 65f);
        events.add(new AnomalyEvent(Source.COUNTER, base + 5000, "web-1002", 0.33f, web2));

        // t+10s: 히트맵
        events.add(new AnomalyEvent(Source.HITMAP, base + 10000, "pcode-12345", 0.01f, null));

        // t+10s: 결제 관련 URL만 느려짐
        events.add(new AnomalyEvent(Source.QUANTILE, base + 10000, "/api/payment", 0.30f, null));
        events.add(new AnomalyEvent(Source.QUANTILE, base + 10000, "/api/checkout", 0.28f, null));

        analyzeAndPrint(events);
    }

    /**
     * 시나리오 3: 점진적 메모리 릭
     * 힙 사용량 → GC 빈발 → 응답시간 → 히트맵
     */
    static void runScenario3() {
        long base = System.currentTimeMillis();
        List<AnomalyEvent> events = new ArrayList<AnomalyEvent>();

        // t+0: 웹서버1 힙 증가
        Map<String, Float> web1a = new LinkedHashMap<String, Float>();
        web1a.put("heap_use", 35f);
        web1a.put("gc_count", 25f);
        events.add(new AnomalyEvent(Source.COUNTER, base, "web-1001", 0.65f, web1a));

        // t+60s: 더 심해짐
        Map<String, Float> web1b = new LinkedHashMap<String, Float>();
        web1b.put("heap_use", 60f);
        web1b.put("gc_count", 70f);
        web1b.put("gc_time", 85f);
        web1b.put("resp_time", 40f);
        events.add(new AnomalyEvent(Source.COUNTER, base + 60000, "web-1001", 0.40f, web1b));

        // t+120s: GC로 인한 응답시간 급등
        Map<String, Float> web1c = new LinkedHashMap<String, Float>();
        web1c.put("heap_use", 80f);
        web1c.put("gc_count", 120f);
        web1c.put("gc_time", 150f);
        web1c.put("resp_time", 90f);
        events.add(new AnomalyEvent(Source.COUNTER, base + 120000, "web-1001", 0.15f, web1c));

        // t+120s: 히트맵
        events.add(new AnomalyEvent(Source.HITMAP, base + 120000, "pcode-12345", 0.02f, null));

        analyzeAndPrint(events);
    }

    static void analyzeAndPrint(List<AnomalyEvent> events) {
        RootCauseAnalyzer analyzer = new RootCauseAnalyzer(300000); // 5분 윈도우
        List<IncidentGroup> groups = analyzer.correlate(events);

        for (int g = 0; g < groups.size(); g++) {
            IncidentGroup group = groups.get(g);

            System.out.println("  ┌─ 장애 그룹 #" + (g + 1) + " ─────────────────────────────────");
            System.out.println("  │");

            // 타임라인
            System.out.println("  │ [타임라인]");
            for (String line : group.timeline) {
                System.out.println("  │   " + line);
            }

            // 영향 범위
            System.out.println("  │");
            System.out.println("  │ [영향 범위]");
            if (!group.affectedServers.isEmpty()) {
                System.out.println("  │   서버: " + group.affectedServers);
            }
            if (!group.affectedUrls.isEmpty()) {
                System.out.println("  │   URL: " + group.affectedUrls);
            }
            System.out.println("  │   총 이벤트: " + group.events.size() + "개");

            // 공통 메트릭
            if (!group.commonMetrics.isEmpty()) {
                System.out.println("  │");
                System.out.println("  │ [공통 이상 메트릭]");
                for (Map.Entry<String, Float> m : group.commonMetrics.entrySet()) {
                    System.out.println(String.format("  │   %-15s 평균 +%.0f%% 벗어남", m.getKey(), m.getValue()));
                }
            }

            // 최초 이벤트
            System.out.println("  │");
            System.out.println("  │ [최초 감지]");
            System.out.println("  │   " + group.firstEvent.timeString() + " " +
                    group.firstEvent.source + " " + group.firstEvent.id);

            // 추정 원인
            System.out.println("  │");
            System.out.println("  │ [추정 원인]");
            System.out.println("  │   → " + group.suspectedCause);

            System.out.println("  │");
            System.out.println("  └──────────────────────────────────────────");
            System.out.println();
        }
    }
}
