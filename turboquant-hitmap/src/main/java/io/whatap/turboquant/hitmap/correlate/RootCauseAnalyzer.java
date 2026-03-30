package io.whatap.turboquant.hitmap.correlate;

import java.util.*;

/**
 * Phase 12: 벡터 인덱스 간 시간 연관 분석으로 장애 근본 원인 추적.
 *
 * 1. 여러 인덱스에서 이상 이벤트를 수집
 * 2. 시간 윈도우(±N초)로 동시 발생 이벤트를 그룹핑
 * 3. 그룹 내에서 "무엇이 먼저 이상해졌는가" 시간 순서 분석
 * 4. 공통으로 벗어난 메트릭을 찾아 원인 추정
 */
public class RootCauseAnalyzer {

    private final long windowMs; // 시간 윈도우 (밀리초)

    public RootCauseAnalyzer(long windowMs) {
        this.windowMs = windowMs;
    }

    /**
     * 이상 이벤트들을 시간 윈도우로 묶어서 장애 그룹을 만든다.
     */
    public List<IncidentGroup> correlate(List<AnomalyEvent> events) {
        if (events.isEmpty()) return new ArrayList<IncidentGroup>();

        // 시간순 정렬
        List<AnomalyEvent> sorted = new ArrayList<AnomalyEvent>(events);
        Collections.sort(sorted, new Comparator<AnomalyEvent>() {
            public int compare(AnomalyEvent a, AnomalyEvent b) {
                return Long.compare(a.time, b.time);
            }
        });

        // 시간 윈도우로 그룹핑
        List<IncidentGroup> groups = new ArrayList<IncidentGroup>();
        IncidentGroup current = new IncidentGroup();
        current.add(sorted.get(0));

        for (int i = 1; i < sorted.size(); i++) {
            AnomalyEvent e = sorted.get(i);
            if (e.time - current.startTime() <= windowMs) {
                current.add(e);
            } else {
                groups.add(current);
                current = new IncidentGroup();
                current.add(e);
            }
        }
        groups.add(current);

        // 각 그룹 분석
        for (IncidentGroup g : groups) {
            g.analyze();
        }

        return groups;
    }

    /**
     * 장애 그룹: 동시에 발생한 이상 이벤트 묶음.
     */
    public static class IncidentGroup {
        public final List<AnomalyEvent> events = new ArrayList<AnomalyEvent>();
        public AnomalyEvent firstEvent;      // 가장 먼저 발생한 이벤트
        public String suspectedCause;         // 추정 원인
        public List<String> affectedServers;  // 영향 받은 서버
        public List<String> affectedUrls;     // 영향 받은 URL
        public Map<String, Float> commonMetrics; // 공통으로 벗어난 메트릭
        public List<String> timeline;         // 시간순 이벤트 타임라인

        void add(AnomalyEvent e) { events.add(e); }

        long startTime() {
            long min = Long.MAX_VALUE;
            for (AnomalyEvent e : events) if (e.time < min) min = e.time;
            return min;
        }

        long endTime() {
            long max = Long.MIN_VALUE;
            for (AnomalyEvent e : events) if (e.time > max) max = e.time;
            return max;
        }

        /**
         * 그룹 분석: 시간 순서, 공통 메트릭, 원인 추정.
         */
        void analyze() {
            // 시간순 정렬
            Collections.sort(events, new Comparator<AnomalyEvent>() {
                public int compare(AnomalyEvent a, AnomalyEvent b) {
                    return Long.compare(a.time, b.time);
                }
            });

            firstEvent = events.get(0);

            // 영향 받은 서버/URL 수집
            affectedServers = new ArrayList<String>();
            affectedUrls = new ArrayList<String>();
            for (AnomalyEvent e : events) {
                if (e.source == AnomalyEvent.Source.COUNTER && !affectedServers.contains(e.id)) {
                    affectedServers.add(e.id);
                }
                if (e.source == AnomalyEvent.Source.QUANTILE && !affectedUrls.contains(e.id)) {
                    affectedUrls.add(e.id);
                }
            }

            // 공통 메트릭 찾기: 카운터 이벤트들에서 벗어난 메트릭의 교집합
            commonMetrics = findCommonMetrics();

            // 타임라인 생성
            timeline = new ArrayList<String>();
            for (AnomalyEvent e : events) {
                timeline.add(String.format("[%s] %s %s (유사도: %.4f)%s",
                        e.timeString(), e.source, e.id, e.similarity,
                        e.details.isEmpty() ? "" : " " + topDeviations(e.details, 3)));
            }

            // 원인 추정
            suspectedCause = inferCause();
        }

