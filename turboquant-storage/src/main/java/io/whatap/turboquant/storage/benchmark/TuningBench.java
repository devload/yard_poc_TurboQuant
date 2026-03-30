package io.whatap.turboquant.storage.benchmark;

import io.whatap.turboquant.core.TurboQuantizer;
import io.whatap.turboquant.core.LloydMaxCodebook;

import java.util.Random;

/**
 * TurboQuant 정확도 튜닝 방법 비교.
 *
 * 문제: 현재 dim=5에서 CPU 52.7 → 36.8 (오차 ~19%)
 *
 * 튜닝 방법:
 * 1. 비트 수 증가: 4bit → 6bit → 8bit
 * 2. 필드별 정규화 [0,1] 후 TurboQuant (min/max 별도 저장)
 * 3. 같은 필드끼리 시간축으로 묶기 (dim=100, 같은 스케일)
 * 4. 회전 제거: codebook만 사용 (per-field 독립)
 * 5. 델타 인코딩: 시간 차분 후 양자화 (범위 축소)
 * 6. 조합: 정규화 + 높은 비트 + 시간 배치
 */
public class TuningBench {

    static final int SAMPLES = 5000;

    public static void main(String[] args) {
        System.out.println("=== TurboQuant 알고리즘 튜닝 벤치마크 ===");
        System.out.println();

        // CPU 메트릭으로 대표 테스트 (range 0~100, mean 40, std 20)
        float min = 0, max = 100, mean = 40, std = 20;

        System.out.println("[CPU % 메트릭 기준, " + SAMPLES + "샘플]");
        System.out.println();
        System.out.println(String.format("%-45s %8s %8s %8s %8s",
                "방법", "상대오차", "최대오차", "크기(B)", "압축률"));
        System.out.println(new String(new char[82]).replace('\0', '-'));

        // 기준선
        run("현재: TQ 4-bit, dim=5", () -> testTQ(4, 5, mean, std, min, max, false), 20);
        run("현재: TQ 4-bit, dim=5, 정규화", () -> testTQ(4, 5, mean, std, min, max, true), 20);

        // 튜닝 1: 비트 수 증가
        run("튜닝1: TQ 6-bit, dim=5", () -> testTQ(6, 5, mean, std, min, max, false), 20);
        run("튜닝1: TQ 8-bit, dim=5", () -> testTQ(8, 5, mean, std, min, max, false), 20);
        run("튜닝1: TQ 8-bit, dim=5, 정규화", () -> testTQ(8, 5, mean, std, min, max, true), 20);

        // 튜닝 2: 같은 필드 시간축 묶기 (cpu 100개 타임스텝 = dim=100)
        run("튜닝2: TQ 4-bit, dim=100 (시간배치)", () -> testTQTimeBatch(4, 100, mean, std, min, max, false), 400);
        run("튜닝2: TQ 4-bit, dim=100, 정규화", () -> testTQTimeBatch(4, 100, mean, std, min, max, true), 400);
        run("튜닝2: TQ 8-bit, dim=100, 정규화", () -> testTQTimeBatch(8, 100, mean, std, min, max, true), 400);

        // 튜닝 3: 회전 제거 (codebook만, per-value 독립 양자화)
        run("튜닝3: codebook만 4-bit (회전X)", () -> testCodebookOnly(4, mean, std, min, max), 20);
        run("튜닝3: codebook만 8-bit (회전X)", () -> testCodebookOnly(8, mean, std, min, max), 20);

        // 튜닝 4: 델타 인코딩 + TQ
        run("튜닝4: 델타 + TQ 4-bit, dim=99", () -> testDeltaTQ(4, 100, mean, std, min, max), 400);
        run("튜닝4: 델타 + TQ 8-bit, dim=99", () -> testDeltaTQ(8, 100, mean, std, min, max), 400);

        // 튜닝 5: 최적 조합 (정규화 + 시간배치 + 6-bit)
        run("튜닝5: 정규화+시간배치+6bit dim=100", () -> testTQTimeBatch(6, 100, mean, std, min, max, true), 400);

        // 비교: 단순 스칼라
        run("[참고] 스칼라 8-bit (형변환)", () -> testScalar8(mean, std, min, max), 20);

        // 전체 메트릭 한번에
        System.out.println();
        System.out.println("=== 최적 조합으로 전체 메트릭 테스트 ===");
        System.out.println("방법: 필드별 정규화 + 시간배치(dim=100) + TQ 8-bit");
        System.out.println();

        Object[][] metrics = {
            {"CPU (%)",           0f, 100f,    40f, 20f},
            {"Memory (%)",        0f, 100f,    65f, 10f},
            {"TPS",               0f, 10000f,  500f, 200f},
            {"Response Time(ms)", 0f, 30000f,  1500f, 800f},
            {"Heap Used (MB)",    0f, 4096f,   2000f, 500f},
            {"GC Count",          0f, 100f,    10f, 5f},
            {"Error Count",       0f, 50f,     3f, 2f},
            {"Active Service",    0f, 200f,    15f, 8f},
        };

        System.out.println(String.format("%-22s %10s %10s %10s",
                "메트릭", "상대오차", "최대오차", "대시보드"));
        System.out.println(new String(new char[55]).replace('\0', '-'));

        Random rng = new Random(42);
        for (Object[] m : metrics) {
            String name = (String) m[0];
            float mi = (float) m[1], ma = (float) m[2];
            float me = (float) m[3], st = (float) m[4];

            Result r = testTQTimeBatch(8, 100, me, st, mi, ma, true);

            // 대시보드 샘플
            TurboQuantizer tq = new TurboQuantizer(8, 100, 42L);
            float[] vals = new float[100];
            Random rng2 = new Random(123);
            for (int i = 0; i < 100; i++) {
                vals[i] = (float)((me + rng2.nextGaussian() * st - mi) / (ma - mi));
                vals[i] = Math.max(0, Math.min(1, vals[i]));
            }
            float[] restored = tq.decompress(tq.compress(vals));
            float origVal = vals[50] * (ma - mi) + mi;
            float restVal = restored[50] * (ma - mi) + mi;
            String dashboard = String.format("%.1f→%.1f", origVal, restVal);

            System.out.println(String.format("%-22s %9.2f%% %9.2f %10s",
                    name, r.avgRelErr, r.maxAbsErr, dashboard));
        }
    }

