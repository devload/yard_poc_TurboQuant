package io.whatap.turboquant.hitmap.correlate;

import java.util.*;

/**
 * Phase 12b: 장애 원인 심층 추적 (Deep Drill-Down).
 *
 * RootCauseAnalyzer가 "SQL이 느려졌다"까지 찾았으면,
 * DeepDrillDown이 "어떤 SQL이, 왜 느려졌는가"까지 파고든다.
 *
 * 추적 깊이:
 *   1단계: 메트릭 이상 감지 (RootCauseAnalyzer)
 *   2단계: 이상 메트릭의 원인 서버/컴포넌트 식별
 *   3단계: 해당 서버의 상세 카운터 분석 (DB 세션, 커넥션 풀 등)
 *   4단계: 슬로우 쿼리/URL 특정 (StatGeneralPack 기반)
 *   5단계: 원인 체인 요약
 */
public class DeepDrillDown {

    /**
     * 장애 그룹을 심층 분석하여 원인 체인을 반환.
     */
    public static DrillDownReport analyze(RootCauseAnalyzer.IncidentGroup group,
                                           ServerDetailProvider serverDetails,
                                           QueryStatsProvider queryStats) {
        DrillDownReport report = new DrillDownReport();
        report.group = group;

        // 1단계: 이미 RootCauseAnalyzer가 분석한 결과
        report.level1_symptom = group.suspectedCause;
        report.level1_commonMetrics = group.commonMetrics;

        // 2단계: 최초 발생 서버의 상세 카운터 분석
        AnomalyEvent firstCounter = null;
        for (AnomalyEvent e : group.events) {
            if (e.source == AnomalyEvent.Source.COUNTER) {
                firstCounter = e;
                break;
            }
        }

        if (firstCounter != null && serverDetails != null) {
            ServerDetail detail = serverDetails.getDetail(firstCounter.id, firstCounter.time);
            if (detail != null) {
                report.level2_server = firstCounter.id;
                report.level2_detail = detail;
                report.level2_analysis = analyzeServerDetail(detail, group.commonMetrics);
            }
        }

        // 3단계: 슬로우 쿼리/URL 특정
        if (queryStats != null && firstCounter != null) {
            List<SlowQuery> slowQueries = queryStats.getSlowQueries(firstCounter.id, firstCounter.time);
            List<SlowUrl> slowUrls = queryStats.getSlowUrls(firstCounter.id, firstCounter.time);
            report.level3_slowQueries = slowQueries;
            report.level3_slowUrls = slowUrls;
            report.level3_analysis = analyzeQueries(slowQueries, slowUrls);
        }

        // 4단계: 원인 체인 생성
        report.causeChain = buildCauseChain(report);

        return report;
    }

    /**
     * 서버 상세 카운터에서 이상 원인 분석.
     */
    static String analyzeServerDetail(ServerDetail detail, Map<String, Float> commonMetrics) {
        StringBuilder sb = new StringBuilder();

        if (detail.dbConnectionPool != null) {
            if (detail.dbConnectionPool.activeRatio > 0.9f) {
                sb.append("DB 커넥션 풀 포화 (활성 " +
                        String.format("%.0f%%", detail.dbConnectionPool.activeRatio * 100) +
                        ", 대기 " + detail.dbConnectionPool.waitCount + "건). ");
            }
        }

        if (detail.heapUsagePercent > 85) {
            sb.append("힙 메모리 " + String.format("%.0f%%", detail.heapUsagePercent) + " 사용 (위험). ");
        }

        if (detail.gcTimePercent > 20) {
            sb.append("GC에 CPU " + String.format("%.0f%%", detail.gcTimePercent) + " 소모. ");
        }

        if (detail.threadPoolUsage > 0.8f) {
            sb.append("스레드 풀 " + String.format("%.0f%%", detail.threadPoolUsage * 100) + " 사용. ");
        }

        if (detail.diskIoWait > 30) {
            sb.append("디스크 I/O 대기 " + String.format("%.0f%%", detail.diskIoWait) + ". ");
        }

        return sb.length() > 0 ? sb.toString() : "상세 카운터 정상 범위";
    }

    /**
     * 슬로우 쿼리/URL 분석.
     */
    static String analyzeQueries(List<SlowQuery> queries, List<SlowUrl> urls) {
        StringBuilder sb = new StringBuilder();

        if (queries != null && !queries.isEmpty()) {
            SlowQuery worst = queries.get(0);
            sb.append("가장 느린 쿼리: ").append(worst.sqlText).append(" (")
                    .append(String.format("%.0fms → %.0fms", worst.normalAvgMs, worst.currentAvgMs))
                    .append(", ").append(String.format("%.0f배", worst.currentAvgMs / Math.max(1, worst.normalAvgMs)))
                    .append(" 느려짐). ");

            if (queries.size() > 1) {
                sb.append("외 ").append(queries.size() - 1).append("개 쿼리 영향. ");
            }
        }

        if (urls != null && !urls.isEmpty()) {
            sb.append("영향 URL: ");
            for (int i = 0; i < Math.min(3, urls.size()); i++) {
                if (i > 0) sb.append(", ");
                SlowUrl u = urls.get(i);
                sb.append(u.url).append("(")
                        .append(String.format("%.0fms→%.0fms", u.normalAvgMs, u.currentAvgMs))
                        .append(")");
            }
            sb.append(". ");
        }

        return sb.length() > 0 ? sb.toString() : "슬로우 쿼리 정보 없음";
    }

