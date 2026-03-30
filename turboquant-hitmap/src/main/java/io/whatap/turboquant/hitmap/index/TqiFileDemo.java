package io.whatap.turboquant.hitmap.index;

import io.whatap.turboquant.core.TurboQuantizer;
import io.whatap.turboquant.hitmap.index.TqiFile.TqiEntry;

import java.io.File;
import java.util.*;

/**
 * .tqi 파일 기반 벡터 인덱스 데모.
 *
 * 1. 파일 생성 + 히트맵 벡터 쓰기
 * 2. 파일 닫고 다시 열기 (재시작 시뮬레이션)
 * 3. 파일에서 읽어서 유사도 검색
 */
public class TqiFileDemo {

    static final int DIM = 120;
    static final int BITS = 4;
    static final long SEED = 42L;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Phase 11: 파일 기반 벡터 인덱스 (.tqi) ===");
        System.out.println();

        File tqiFile = new File("/tmp/hitmap_demo.tqi");
        tqiFile.delete(); // 이전 테스트 파일 삭제

        TurboQuantizer tq = new TurboQuantizer(BITS, DIM, SEED);
        int compressedSize = tq.compressedSize();

        // === 1. 파일 생성 + 히트맵 쓰기 ===
        System.out.println("1. 인덱스 파일 생성 + 히트맵 쓰기");
        Random rng = new Random(42);

        TqiFile writer = TqiFile.create(tqiFile, DIM, BITS, compressedSize, SEED);

        long baseTime = System.currentTimeMillis() - 3600000;
        int normalCount = 100;
        int anomalyCount = 5;

        // 정상 히트맵 100개 (0~2초 구간 집중)
        long writeStart = System.nanoTime();
        for (int i = 0; i < normalCount; i++) {
            float[] hitmap = generateNormalHitmap(rng);
            float[] normalized = normalize(hitmap);
            byte[] compressed = tq.compress(normalized);
            int totalHits = sum(hitmap);
            writer.append(baseTime + i * 300000, 12345L, 1000 + (i % 5), totalHits, compressed);
        }

        // 이상 히트맵 5개 (5~10초 구간 집중)
        for (int i = 0; i < anomalyCount; i++) {
            float[] hitmap = generateAnomalyHitmap(rng);
            float[] normalized = normalize(hitmap);
            byte[] compressed = tq.compress(normalized);
            int totalHits = sum(hitmap);
            writer.append(baseTime + (normalCount + i) * 300000, 12345L, 1000, totalHits, compressed);
        }

        writer.flush();
        writer.close();

        System.out.println(String.format("   기록: %d개 엔트리 (정상 %d + 이상 %d)",
                normalCount + anomalyCount, normalCount, anomalyCount));
        System.out.println(String.format("   파일: %s", tqiFile.getAbsolutePath()));
        System.out.println(String.format("   크기: %,d bytes (%.1f KB)", tqiFile.length(), tqiFile.length() / 1024.0));
        System.out.println(String.format("   엔트리당: %d bytes (원본 480B → %.1fx 압축)",
                compressedSize + 24, 480.0 / (compressedSize + 24)));

        // === 2. 파일 닫고 다시 열기 (재시작 시뮬레이션) ===
        System.out.println();
        System.out.println("2. 파일 다시 열기 (YARD 재시작 시뮬레이션)");

        long openStart = System.nanoTime();
        TqiFile reader = TqiFile.open(tqiFile);
        long openTime = System.nanoTime() - openStart;

        System.out.println(String.format("   복원: %d개 엔트리, %.2fms",
                reader.getCount(), openTime / 1e6));
        System.out.println(String.format("   dim=%d, bits=%d, compressedSize=%d, seed=%d",
                reader.getDim(), reader.getBits(), reader.getCompressedSize(), reader.getRotationSeed()));

        // === 3. 유사도 검색 ===
        System.out.println();
        System.out.println("3. 파일 기반 유사도 검색");

        // 쿼리: 마지막 이상 히트맵
        TqiEntry queryEntry = reader.read(reader.getCount() - 1);
        float[] qRecon = tq.decompress(queryEntry.compressed);

        System.out.println(String.format("   쿼리: %s (totalHits=%d)", queryEntry.timeString(), queryEntry.totalHits));

        // 전수 검색
        long searchStart = System.nanoTime();
        List<ScoredEntry> scored = new ArrayList<ScoredEntry>();
        for (int i = 0; i < reader.getCount() - 1; i++) {
            TqiEntry e = reader.read(i);
            float[] eRecon = tq.decompress(e.compressed);
            float sim = cosineSim(qRecon, eRecon);
            scored.add(new ScoredEntry(i, e, sim));
        }
        Collections.sort(scored);
        long searchTime = System.nanoTime() - searchStart;

        System.out.println(String.format("   검색 시간: %.2fms (%d개 벡터)", searchTime / 1e6, reader.getCount()));
        System.out.println();
        System.out.println(String.format("   %-5s %-22s %5s %8s %10s %6s",
                "순위", "시간", "oid", "총건수", "유사도", "유형"));
        System.out.println("   " + new String(new char[62]).replace('\0', '-'));

        for (int i = 0; i < Math.min(10, scored.size()); i++) {
            ScoredEntry s = scored.get(i);
            String type = s.index >= normalCount ? "이상" : "정상";
            System.out.println(String.format("   #%-4d %-22s %5d %8d %9.4f %6s",
                    i + 1, s.entry.timeString(), s.entry.oid, s.entry.totalHits, s.similarity, type));
        }

