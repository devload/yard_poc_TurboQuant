package io.whatap.turboquant.storage.benchmark;

import io.whatap.turboquant.core.TurboQuantizer;

import java.util.*;

/**
 * TurboQuant dequantize 속도 측정.
 * GZIP이 아닌 TurboQuant의 실제 복원 속도를 측정.
 *
 * TurboQuant decompress:
 *   1. bit unpack (비트 연산) - O(n)
 *   2. codebook lookup (배열 인덱싱) - O(n)
 *   3. inverse rotate (행렬-벡터 곱) - O(n²)
 *   4. norm scale - O(n)
 *
 * vs Raw read: byte[] 그대로 반환
 */
public class TurboQuantLatencyBench {

    static final int WARMUP = 500;
    static final int ITERATIONS = 5000;

    public static void main(String[] args) {
        System.out.println("=== TurboQuant Dequantize 속도 벤치마크 ===");
        System.out.println("Warmup: " + WARMUP + ", Measure: " + ITERATIONS);
        System.out.println();

        // 실제 MetricValue 크기별 테스트
        // .dat2 레코드는 필드 수에 따라 다양: 5~50 floats
        int[] dimensions = {5, 10, 20, 50, 100};
        int numBits = 4;

        System.out.println(String.format("%-8s %12s %12s %12s %12s %10s",
                "차원", "Raw(ns)", "TQ압축(ns)", "TQ복원(ns)", "오버헤드(ns)", "오버헤드"));
        System.out.println(new String(new char[72]).replace('\0', '-'));

        Random rng = new Random(42);

        for (int dim : dimensions) {
            TurboQuantizer tq = new TurboQuantizer(numBits, dim, 42L);

            // 테스트 데이터 생성 (실제 MetricValue 범위: cpu 0~100, mem 0~100, tps 0~10000 등)
            float[] original = new float[dim];
            for (int i = 0; i < dim; i++) {
                original[i] = rng.nextFloat() * 1000; // 0~1000 범위
            }

            // 압축
            byte[] compressed = tq.compress(original);
            byte[] rawBytes = new byte[dim * 4]; // float32 raw

            // Warmup - Raw
            for (int w = 0; w < WARMUP; w++) {
                byte[] copy = Arrays.copyOf(rawBytes, rawBytes.length);
                if (copy.length < 0) throw new RuntimeException();
            }

            // Measure - Raw read
            long rawStart = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                byte[] copy = Arrays.copyOf(rawBytes, rawBytes.length);
                if (copy.length < 0) throw new RuntimeException();
            }
            long rawNs = (System.nanoTime() - rawStart) / ITERATIONS;

            // Warmup - TQ compress
            for (int w = 0; w < WARMUP; w++) {
                byte[] c = tq.compress(original);
                if (c.length < 0) throw new RuntimeException();
            }

            // Measure - TQ compress
            long compStart = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                byte[] c = tq.compress(original);
                if (c.length < 0) throw new RuntimeException();
            }
            long compNs = (System.nanoTime() - compStart) / ITERATIONS;

            // Warmup - TQ decompress
            for (int w = 0; w < WARMUP; w++) {
                float[] r = tq.decompress(compressed);
                if (r.length < 0) throw new RuntimeException();
            }

            // Measure - TQ decompress (이게 API 읽기 경로)
            long decompStart = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                float[] r = tq.decompress(compressed);
                if (r.length < 0) throw new RuntimeException();
            }
            long decompNs = (System.nanoTime() - decompStart) / ITERATIONS;

            long overhead = decompNs - rawNs;
            String overheadStr;
            if (overhead < 1000) {
                overheadStr = String.format("+%dns", overhead);
            } else {
                overheadStr = String.format("+%.1fus", overhead / 1000.0);
            }

            System.out.println(String.format("%-8d %10d ns %10d ns %10d ns %10d ns %10s",
                    dim, rawNs, compNs, decompNs, overhead, overheadStr));
        }

        // API 시나리오 시뮬레이션
        System.out.println();
        System.out.println("=== API 조회 시나리오 (dim=20, 4-bit) ===");
        int scenarioDim = 20; // 일반적인 TagCountPack field 수
        TurboQuantizer tq20 = new TurboQuantizer(numBits, scenarioDim, 42L);

        float[] testVec = new float[scenarioDim];
        for (int i = 0; i < scenarioDim; i++) testVec[i] = rng.nextFloat() * 500;
        byte[] testCompressed = tq20.compress(testVec);

        // Warmup
        for (int w = 0; w < 1000; w++) {
            tq20.decompress(testCompressed);
        }

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < 100000; i++) {
            tq20.decompress(testCompressed);
        }
        long perRecord = (System.nanoTime() - start) / 100000;

        int[] querySizes = {10, 50, 100, 500, 1000};
        System.out.println(String.format("TQ decompress: %d ns/record (dim=%d, %d-bit)", perRecord, scenarioDim, numBits));
        System.out.println();
        System.out.println(String.format("%-20s %12s %12s",
                "조회 레코드 수", "TQ 추가 지연", "vs 전체 응답(~100ms)"));
        System.out.println(new String(new char[48]).replace('\0', '-'));
        for (int qs : querySizes) {
            double delayUs = perRecord * qs / 1000.0;
            double delayMs = delayUs / 1000.0;
            double pctOf100ms = delayMs / 100.0 * 100;
            System.out.println(String.format("%-20d %10.1f us %10.2f%% of 100ms",
                    qs, delayUs, pctOf100ms));
        }
    }
}