    /**
     * 원인 체인 생성 — "왜?" 를 계속 따라가는 연쇄.
     */
    static List<CauseChainStep> buildCauseChain(DrillDownReport report) {
        List<CauseChainStep> chain = new ArrayList<CauseChainStep>();

        // 최종 증상부터 역순으로
        // 히트맵 이상
        boolean hasHitmap = false;
        for (AnomalyEvent e : report.group.events) {
            if (e.source == AnomalyEvent.Source.HITMAP) { hasHitmap = true; break; }
        }
        if (hasHitmap) {
            chain.add(new CauseChainStep("증상", "히트맵 분포 이상 감지",
                    "응답시간 분포가 평소와 크게 달라졌다"));
        }

        // URL 영향
        if (!report.group.affectedUrls.isEmpty()) {
            chain.add(new CauseChainStep("영향", report.group.affectedUrls.size() + "개 URL 응답시간 급증",
                    "URL: " + report.group.affectedUrls));
        }

        // 서버 이상
        if (!report.group.affectedServers.isEmpty()) {
            String metrics = "";
            if (!report.group.commonMetrics.isEmpty()) {
                List<String> top = new ArrayList<String>();
                for (Map.Entry<String, Float> m : report.group.commonMetrics.entrySet()) {
                    top.add(m.getKey() + " +" + String.format("%.0f%%", m.getValue()));
                    if (top.size() >= 3) break;
                }
                metrics = String.join(", ", top);
            }
            chain.add(new CauseChainStep("전파", report.group.affectedServers.size() + "대 서버 메트릭 이상",
                    metrics));
        }

        // 서버 상세 원인
        if (report.level2_analysis != null && !report.level2_analysis.equals("상세 카운터 정상 범위")) {
            chain.add(new CauseChainStep("원인", report.level2_analysis, "서버: " + report.level2_server));
        }

        // 쿼리/URL 레벨 원인
        if (report.level3_analysis != null && !report.level3_analysis.equals("슬로우 쿼리 정보 없음")) {
            chain.add(new CauseChainStep("근본원인", report.level3_analysis, ""));
        }

        return chain;
    }

    // === 데이터 모델 ===

    public static class DrillDownReport {
        public RootCauseAnalyzer.IncidentGroup group;

        // 1단계: 증상
        public String level1_symptom;
        public Map<String, Float> level1_commonMetrics;

        // 2단계: 서버 상세
        public String level2_server;
        public ServerDetail level2_detail;
        public String level2_analysis;

        // 3단계: 쿼리/URL
        public List<SlowQuery> level3_slowQueries;
        public List<SlowUrl> level3_slowUrls;
        public String level3_analysis;

        // 최종: 원인 체인
        public List<CauseChainStep> causeChain;
    }

    public static class CauseChainStep {
        public final String level;
        public final String description;
        public final String detail;

        public CauseChainStep(String level, String description, String detail) {
            this.level = level;
            this.description = description;
            this.detail = detail;
        }
    }

    public static class ServerDetail {
        public float heapUsagePercent;
        public float gcTimePercent;
        public float threadPoolUsage;
        public float diskIoWait;
        public DbConnectionPool dbConnectionPool;
    }

    public static class DbConnectionPool {
        public int total;
        public int active;
        public int idle;
        public int waitCount;
        public float activeRatio;

        public DbConnectionPool(int total, int active, int waitCount) {
            this.total = total;
            this.active = active;
            this.idle = total - active;
            this.waitCount = waitCount;
            this.activeRatio = total > 0 ? (float) active / total : 0;
        }
    }

    public static class SlowQuery {
        public String sqlHash;
        public String sqlText;
        public float normalAvgMs;
        public float currentAvgMs;
        public int count;

        public SlowQuery(String hash, String text, float normalMs, float currentMs, int count) {
            this.sqlHash = hash; this.sqlText = text;
            this.normalAvgMs = normalMs; this.currentAvgMs = currentMs; this.count = count;
        }
    }

    public static class SlowUrl {
        public String url;
        public float normalAvgMs;
        public float currentAvgMs;
        public int count;

        public SlowUrl(String url, float normalMs, float currentMs, int count) {
            this.url = url; this.normalAvgMs = normalMs; this.currentAvgMs = currentMs; this.count = count;
        }
    }

    // === Provider 인터페이스 (YARD 데이터 연결용) ===

    public interface ServerDetailProvider {
        ServerDetail getDetail(String serverId, long time);
    }

    public interface QueryStatsProvider {
        List<SlowQuery> getSlowQueries(String serverId, long time);
        List<SlowUrl> getSlowUrls(String serverId, long time);
    }
}
