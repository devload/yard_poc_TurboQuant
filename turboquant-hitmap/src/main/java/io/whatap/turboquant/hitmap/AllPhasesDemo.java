package io.whatap.turboquant.hitmap;

import io.whatap.turboquant.hitmap.counter.GroupRotationIndex;
import io.whatap.turboquant.hitmap.quantile.QuantileIndex;

import java.util.*;

/**
 * Phase 8~10 통합 데모.
 * 실제 모니터링 시나리오를 시뮬레이션하여 3가지 TurboQuant 적용을 검증.
 */
public class AllPhasesDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  TurboQuant 적용 가능 데이터 실증 테스트     ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        runPhase8_Hitmap();
        runPhase9_CounterGroupRotation();
        runPhase10_QuantileSearch();

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║              최종 결과 요약                   ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();
        System.out.println(String.format("%-25s %8s %10s %10s %12s",
                "적용 대상", "차원", "압축률", "검색(ms)", "이상탐지"));
        System.out.println(new String(new char[70]).replace('\0', '-'));
        System.out.println(String.format("%-25s %8d %9.1fx %9.2fms %12s",
                "히트맵 (HitMapPack)", 120, 7.5, 1.7, "정확"));
        System.out.println(String.format("%-25s %8s %9s %9s %12s",
                "서버카운터 (CounterPack)", "7+8+7", "아래참조", "아래참조", "아래참조"));
        System.out.println(String.format("%-25s %8d %9s %9s %12s",
                "퍼센타일 (KLL Quantile)", 14, "아래참조", "아래참조", "아래참조"));
    }

    // ===== Phase 8: 히트맵 =====
    static void runPhase8_Hitmap() throws Exception {
        System.out.println("━━━ Phase 8: 히트맵 패턴 검색 (120차원, 동일 스케일) ━━━");
        System.out.println();

        // 이미 HitmapSearchMain에서 검증됨 — 결과 요약만
        System.out.println("  결과: HitmapSearchMain 참조");
        System.out.println("  - 120차원 int[] → TurboQuant 4-bit → 64B/벡터 (7.5x 압축)");
        System.out.println("  - 이상 히트맵 유사도 0.006 vs 정상 0.97 → 정확히 탐지");
        System.out.println();
    }

    // ===== Phase 9: CounterPack 그룹 회전 =====
    static void runPhase9_CounterGroupRotation() {
        System.out.println("━━━ Phase 9: 서버 카운터 그룹 회전 검색 (7+8+7=22차원) ━━━");
        System.out.println();

        Random rng = new Random(42);
        GroupRotationIndex index = new GroupRotationIndex(8); // 8-bit

        // 서버 3종류 시뮬레이션
        // 웹서버 (CPU높음, TPS높음), DB서버 (메모리높음, SQL많음), 배치서버 (CPU간헐 스파이크)
        String[][] serverTypes = {
            {"web", "CPU 높음, TPS 높음"},
            {"db", "메모리 높음, SQL 많음"},
            {"batch", "CPU 간헐 스파이크"}
        };

        int[][] oids = {{1001,1002,1003,1004,1005}, {2001,2002,2003}, {3001,3002}};
        long baseTime = System.currentTimeMillis();

        // 10분간 5초 간격 데이터 생성
        System.out.println("  데이터 생성: 10 서버 × 120 타임스텝 (10분)");
        for (int t = 0; t < 120; t++) {
            long time = baseTime + t * 5000;
            for (int g = 0; g < 3; g++) {
                for (int oid : oids[g]) {
                    Map<String, Float> m = generateCounterMetrics(rng, g, t);
                    index.add(oid, time, m);
                }
            }
        }
        System.out.println(String.format("  인덱스: %d개 벡터, %dB/벡터",
                index.size(), index.compressedBytesPerEntry()));

        // 정확도 측정
        System.out.println();
        System.out.println("  [정확도] 그룹 회전 8-bit 복원 오차:");
        Map<String, Float> sampleMetrics = generateCounterMetrics(rng, 0, 50);
        Map<String, Float> errors = index.measureAccuracy(sampleMetrics);
        System.out.println(String.format("    %-15s %-12s %-10s %-10s", "메트릭", "원본", "오차(%)", "그룹"));
        System.out.println("    " + new String(new char[50]).replace('\0', '-'));
        for (Map.Entry<String, Float> e : errors.entrySet()) {
            Float orig = sampleMetrics.get(e.getKey());
            String group = isGroupA(e.getKey()) ? "퍼센트" : isGroupB(e.getKey()) ? "카운트" : "시간/크기";
            System.out.println(String.format("    %-15s %10.1f %9.2f%% %10s",
                    e.getKey(), orig != null ? orig : 0, e.getValue(), group));
        }

        // 유사 서버 검색
        System.out.println();
        System.out.println("  [검색] 웹서버 1001과 비슷한 서버 찾기:");
        Map<String, Float> queryMetrics = generateCounterMetrics(new Random(42), 0, 60);
        long searchStart = System.nanoTime();
        List<GroupRotationIndex.SearchResult> results = index.search(queryMetrics, 1001, 5);
        long searchTime = System.nanoTime() - searchStart;

        System.out.println(String.format("    검색 시간: %.2fms", searchTime / 1e6));
        System.out.println(String.format("    %-6s %-8s %10s %8s %8s %8s",
                "순위", "서버", "유사도", "퍼센트G", "카운트G", "시간G"));
        System.out.println("    " + new String(new char[55]).replace('\0', '-'));
        int rank = 1;
        for (GroupRotationIndex.SearchResult r : results) {
            String type = r.oid >= 3000 ? "batch" : r.oid >= 2000 ? "db" : "web";
            System.out.println(String.format("    #%-5d %-4d(%s) %9.4f %8.4f %8.4f %8.4f",
                    rank, r.oid, type, r.similarity, r.simA, r.simB, r.simC));
            rank++;
        }

        // 클러스터 정확도
        int correct = 0;
        for (GroupRotationIndex.SearchResult r : results) {
            if (r.oid >= 1000 && r.oid < 2000) correct++; // 같은 웹서버 그룹
        }
        System.out.println(String.format("    클러스터 정확도: %d/%d (Top-5 중 같은 유형)", correct, results.size()));
        System.out.println();
    }

    // ===== Phase 10: KLL Quantile =====
    static void runPhase10_QuantileSearch() {
        System.out.println("━━━ Phase 10: 퍼센타일 분포 검색 (14차원, 동일 스케일) ━━━");
        System.out.println();

        Random rng = new Random(42);
        QuantileIndex index = new QuantileIndex(4, 14);

        // URL 4종류: 빠른API, 보통API, 느린API, DB의존API
        String[][] urls = {
            {"/api/health", "/api/ping", "/api/status"},           // 빠른 (P50: 5ms)
            {"/api/users", "/api/orders", "/api/products"},        // 보통 (P50: 100ms)
            {"/api/report", "/api/export", "/api/analytics"},      // 느린 (P50: 2000ms)
            {"/api/search", "/api/recommend", "/api/aggregate"}    // DB의존 (P50: 500ms, P99 높음)
        };
        float[][] basePercentiles = {
            {1,2,3,3,5,7,8,9,10,12,15,25,50,100},           // 빠른
            {20,30,50,70,100,120,150,170,200,250,350,500,1000,3000}, // 보통
            {200,500,800,1200,2000,2500,3000,3200,3500,4000,5000,7000,12000,25000}, // 느린
            {50,80,120,200,500,600,700,750,800,900,1200,3000,10000,30000}  // DB의존 (long tail)
        };

        System.out.println("  데이터 생성: 12 URL × 24 타임스텝 (2시간)");
        long baseTime = System.currentTimeMillis();
        for (int t = 0; t < 24; t++) {
            long time = baseTime + t * 300000;
            for (int g = 0; g < 4; g++) {
                for (String url : urls[g]) {
                    float[] pcts = new float[14];
                    for (int i = 0; i < 14; i++) {
                        pcts[i] = basePercentiles[g][i] * (0.8f + rng.nextFloat() * 0.4f); // ±20% 변동
                    }
                    index.add(url, time, pcts);
                }
            }
        }
        System.out.println(String.format("  인덱스: %d개 벡터, %dB/벡터",
                index.size(), index.compressedBytesPerEntry()));

        // 유사 URL 검색
        System.out.println();
        System.out.println("  [검색] /api/users와 응답 분포가 비슷한 URL:");
        float[] queryPcts = new float[14];
        for (int i = 0; i < 14; i++) queryPcts[i] = basePercentiles[1][i] * (0.9f + rng.nextFloat() * 0.2f);

        long searchStart = System.nanoTime();
        List<QuantileIndex.SearchResult> results = index.search(queryPcts, "/api/users", 5);
        long searchTime = System.nanoTime() - searchStart;

        System.out.println(String.format("    검색 시간: %.2fms", searchTime / 1e6));
        System.out.println(String.format("    %-25s %10s %10s %10s", "URL", "유사도", "P50(ms)", "P99(ms)"));
        System.out.println("    " + new String(new char[58]).replace('\0', '-'));
        for (QuantileIndex.SearchResult r : results) {
            System.out.println(String.format("    %-25s %9.4f %9.0f %9.0f",
                    r.url, r.similarity, r.percentiles[4], r.percentiles[12]));
        }

        // 분포 변화 감지
        System.out.println();
        System.out.println("  [이상탐지] /api/search 응답 분포 변화 감지:");
        float[] normalPcts = basePercentiles[3].clone();
        float[] degradedPcts = new float[14];
        for (int i = 0; i < 14; i++) degradedPcts[i] = normalPcts[i] * 3; // 3배 느려짐

        float normalSim = index.compareDistributions(normalPcts, normalPcts);
        float degradedSim = index.compareDistributions(normalPcts, degradedPcts);

        System.out.println(String.format("    정상→정상:   유사도 %.4f", normalSim));
        System.out.println(String.format("    정상→3배느림: 유사도 %.4f %s",
                degradedSim, degradedSim < 0.95 ? "⚠ 분포 변화 감지!" : "정상"));
        System.out.println();
    }

    // === 데이터 생성 헬퍼 ===
    static Map<String, Float> generateCounterMetrics(Random rng, int serverType, int tick) {
        Map<String, Float> m = new LinkedHashMap<String, Float>();
        switch (serverType) {
            case 0: // 웹서버
                m.put("cpu", 40 + (float)rng.nextGaussian() * 15 + 10*(float)Math.sin(tick/10.0));
                m.put("cpu_sys", 10 + (float)rng.nextGaussian() * 3);
                m.put("cpu_usr", 30 + (float)rng.nextGaussian() * 10);
                m.put("cpu_proc", 15 + (float)rng.nextGaussian() * 5);
                m.put("mem", 65 + (float)rng.nextGaussian() * 5);
                m.put("swap", 5 + (float)rng.nextGaussian() * 2);
                m.put("disk", 45 + (float)rng.nextGaussian() * 5);
                m.put("tps", 300 + (float)rng.nextGaussian() * 50);
                m.put("service_count", 150 + (float)rng.nextGaussian() * 30);
                m.put("service_error", 2 + (float)Math.abs(rng.nextGaussian()));
                m.put("gc_count", 5 + (float)rng.nextGaussian() * 2);
                m.put("sql_count", 80 + (float)rng.nextGaussian() * 15);
                m.put("httpc_count", 40 + (float)rng.nextGaussian() * 10);
                m.put("act_svc_count", 3 + (float)Math.abs(rng.nextGaussian()));
                m.put("thread_count", 100 + (float)rng.nextGaussian() * 10);
                m.put("resp_time", 200 + (float)rng.nextGaussian() * 50);
                m.put("heap_use", 2000 + (float)rng.nextGaussian() * 300);
                m.put("heap_tot", 4096f);
                m.put("gc_time", 30 + (float)rng.nextGaussian() * 10);
                m.put("sql_time", 50 + (float)rng.nextGaussian() * 20);
                m.put("httpc_time", 100 + (float)rng.nextGaussian() * 30);
                m.put("service_time", 500 + (float)rng.nextGaussian() * 100);
                break;
            case 1: // DB서버
                m.put("cpu", 25 + (float)rng.nextGaussian() * 10);
                m.put("cpu_sys", 5 + (float)rng.nextGaussian() * 2);
                m.put("cpu_usr", 20 + (float)rng.nextGaussian() * 8);
                m.put("cpu_proc", 10 + (float)rng.nextGaussian() * 3);
                m.put("mem", 85 + (float)rng.nextGaussian() * 3);
                m.put("swap", 2 + (float)Math.abs(rng.nextGaussian()));
                m.put("disk", 60 + (float)rng.nextGaussian() * 8);
                m.put("tps", 50 + (float)rng.nextGaussian() * 15);
                m.put("service_count", 30 + (float)rng.nextGaussian() * 10);
                m.put("service_error", 1 + (float)Math.abs(rng.nextGaussian()) * 0.5f);
                m.put("gc_count", 3 + (float)rng.nextGaussian());
                m.put("sql_count", 200 + (float)rng.nextGaussian() * 40);
                m.put("httpc_count", 5 + (float)rng.nextGaussian() * 2);
                m.put("act_svc_count", 8 + (float)Math.abs(rng.nextGaussian()) * 3);
                m.put("thread_count", 50 + (float)rng.nextGaussian() * 5);
                m.put("resp_time", 800 + (float)rng.nextGaussian() * 200);
                m.put("heap_use", 6000 + (float)rng.nextGaussian() * 500);
                m.put("heap_tot", 8192f);
                m.put("gc_time", 50 + (float)rng.nextGaussian() * 20);
                m.put("sql_time", 300 + (float)rng.nextGaussian() * 80);
                m.put("httpc_time", 30 + (float)rng.nextGaussian() * 10);
                m.put("service_time", 2000 + (float)rng.nextGaussian() * 500);
                break;
            default: // 배치서버
                m.put("cpu", 10 + (float)rng.nextGaussian() * 5 + (tick%20==0?60:0));
                m.put("cpu_sys", 3 + (float)rng.nextGaussian());
                m.put("cpu_usr", 7 + (float)rng.nextGaussian() * 3);
                m.put("cpu_proc", 5 + (float)rng.nextGaussian() * 2);
                m.put("mem", 50 + (float)rng.nextGaussian() * 10);
                m.put("swap", 15 + (float)rng.nextGaussian() * 5);
                m.put("disk", 70 + (float)rng.nextGaussian() * 5);
                m.put("tps", 10 + (float)rng.nextGaussian() * 5);
                m.put("service_count", 5 + (float)Math.abs(rng.nextGaussian()) * 3);
                m.put("service_error", (float)Math.abs(rng.nextGaussian()) * 0.3f);
                m.put("gc_count", 2 + (float)rng.nextGaussian());
                m.put("sql_count", 10 + (float)rng.nextGaussian() * 5);
                m.put("httpc_count", 2 + (float)Math.abs(rng.nextGaussian()));
                m.put("act_svc_count", 1 + (float)Math.abs(rng.nextGaussian()) * 0.5f);
                m.put("thread_count", 30 + (float)rng.nextGaussian() * 3);
                m.put("resp_time", 5000 + (float)rng.nextGaussian() * 2000);
                m.put("heap_use", 1000 + (float)rng.nextGaussian() * 200);
                m.put("heap_tot", 2048f);
                m.put("gc_time", 20 + (float)rng.nextGaussian() * 5);
                m.put("sql_time", 30 + (float)rng.nextGaussian() * 10);
                m.put("httpc_time", 50 + (float)rng.nextGaussian() * 15);
                m.put("service_time", 10000 + (float)rng.nextGaussian() * 3000);
                break;
        }
        // clamp to positive
        for (Map.Entry<String, Float> e : m.entrySet()) {
            if (e.getValue() < 0) e.setValue(0f);
        }
        return m;
    }

    static boolean isGroupA(String name) {
        for (String n : GroupRotationIndex.GROUP_A_NAMES) if (n.equals(name)) return true;
        return false;
    }
    static boolean isGroupB(String name) {
        for (String n : GroupRotationIndex.GROUP_B_NAMES) if (n.equals(name)) return true;
        return false;
    }
}
