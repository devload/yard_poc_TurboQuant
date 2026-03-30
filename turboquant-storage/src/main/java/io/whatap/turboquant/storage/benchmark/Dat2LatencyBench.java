package io.whatap.turboquant.storage.benchmark;

import io.whatap.util.CompressUtil;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * API 조회 속도 영향 측정.
 * 실제 .dat2 레코드에 대해:
 *   1) Raw read (현재): byte[] 그대로 반환
 *   2) GZIP decompress: byte[] → unZip() → 반환
 *   3) 레코드별 latency (ns) 측정
 */
public class Dat2LatencyBench {

    static final String YARDBASE = "/Users/devload/Documents/whatap-server/yardbase";
    static final int WARMUP = 100;
    static final int ITERATIONS = 1000;

    public static void main(String[] args) throws Exception {
        System.out.println("=== API 조회 속도 영향 벤치마크 ===");
        System.out.println("Warmup: " + WARMUP + " iterations, Measure: " + ITERATIONS + " iterations");
        System.out.println();

        // 실제 .dat2 파일에서 레코드 수집
        List<byte[]> allRecords = new ArrayList<byte[]>();
        List<Path> dat2Files = new ArrayList<Path>();
        Files.walk(Paths.get(YARDBASE)).filter(p -> p.toString().endsWith(".dat2")).forEach(dat2Files::add);

        for (Path dat2 : dat2Files) {
            byte[] raw = Files.readAllBytes(dat2);
            if (raw.length < 4) continue;
            List<byte[]> records = parseInt3Records(raw);
            for (byte[] r : records) {
                if (r.length > 10) allRecords.add(r);
            }
        }

        System.out.println("총 레코드: " + allRecords.size() + "개");
        long totalBytes = 0;
        for (byte[] r : allRecords) totalBytes += r.length;
        System.out.println("평균 레코드 크기: " + (totalBytes / allRecords.size()) + " bytes");
        System.out.println();

        // 사전에 GZIP 압축된 버전 준비
        List<byte[]> compressedRecords = new ArrayList<byte[]>();
        for (byte[] r : allRecords) {
            compressedRecords.add(CompressUtil.doZip(r));
        }

        // === Benchmark 1: Raw read (baseline) ===
        // 시뮬레이션: byte[]를 그냥 반환 (복사만)
        for (int w = 0; w < WARMUP; w++) {
            for (byte[] r : allRecords) {
                byte[] copy = Arrays.copyOf(r, r.length);
                if (copy.length < 0) throw new RuntimeException(); // prevent dead code elimination
            }
        }
        long rawStart = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            for (byte[] r : allRecords) {
                byte[] copy = Arrays.copyOf(r, r.length);
                if (copy.length < 0) throw new RuntimeException();
            }
        }
        long rawTotal = System.nanoTime() - rawStart;
        long rawPerRecord = rawTotal / (ITERATIONS * allRecords.size());

