package io.whatap.turboquant.hitmap;

import io.whatap.io.DataInputX;
import io.whatap.lang.value.*;
import io.whatap.turboquant.hitmap.counter.GroupRotationIndex;
import io.whatap.turboquant.hitmap.correlate.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 실제 YARD .dat2 데이터를 읽어서 실시간 이상탐지.
 *
 * 1. .dat2 파싱 → 서버별 메트릭 시계열 추출
 * 2. GroupRotationIndex에 벡터 등록
 * 3. 베이스라인(처음 80%) 대비 최근(마지막 20%) 이상 탐지
 * 4. 이상 발견 시 RootCauseAnalyzer로 원인 추적
 */
public class LiveAnomalyDetector {

    static final String DATA_DIR = "/tmp/ab-data";

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  실시간 이상 탐지 — 실제 YARD 데이터        ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        // 1. .dat2 파싱
        System.out.println("1. 실제 app_counter.dat2 파싱...");
        File dat2 = new File(DATA_DIR, "tagc-app_counter.dat2");
        List<ServerSnapshot> snapshots = parseDat2(dat2);
        System.out.println("   파싱 완료: " + snapshots.size() + "개 레코드");

        if (snapshots.isEmpty()) {
            System.out.println("   데이터 없음. 종료.");
            return;
        }

        // 서버별 그룹핑
        Map<Integer, List<ServerSnapshot>> byOid = new LinkedHashMap<Integer, List<ServerSnapshot>>();
        for (ServerSnapshot s : snapshots) {
            List<ServerSnapshot> list = byOid.get(s.oid);
            if (list == null) { list = new ArrayList<ServerSnapshot>(); byOid.put(s.oid, list); }
            list.add(s);
        }
        System.out.println("   서버 수: " + byOid.size());
        for (Map.Entry<Integer, List<ServerSnapshot>> e : byOid.entrySet()) {
            System.out.println("     oid=" + e.getKey() + ": " + e.getValue().size() + "개 스냅샷");
        }

        // 2. 벡터 인덱스 구축
        System.out.println();
        System.out.println("2. GroupRotation 벡터 인덱스 구축 (8-bit)...");
        GroupRotationIndex index = new GroupRotationIndex(8);

        for (ServerSnapshot s : snapshots) {
            index.add(s.oid, s.time, s.metrics);
        }
        System.out.println("   인덱스: " + index.size() + "개 벡터, " + index.compressedBytesPerEntry() + "B/벡터");

        // 3. 이상 탐지: 서버별로 베이스라인 vs 최근 비교
        System.out.println();
        System.out.println("3. 이상 탐지: 서버별 베이스라인 대비 편차...");
        System.out.println();

        List<AnomalyEvent> allAnomalies = new ArrayList<AnomalyEvent>();