    static void run(String name, java.util.function.Supplier<Result> test, int rawBytes) {
        Result r = test.get();
        double ratio = rawBytes > 0 ? (double) rawBytes / r.compressedBytes : 0;
        System.out.println(String.format("%-45s %7.2f%% %8.2f %7dB %7.1fx",
                name, r.avgRelErr, r.maxAbsErr, r.compressedBytes, ratio));
    }

    // TQ with dim=5 (single MetricValue)
    static Result testTQ(int bits, int dim, float mean, float std, float min, float max, boolean normalize) {
        Random rng = new Random(42);
        TurboQuantizer tq = new TurboQuantizer(bits, dim, 42L);
        double totalRel = 0, maxAbs = 0;
        int count = 0;

        for (int i = 0; i < SAMPLES; i++) {
            float val = clamp((float)(mean + rng.nextGaussian() * std), min, max);
            float[] orig = new float[dim];
            for (int d = 0; d < dim; d++) {
                float v = clamp((float)(mean + rng.nextGaussian() * std), min, max);
                orig[d] = normalize ? (v - min) / (max - min) : v;
            }
            float[] rest = tq.decompress(tq.compress(orig));
            for (int d = 0; d < dim; d++) {
                float o = normalize ? orig[d] * (max - min) + min : orig[d];
                float r = normalize ? rest[d] * (max - min) + min : rest[d];
                float absErr = Math.abs(o - r);
                if (absErr > maxAbs) maxAbs = absErr;
                if (Math.abs(o) > 0.01) { totalRel += absErr / Math.abs(o) * 100; count++; }
            }
        }
        int compressed = tq.compressedSize();
        if (normalize) compressed += 8; // min/max overhead
        return new Result(count > 0 ? totalRel / count : 0, maxAbs, compressed);
    }

    // TQ time batch: same field across time (dim=timeSteps)
    static Result testTQTimeBatch(int bits, int timeSteps, float mean, float std, float min, float max, boolean normalize) {
        Random rng = new Random(42);
        TurboQuantizer tq = new TurboQuantizer(bits, timeSteps, 42L);
        double totalRel = 0, maxAbs = 0;
        int count = 0;

        for (int block = 0; block < SAMPLES / timeSteps; block++) {
            float[] orig = new float[timeSteps];
            for (int t = 0; t < timeSteps; t++) {
                float v = clamp((float)(mean + rng.nextGaussian() * std + 5 * Math.sin(2 * Math.PI * t / 60.0)), min, max);
                orig[t] = normalize ? (v - min) / (max - min) : v;
            }
            float[] rest = tq.decompress(tq.compress(orig));
            for (int t = 0; t < timeSteps; t++) {
                float o = normalize ? orig[t] * (max - min) + min : orig[t];
                float r = normalize ? rest[t] * (max - min) + min : rest[t];
                float absErr = Math.abs(o - r);
                if (absErr > maxAbs) maxAbs = absErr;
                if (Math.abs(o) > 0.01) { totalRel += absErr / Math.abs(o) * 100; count++; }
            }
        }
        int compressed = tq.compressedSize();
        if (normalize) compressed += 8;
        return new Result(count > 0 ? totalRel / count : 0, maxAbs, compressed);
    }