        // === Benchmark 2: GZIP decompress ===
        for (int w = 0; w < WARMUP; w++) {
            for (byte[] c : compressedRecords) {
                byte[] decompressed = CompressUtil.unZip(c);
                if (decompressed.length < 0) throw new RuntimeException();
            }
        }
        long gzipStart = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            for (byte[] c : compressedRecords) {
                byte[] decompressed = CompressUtil.unZip(c);
                if (decompressed.length < 0) throw new RuntimeException();
            }
        }
        long gzipTotal = System.nanoTime() - gzipStart;
        long gzipPerRecord = gzipTotal / (ITERATIONS * allRecords.size());

        // === Benchmark 3: Version byte check + conditional decompress ===
        // 실제 적용 시나리오: version byte 확인 → 0x01이면 decompress
        List<byte[]> wrappedRecords = new ArrayList<byte[]>();
        for (byte[] c : compressedRecords) {
            byte[] wrapped = new byte[1 + c.length];
            wrapped[0] = 0x01;
            System.arraycopy(c, 0, wrapped, 1, c.length);
            wrappedRecords.add(wrapped);
        }

        for (int w = 0; w < WARMUP; w++) {
            for (byte[] wr : wrappedRecords) {
                byte[] result = decompressField(wr);
                if (result.length < 0) throw new RuntimeException();
            }
        }
        long condStart = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            for (byte[] wr : wrappedRecords) {
                byte[] result = decompressField(wr);
                if (result.length < 0) throw new RuntimeException();
            }
        }
        long condTotal = System.nanoTime() - condStart;
        long condPerRecord = condTotal / (ITERATIONS * allRecords.size());

        // === 결과 출력 ===
        long overhead = condPerRecord - rawPerRecord;
        double overheadPct = rawPerRecord > 0 ? (double) overhead / rawPerRecord * 100 : 0;

        System.out.println(String.format("%-35s %10s %12s", "방법", "레코드당", "총 시간"));
        System.out.println(new String(new char[60]).replace('\0', '-'));
        System.out.println(String.format("%-35s %8d ns %10.1f ms",
                "Raw read (현재, 기준선)", rawPerRecord, rawTotal / 1e6));
        System.out.println(String.format("%-35s %8d ns %10.1f ms",
                "GZIP decompress만", gzipPerRecord, gzipTotal / 1e6));
        System.out.println(String.format("%-35s %8d ns %10.1f ms",
                "Version check + decompress (제안)", condPerRecord, condTotal / 1e6));

        System.out.println();
        System.out.println(String.format("오버헤드: +%d ns/record (+%.1f%%)", overhead, overheadPct));
        System.out.println();

        // API 관점 분석
        // 일반적인 API 조회: 100~500 레코드 읽기
        int[] querySizes = {10, 50, 100, 500, 1000};
        System.out.println(String.format("%-20s %12s %12s %12s",
                "API 조회 레코드수", "현재(Raw)", "제안(Decomp)", "추가 지연"));
        System.out.println(new String(new char[60]).replace('\0', '-'));
        for (int qs : querySizes) {
            double rawMs = rawPerRecord * qs / 1e6;
            double condMs = condPerRecord * qs / 1e6;
            double diffMs = (condPerRecord - rawPerRecord) * qs / 1e6;
            System.out.println(String.format("%-20d %10.3f ms %10.3f ms %10.3f ms",
                    qs, rawMs, condMs, diffMs));
        }

        System.out.println();
        System.out.println("=== 해석 ===");
        if (overhead < 1000) {
            System.out.println("오버헤드 < 1us/record → API 응답에 실질적 영향 없음");
        } else if (overhead < 10000) {
            System.out.println("오버헤드 1~10us/record → 대량 조회(1000+)에서 수ms 추가, 대부분 허용 가능");
        } else {
            System.out.println("오버헤드 > 10us/record → 최적화 필요 (캐싱, 비동기 등)");
        }
    }

    static byte[] decompressField(byte[] raw) {
        if (raw == null || raw.length < 2) return raw;
        if (raw[0] == 0x01) {
            byte[] compressed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, compressed, 0, compressed.length);
            return CompressUtil.unZip(compressed);
        }
        return raw;
    }

    static List<byte[]> parseInt3Records(byte[] data) {
        List<byte[]> records = new ArrayList<byte[]>();
        int pos = 0;
        while (pos + 3 <= data.length) {
            int len = ((data[pos] & 0xFF) << 16) | ((data[pos + 1] & 0xFF) << 8) | (data[pos + 2] & 0xFF);
            pos += 3;
            if (len == 0x7FFFFF) {
                if (pos + 4 > data.length) break;
                len = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                        | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                pos += 4;
            }
            if (len < 0 || pos + len > data.length) break;
            byte[] record = new byte[len];
            System.arraycopy(data, pos, record, 0, len);
            pos += len;
            records.add(record);
        }
        return records;
    }
}