        // === 4. 이상 탐지 ===
        System.out.println();
        System.out.println("4. 이상 탐지 (정상 베이스라인 vs 각 엔트리)");

        // 베이스라인: 처음 50개의 평균 벡터
        float[] baseline = new float[DIM];
        for (int i = 0; i < 50; i++) {
            float[] recon = tq.decompress(reader.read(i).compressed);
            for (int d = 0; d < DIM; d++) baseline[d] += recon[d] / 50;
        }

        // 전체 스캔하면서 이상 점수
        System.out.println(String.format("   %-5s %-22s %10s %6s",
                "#", "시간", "유사도", "판정"));
        System.out.println("   " + new String(new char[48]).replace('\0', '-'));

        int detected = 0, missed = 0, falsePositive = 0;
        for (int i = 0; i < reader.getCount(); i++) {
            TqiEntry e = reader.read(i);
            float[] recon = tq.decompress(e.compressed);
            float sim = cosineSim(recon, baseline);
            boolean isAnomaly = sim < 0.5;
            boolean actualAnomaly = i >= normalCount;

            if (i >= normalCount || isAnomaly) { // 이상이거나 이상으로 판정된 것만 출력
                String verdict = isAnomaly && actualAnomaly ? "TP" :
                        isAnomaly && !actualAnomaly ? "FP" :
                                !isAnomaly && actualAnomaly ? "FN" : "TN";
                System.out.println(String.format("   %-5d %-22s %9.4f %6s %s",
                        i, e.timeString(), sim, isAnomaly ? "이상" : "정상", verdict));
            }

            if (isAnomaly && actualAnomaly) detected++;
            if (isAnomaly && !actualAnomaly) falsePositive++;
            if (!isAnomaly && actualAnomaly) missed++;
        }

        System.out.println();
        System.out.println(String.format("   정탐(TP): %d/%d, 오탐(FP): %d, 미탐(FN): %d",
                detected, anomalyCount, falsePositive, missed));

        // === 5. 파일 크기 분석 ===
        System.out.println();
        System.out.println("5. 저장 효율");
        System.out.println(String.format("   벡터 %d개 × %dB/엔트리 = %,d bytes (%.1f KB)",
                reader.getCount(), reader.getEntrySize(), reader.fileSizeBytes(), reader.fileSizeBytes() / 1024.0));
        System.out.println(String.format("   원본 대비: %d개 × 480B = %,d bytes → %.1fx 압축",
                reader.getCount(), reader.getCount() * 480, (reader.getCount() * 480.0) / reader.fileSizeBytes()));

        // 스케일 예측
        int[] scales = {1000, 10000, 100000, 1000000};
        System.out.println();
        System.out.println(String.format("   %-12s %12s %12s", "벡터 수", ".tqi 크기", "원본 크기"));
        System.out.println("   " + new String(new char[38]).replace('\0', '-'));
        for (int n : scales) {
            long tqiSize = TqiFile.HEADER_SIZE + (long) n * reader.getEntrySize();
            long rawSize = (long) n * 480;
            System.out.println(String.format("   %-12s %12s %12s",
                    String.format("%,d", n), formatSize(tqiSize), formatSize(rawSize)));
        }

        reader.close();
        System.out.println();
        System.out.println("=== 완료 ===");
    }

    // === 헬퍼 ===
    static float[] generateNormalHitmap(Random rng) {
        float[] h = new float[DIM];
        for (int i = 0; i < 200; i++) {
            double t = Math.abs(rng.nextGaussian() * 500 + 800);
            int idx = hitmapIndex(t);
            if (idx >= 0 && idx < DIM) h[idx]++;
        }
        return h;
    }

    static float[] generateAnomalyHitmap(Random rng) {
        float[] h = new float[DIM];
        for (int i = 0; i < 200; i++) {
            double t = Math.abs(rng.nextGaussian() * 2000 + 7000);
            int idx = hitmapIndex(t);
            if (idx >= 0 && idx < DIM) h[idx]++;
        }
        return h;
    }

    static int hitmapIndex(double ms) {
        int t = (int) ms;
        if (t < 5000) return t / 125;
        if (t < 10000) return 40 + (t - 5000) / 250;
        if (t < 20000) return 60 + (t - 10000) / 500;
        if (t < 40000) return 80 + (t - 20000) / 1000;
        if (t < 80000) return 100 + (t - 40000) / 2000;
        return 119;
    }

    static float[] normalize(float[] v) {
        float norm = 0;
        for (float f : v) norm += f * f;
        norm = (float) Math.sqrt(norm);
        if (norm < 1e-10f) return v;
        float[] r = new float[v.length];
        for (int i = 0; i < v.length; i++) r[i] = v[i] / norm;
        return r;
    }

    static int sum(float[] v) { int s = 0; for (float f : v) s += (int) f; return s; }

    static float cosineSim(float[] a, float[] b) {
        float dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        na = (float) Math.sqrt(na); nb = (float) Math.sqrt(nb);
        return (na < 1e-10f || nb < 1e-10f) ? 0 : dot / (na * nb);
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }

    static class ScoredEntry implements Comparable<ScoredEntry> {
        final int index; final TqiEntry entry; final float similarity;
        ScoredEntry(int i, TqiEntry e, float s) { index = i; entry = e; similarity = s; }
        public int compareTo(ScoredEntry o) { return Float.compare(o.similarity, similarity); }
    }
}