        /**
         * 카운터 이벤트들에서 공통으로 크게 벗어난 메트릭 찾기.
         */
        private Map<String, Float> findCommonMetrics() {
            Map<String, Float> result = new LinkedHashMap<String, Float>();
            Map<String, List<Float>> allMetrics = new LinkedHashMap<String, List<Float>>();

            for (AnomalyEvent e : events) {
                if (e.source == AnomalyEvent.Source.COUNTER) {
                    for (Map.Entry<String, Float> m : e.details.entrySet()) {
                        if (m.getValue() > 20) { // 20% 이상 벗어난 메트릭만
                            List<Float> list = allMetrics.get(m.getKey());
                            if (list == null) { list = new ArrayList<Float>(); allMetrics.put(m.getKey(), list); }
                            list.add(m.getValue());
                        }
                    }
                }
            }

            // 2개 이상 서버에서 공통으로 벗어난 메트릭
            int counterCount = 0;
            for (AnomalyEvent e : events) if (e.source == AnomalyEvent.Source.COUNTER) counterCount++;

            for (Map.Entry<String, List<Float>> entry : allMetrics.entrySet()) {
                if (entry.getValue().size() >= Math.max(2, counterCount / 2)) {
                    float avg = 0;
                    for (float v : entry.getValue()) avg += v;
                    avg /= entry.getValue().size();
                    result.put(entry.getKey(), avg);
                }
            }

            return result;
        }

        /**
         * 원인 추정 로직.
         */
        private String inferCause() {
            if (commonMetrics.isEmpty()) {
                if (firstEvent.source == AnomalyEvent.Source.HITMAP) {
                    return "히트맵 분포 이상 — 서버별 상세 분석 필요";
                }
                return "단일 이벤트 — 추가 데이터 필요";
            }

            // 메트릭 패턴으로 원인 추정
            boolean hasGc = commonMetrics.containsKey("gc_count") || commonMetrics.containsKey("gc_time");
            boolean hasHeap = commonMetrics.containsKey("heap_use");
            boolean hasSql = commonMetrics.containsKey("sql_time") || commonMetrics.containsKey("sql_count");
            boolean hasCpu = commonMetrics.containsKey("cpu");
            boolean hasMem = commonMetrics.containsKey("mem");
            boolean hasRt = commonMetrics.containsKey("resp_time");
            boolean hasHttpc = commonMetrics.containsKey("httpc_time");

            if (hasSql && hasRt) {
                return "DB 쿼리 지연 → 응답시간 증가 (SQL 시간과 응답시간이 동시에 상승)";
            }
            if (hasGc && hasHeap) {
                return "메모리 부족 → GC 빈발 (힙 사용량 증가 + GC 시간 증가)";
            }
            if (hasCpu && !hasSql) {
                return "CPU 과부하 (SQL 무관, CPU 단독 상승)";
            }
            if (hasHttpc && hasRt) {
                return "외부 API 지연 → 응답시간 증가 (HTTP 호출 시간과 응답시간 동시 상승)";
            }
            if (hasMem) {
                return "서버 메모리 부족 (OS 레벨)";
            }
            if (hasRt) {
                return "응답시간 증가 — 구체적 원인은 프로필 분석 필요";
            }

            return "복합 이상 — 공통 메트릭: " + commonMetrics.keySet();
        }

        private String topDeviations(Map<String, Float> details, int n) {
            List<Map.Entry<String, Float>> sorted = new ArrayList<Map.Entry<String, Float>>(details.entrySet());
            Collections.sort(sorted, new Comparator<Map.Entry<String, Float>>() {
                public int compare(Map.Entry<String, Float> a, Map.Entry<String, Float> b) {
                    return Float.compare(b.getValue(), a.getValue());
                }
            });
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < Math.min(n, sorted.size()); i++) {
                if (i > 0) sb.append(", ");
                sb.append(sorted.get(i).getKey()).append(":+").append(String.format("%.0f%%", sorted.get(i).getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
    }
}
