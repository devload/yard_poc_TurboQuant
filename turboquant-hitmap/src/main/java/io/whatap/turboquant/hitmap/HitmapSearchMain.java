package io.whatap.turboquant.hitmap;

import io.whatap.turboquant.hitmap.HitmapReader.HitmapEntry;
import io.whatap.turboquant.hitmap.HitmapVectorIndex.SearchResult;
import io.whatap.turboquant.hitmap.HitmapVectorIndex.AnomalyResult;

import java.util.*;

/**
 * Phase 8: 실제 yardbase 히트맵으로 TurboQuant 벡터 검색 데모.
 *
 * 1. yardbase에서 히트맵 데이터 읽기
 * 2. TurboQuant 120차원 벡터 인덱스 구축
 * 3. "이 히트맵과 비슷한 과거 시점 찾기"
 * 4. "히트맵 분포가 비정상적으로 바뀌면 알림"
 */
public class HitmapSearchMain {

    static final String YARDBASE = "/Users/devload/Documents/whatap-server/yardbase";

    public static void main(String[] args) throws Exception {
        System.out.println("=== Phase 8: 실제 YARD 히트맵 벡터 검색 ===");
        System.out.println("yardbase: " + YARDBASE);
        System.out.println();

        // 1. yardbase에서 히트맵 읽기
        System.out.println("1. 히트맵 데이터 로딩...");
        List<HitmapEntry> entries = HitmapReader.readAll(YARDBASE);
        System.out.println("   로딩 완료: " + entries.size() + "개 히트맵");

        if (entries.isEmpty()) {
            System.out.println("   히트맵 데이터가 없습니다. 데모 데이터로 진행합니다.");
            entries = generateDemoHitmaps();
            System.out.println("   데모 히트맵 " + entries.size() + "개 생성");
        }

        // 히트맵 요약 출력
        System.out.println();
        System.out.println("   히트맵 목록:");
        System.out.println(String.format("   %-5s %-22s %-8s %-12s %8s %s",
                "#", "시간", "pcode", "날짜/시간", "총건수", "분포 미리보기"));
        System.out.println("   " + new String(new char[85]).replace('\0', '-'));
        for (int i = 0; i < entries.size(); i++) {
            HitmapEntry e = entries.get(i);
            System.out.println(String.format("   %-5d %-22s %-8s %s/%sh %8d %s",
                    i, e.timeString(), e.pcode, e.date, e.hour, e.totalHits(), preview(e.hit)));
        }

        // 2. TurboQuant 벡터 인덱스 구축
        System.out.println();
        System.out.println("2. TurboQuant 벡터 인덱스 구축 (4-bit, 120차원)...");
        HitmapVectorIndex index = new HitmapVectorIndex(4);

        long buildStart = System.nanoTime();
        for (HitmapEntry e : entries) {
            index.add(e);
        }
        long buildTime = System.nanoTime() - buildStart;

        System.out.println(String.format("   인덱스 구축 완료: %d개 벡터, %.1fms", index.size(), buildTime / 1e6));
        System.out.println(String.format("   벡터당 크기: %dB (원본 480B → %.1fx 압축)",
                index.compressedBytesPerEntry(), 480.0 / index.compressedBytesPerEntry()));

        // 3. 유사 패턴 검색
        System.out.println();
        System.out.println("3. 유사 패턴 검색: '이 히트맵과 비슷한 시점 찾기'");

        if (entries.size() >= 2) {
            HitmapEntry query = entries.get(entries.size() - 1); // 마지막 히트맵을 쿼리로
            System.out.println(String.format("   쿼리: %s (pcode=%s, 총건수=%d)",
                    query.timeString(), query.pcode, query.totalHits()));
            System.out.println();

            long searchStart = System.nanoTime();
            List<SearchResult> results = index.search(query, 5);
            long searchTime = System.nanoTime() - searchStart;

            System.out.println(String.format("   검색 시간: %.2fms", searchTime / 1e6));
            System.out.println();
            System.out.println(String.format("   %-5s %-22s %-8s %10s %8s",
                    "순위", "시간", "pcode", "유사도", "총건수"));
            System.out.println("   " + new String(new char[58]).replace('\0', '-'));
            int rank = 1;
            for (SearchResult r : results) {
                String timeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new java.util.Date(r.time));
                System.out.println(String.format("   #%-4d %-22s %-8s %9.4f %8d",
                        rank, timeStr, r.pcode, r.similarity, r.totalHits));
                rank++;
            }
        }