    // Codebook only (no rotation)
    static Result testCodebookOnly(int bits, float mean, float std, float min, float max) {
        Random rng = new Random(42);
        int codebookSize = 1 << bits;
        // uniform codebook across [0, 1]
        float[] codebook = new float[codebookSize];
        for (int i = 0; i < codebookSize; i++) {
            codebook[i] = (i + 0.5f) / codebookSize;
        }

        double totalRel = 0, maxAbs = 0;
        int count = 0;
        int dim = 5;

        for (int i = 0; i < SAMPLES; i++) {
            for (int d = 0; d < dim; d++) {
                float val = clamp((float)(mean + rng.nextGaussian() * std), min, max);
                float normalized = (val - min) / (max - min);
                // nearest centroid
                int best = 0;
                float bestDist = Float.MAX_VALUE;
                for (int j = 0; j < codebookSize; j++) {
                    float dist = Math.abs(normalized - codebook[j]);
                    if (dist < bestDist) { bestDist = dist; best = j; }
                }
                float restored = codebook[best] * (max - min) + min;
                float absErr = Math.abs(val - restored);
                if (absErr > maxAbs) maxAbs = absErr;
                if (Math.abs(val) > 0.01) { totalRel += absErr / Math.abs(val) * 100; count++; }
            }
        }
        // bits per value * 5 fields / 8 + min/max overhead
        int compressed = (bits * dim + 7) / 8 + 8 + 4;
        return new Result(count > 0 ? totalRel / count : 0, maxAbs, compressed);
    }

    // Delta encoding + TQ
    static Result testDeltaTQ(int bits, int timeSteps, float mean, float std, float min, float max) {
        Random rng = new Random(42);
        int deltaDim = timeSteps - 1;
        TurboQuantizer tq = new TurboQuantizer(bits, deltaDim, 42L);
        double totalRel = 0, maxAbs = 0;
        int count = 0;

        for (int block = 0; block < SAMPLES / timeSteps; block++) {
            float[] orig = new float[timeSteps];
            for (int t = 0; t < timeSteps; t++) {
                orig[t] = clamp((float)(mean + rng.nextGaussian() * std + 5 * Math.sin(2 * Math.PI * t / 60.0)), min, max);
            }
            // delta encode
            float first = orig[0];
            float[] deltas = new float[deltaDim];
            float deltaMin = Float.MAX_VALUE, deltaMax = -Float.MAX_VALUE;
            for (int t = 0; t < deltaDim; t++) {
                deltas[t] = orig[t + 1] - orig[t];
                if (deltas[t] < deltaMin) deltaMin = deltas[t];
                if (deltas[t] > deltaMax) deltaMax = deltas[t];
            }
            // normalize deltas
            float deltaRange = deltaMax - deltaMin;
            if (deltaRange < 1e-6f) deltaRange = 1f;
            float[] normDeltas = new float[deltaDim];
            for (int t = 0; t < deltaDim; t++) {
                normDeltas[t] = (deltas[t] - deltaMin) / deltaRange;
            }

            float[] restNormDeltas = tq.decompress(tq.compress(normDeltas));

            // reconstruct
            float[] restored = new float[timeSteps];
            restored[0] = first;
            for (int t = 0; t < deltaDim; t++) {
                float restDelta = restNormDeltas[t] * deltaRange + deltaMin;
                restored[t + 1] = restored[t] + restDelta;
            }

            for (int t = 0; t < timeSteps; t++) {
                float absErr = Math.abs(orig[t] - restored[t]);
                if (absErr > maxAbs) maxAbs = absErr;
                if (Math.abs(orig[t]) > 0.01) { totalRel += absErr / Math.abs(orig[t]) * 100; count++; }
            }
        }
        int compressed = tq.compressedSize() + 4 + 8; // first value + delta min/max
        return new Result(count > 0 ? totalRel / count : 0, maxAbs, compressed);
    }

    // Scalar 8-bit (참고)
    static Result testScalar8(float mean, float std, float min, float max) {
        Random rng = new Random(42);
        double totalRel = 0, maxAbs = 0;
        int count = 0;
        for (int i = 0; i < SAMPLES * 5; i++) {
            float val = clamp((float)(mean + rng.nextGaussian() * std), min, max);
            int q = Math.round((val - min) / (max - min) * 255);
            q = Math.max(0, Math.min(255, q));
            float restored = min + (q / 255f) * (max - min);
            float absErr = Math.abs(val - restored);
            if (absErr > maxAbs) maxAbs = absErr;
            if (Math.abs(val) > 0.01) { totalRel += absErr / Math.abs(val) * 100; count++; }
        }
        return new Result(count > 0 ? totalRel / count : 0, maxAbs, 5);
    }

    static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

    static class Result {
        double avgRelErr, maxAbsErr;
        int compressedBytes;
        Result(double avgRelErr, double maxAbsErr, int compressedBytes) {
            this.avgRelErr = avgRelErr;
            this.maxAbsErr = maxAbsErr;
            this.compressedBytes = compressedBytes;
        }
    }
}