        for (Map.Entry<Integer, List<ServerSnapshot>> entry : byOid.entrySet()) {
            int oid = entry.getKey();
            List<ServerSnapshot> serverData = entry.getValue();
            if (serverData.size() < 10) continue;

            // 베이스라인: 처음 80%, 검사 대상: 마지막 20%
            int splitIdx = (int)(serverData.size() * 0.8);
            List<ServerSnapshot> baseline = serverData.subList(0, splitIdx);
            List<ServerSnapshot> recent = serverData.subList(splitIdx, serverData.size());

            // 베이스라인 평균/분산 계산
            Map<String, double[]> stats = new LinkedHashMap<String, double[]>(); // [mean, std]
            for (String key : baseline.get(0).metrics.keySet()) {
                double sum = 0, sumSq = 0;
                int cnt = 0;
                for (ServerSnapshot s : baseline) {
                    Float v = s.metrics.get(key);
                    if (v != null) { sum += v; sumSq += v * v; cnt++; }
                }
                if (cnt > 0) {
                    double mean = sum / cnt;
                    double std = Math.sqrt(Math.max(0, sumSq / cnt - mean * mean));
                    stats.put(key, new double[]{mean, std});
                }
            }

            // 최근 데이터에서 이상 검사
            System.out.println("   서버 oid=" + oid + " (베이스라인: " + baseline.size() + ", 검사: " + recent.size() + ")");

            for (ServerSnapshot s : recent) {
                Map<String, Float> deviations = new LinkedHashMap<String, Float>();
                double totalDeviation = 0;
                int devCount = 0;

                for (Map.Entry<String, Float> m : s.metrics.entrySet()) {
                    double[] stat = stats.get(m.getKey());
                    if (stat == null || stat[1] < 0.001) continue;
                    double zscore = Math.abs((m.getValue() - stat[0]) / stat[1]);
                    if (zscore > 2.0) { // 2-sigma 이상
                        deviations.put(m.getKey(), (float)(zscore * 100 / stat[0]) * (stat[0] > 0.01 ? 1 : 0));
                        // 상대 편차(%)로 저장
                        float relDev = stat[0] > 0.01 ? (float)(Math.abs(m.getValue() - stat[0]) / stat[0] * 100) : 0;
                        deviations.put(m.getKey(), relDev);
                    }
                    totalDeviation += zscore;
                    devCount++;
                }

                double avgDeviation = devCount > 0 ? totalDeviation / devCount : 0;

                if (deviations.size() >= 2 || avgDeviation > 2.0) {
                    // 이상 이벤트
                    String timeStr = new java.text.SimpleDateFormat("HH:mm:ss").format(new Date(s.time));
                    System.out.println(String.format("     ⚠ [%s] 편차 %.1f, 이상 메트릭 %d개: %s",
                            timeStr, avgDeviation, deviations.size(), topN(deviations, 3)));

                    allAnomalies.add(new AnomalyEvent(
                            AnomalyEvent.Source.COUNTER, s.time, "oid-" + oid,
                            (float)(1.0 / (1.0 + avgDeviation)), deviations));
                }
            }

            if (allAnomalies.isEmpty() || allAnomalies.get(allAnomalies.size()-1).id.equals("oid-" + oid) == false) {
                System.out.println("     ✓ 정상 (2-sigma 이상 편차 없음)");
            }
        }

        // 4. 근본 원인 분석
        if (!allAnomalies.isEmpty()) {
            System.out.println();
            System.out.println("4. 근본 원인 분석...");
            RootCauseAnalyzer analyzer = new RootCauseAnalyzer(300000);
            List<RootCauseAnalyzer.IncidentGroup> groups = analyzer.correlate(allAnomalies);

            for (int g = 0; g < groups.size(); g++) {
                RootCauseAnalyzer.IncidentGroup group = groups.get(g);
                System.out.println();
                System.out.println("  ┌─ 장애 그룹 #" + (g+1) + " (" + group.events.size() + "개 이벤트) ─────");
                System.out.println("  │ 최초: " + group.firstEvent.timeString() + " " + group.firstEvent.id);
                System.out.println("  │ 서버: " + group.affectedServers);
                if (!group.commonMetrics.isEmpty()) {
                    System.out.println("  │ 공통 이상 메트릭:");
                    for (Map.Entry<String, Float> m : group.commonMetrics.entrySet()) {
                        System.out.println(String.format("  │   %-20s +%.0f%% 벗어남", m.getKey(), m.getValue()));
                    }
                }
                System.out.println("  │ 추정 원인: " + group.suspectedCause);
                System.out.println("  └─────────────────────────────────");
            }
        } else {
            System.out.println();
            System.out.println("4. 이상 없음 — 모든 서버 정상 범위 내");
        }