        // 4. 이상 탐지
        System.out.println();
        System.out.println("4. 이상 탐지: '히트맵 분포가 갑자기 바뀌면 알림'");

        if (entries.size() >= 3) {
            // 마지막 하나를 제외한 나머지를 베이스라인으로
            List<HitmapEntry> baseline = entries.subList(0, entries.size() - 1);
            HitmapEntry current = entries.get(entries.size() - 1);

            AnomalyResult ar = index.detectAnomaly(current, baseline, 2.0f);
            System.out.println(String.format("   현재 히트맵: %s (총건수: %d)",
                    current.timeString(), current.totalHits()));
            System.out.println(String.format("   베이스라인: %d개 히트맵", baseline.size()));
            System.out.println(String.format("   결과: %s", ar.isAnomaly ? "⚠ 이상 감지!" : "✓ 정상"));
            System.out.println(String.format("   상세: %s", ar.detail));
        }

        System.out.println();
        System.out.println("=== 완료 ===");
    }

    /**
     * 실제 데이터가 없을 때 사용할 데모 히트맵 생성
     */
    static List<HitmapEntry> generateDemoHitmaps() {
        List<HitmapEntry> entries = new ArrayList<HitmapEntry>();
        Random rng = new Random(42);
        long baseTime = System.currentTimeMillis() - 3600000 * 24; // 24시간 전

        // 정상 패턴: 대부분 0~2초 구간에 집중 (버킷 0~16)
        for (int i = 0; i < 20; i++) {
            HitmapEntry e = new HitmapEntry();
            e.time = baseTime + i * 300000; // 5분 간격
            e.pcode = "demo";
            e.date = "20260330";
            e.hour = String.format("%02d", (i * 5 / 60) % 24);
            e.hit = new int[120];
            e.error = new int[120];
            // 정상 분포: 0~2초에 90%, 2~5초에 9%, 나머지 1%
            for (int j = 0; j < 200; j++) {
                double t = Math.abs(rng.nextGaussian() * 500 + 800); // 평균 800ms
                int idx = HitMapPack1Index(t);
                if (idx >= 0 && idx < 120) e.hit[idx]++;
            }
            entries.add(e);
        }

        // 이상 패턴: 5~10초 구간에 갑자기 집중 (히트맵 모양이 확 바뀜)
        HitmapEntry anomaly = new HitmapEntry();
        anomaly.time = baseTime + 20 * 300000;
        anomaly.pcode = "demo";
        anomaly.date = "20260330";
        anomaly.hour = "12";
        anomaly.hit = new int[120];
        anomaly.error = new int[120];
        for (int j = 0; j < 200; j++) {
            double t = Math.abs(rng.nextGaussian() * 2000 + 7000); // 평균 7초!
            int idx = HitMapPack1Index(t);
            if (idx >= 0 && idx < 120) anomaly.hit[idx]++;
        }
        entries.add(anomaly);

        return entries;
    }

    static int HitMapPack1Index(double timeMs) {
        int time = (int) timeMs;
        int x = time / 10000;
        switch (x) {
            case 0:
                if (time < 5000) return time / 125;
                else return 40 + (time - 5000) / 250;
            case 1: return 60 + (time - 10000) / 500;
            case 2: case 3: return 80 + (time - 20000) / 1000;
            case 4: case 5: case 6: case 7: return 100 + (time - 40000) / 2000;
            default: return 119;
        }
    }

    static String preview(int[] arr) {
        StringBuilder sb = new StringBuilder("[");
        int shown = 0;
        for (int i = 0; i < arr.length && shown < 8; i++) {
            if (arr[i] > 0) {
                if (shown > 0) sb.append(",");
                sb.append(i).append(":").append(arr[i]);
                shown++;
            }
        }
        if (shown == 0) sb.append("empty");
        sb.append("]");
        return sb.toString();
    }
}