        // 5. 유사 서버 검색
        System.out.println();
        System.out.println("5. 유사 서버 검색: 가장 최근 스냅샷 기준");
        ServerSnapshot lastSnap = snapshots.get(snapshots.size() - 1);
        List<GroupRotationIndex.SearchResult> similar = index.search(lastSnap.metrics, lastSnap.oid, 5);
        System.out.println("   쿼리: oid=" + lastSnap.oid);
        System.out.println(String.format("   %-6s %-8s %10s", "순위", "서버", "유사도"));
        System.out.println("   " + new String(new char[28]).replace('\0', '-'));
        int rank = 1;
        for (GroupRotationIndex.SearchResult r : similar) {
            System.out.println(String.format("   #%-5d oid=%-4d %9.4f", rank, r.oid, r.similarity));
            rank++;
        }

        System.out.println();
        System.out.println("=== 완료 ===");
    }

    /**
     * .dat2 파싱: WriteSAM int3 포맷 → TagCountPack field 레코드 → 메트릭 추출
     */
    static List<ServerSnapshot> parseDat2(File file) throws Exception {
        List<ServerSnapshot> results = new ArrayList<ServerSnapshot>();
        byte[] raw = Files.readAllBytes(file.toPath());

        // int3 레코드 파싱
        int pos = 0;
        while (pos + 3 <= raw.length) {
            int len = ((raw[pos] & 0xFF) << 16) | ((raw[pos+1] & 0xFF) << 8) | (raw[pos+2] & 0xFF);
            pos += 3;
            if (len == 0x7FFFFF) {
                if (pos + 4 > raw.length) break;
                len = ((raw[pos] & 0xFF) << 24) | ((raw[pos+1] & 0xFF) << 16)
                        | ((raw[pos+2] & 0xFF) << 8) | (raw[pos+3] & 0xFF);
                pos += 4;
            }
            if (len < 0 || pos + len > raw.length) break;

            if (len > 2) {
                try {
                    byte[] record = new byte[len];
                    System.arraycopy(raw, pos, record, 0, len);
                    ServerSnapshot snap = parseFieldRecord(record);
                    if (snap != null && !snap.metrics.isEmpty()) {
                        results.add(snap);
                    }
                } catch (Exception e) {
                    // 파싱 실패 무시
                }
            }
            pos += len;
        }
        return results;
    }

    static ServerSnapshot parseFieldRecord(byte[] record) {
        DataInputX din = new DataInputX(record);
        ServerSnapshot snap = new ServerSnapshot();
        snap.time = System.currentTimeMillis(); // 타임스탬프는 .tim에서 가져와야 하지만 여기선 생략
        snap.oid = record.hashCode() & 0xFFFF; // 간이 oid

        try {
            int fieldIdx = 0;
            while (din.readBoolean()) {
                long idx = din.readDecimal();
                Value value = din.readValue();

                if (value instanceof MetricValue) {
                    MetricValue mv = (MetricValue) value;
                    if (mv.count > 0) {
                        snap.metrics.put("field_" + idx, (float) mv.avg());
                    }
                } else if (value instanceof FloatValue) {
                    snap.metrics.put("field_" + idx, ((FloatValue) value).value);
                } else if (value instanceof DoubleValue) {
                    snap.metrics.put("field_" + idx, (float) ((DoubleValue) value).value);
                } else if (value instanceof DecimalValue) {
                    snap.metrics.put("field_" + idx, (float) ((DecimalValue) value).value);
                }
                fieldIdx++;
            }
        } catch (Exception e) {
            // 파싱 끝
        }

        // oid를 첫 번째 필드의 해시로 대체 (같은 에이전트면 같은 oid)
        if (snap.metrics.containsKey("field_0")) {
            snap.oid = Math.abs(Float.floatToIntBits(snap.metrics.get("field_0"))) % 10000;
        }

        return snap;
    }

    static String topN(Map<String, Float> map, int n) {
        List<Map.Entry<String, Float>> sorted = new ArrayList<Map.Entry<String, Float>>(map.entrySet());
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
        return sb.append("}").toString();
    }

    static class ServerSnapshot {
        long time;
        int oid;
        Map<String, Float> metrics = new LinkedHashMap<String, Float>();
    }
}
