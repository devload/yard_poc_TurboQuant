package io.whatap.turboquant.storage.benchmark;

import java.util.*;

/**
 * 사용자 아이디어: 회전하지 말고, 실제 데이터 분포에 맞는 코드북을 만들자.
 *
 * 비교:
 * 1. 균등 코드북: 0~100을 16등분 (0, 6.25, 12.5, ...)
 * 2. 데이터 적응형 코드북: 실제 CPU 값 분포에서 Lloyd-Max 학습
 * 3. 대표값 코드북: 모니터링에서 자주 나오는 값 기반 (0,10,20,...,100)
 * 4. TurboQuant (회전+Beta 코드북)
 */
public class AdaptiveCodebookBench {

    public static void main(String[] args) {
        System.out.println("=== 데이터 적응형 코드북 vs TurboQuant ===");
        System.out.println();

        // 실제 모니터링 데이터 시뮬레이션
        Random rng = new Random(42);

        // CPU: 대부분 30~80%, 가끔 스파이크
        float[] cpuData = generateCpuData(rng, 10000);
        // TPS: 대부분 50~500, 가끔 스파이크 1000+
        float[] tpsData = generateTpsData(rng, 10000);
        // Response Time: long-tail 분포
        float[] rtData = generateRtData(rng, 10000);
        // GC Count: 0~20 정수 위주
        float[] gcData = generateGcData(rng, 10000);

        Object[][] datasets = {
            {"CPU (%)", cpuData, 0f, 100f},
            {"TPS", tpsData, 0f, 5000f},
            {"Response Time (ms)", rtData, 0f, 30000f},
            {"GC Count", gcData, 0f, 50f},
        };

        for (int bits : new int[]{4, 6, 8}) {
            System.out.println("=== " + bits + "-bit (" + (1 << bits) + "개 코드북) ===");
            System.out.println(String.format("%-25s %12s %12s %12s %12s",
                    "메트릭", "균등", "적응형", "대표값", "TQ(회전)"));
            System.out.println(new String(new char[76]).replace('\0', '-'));

            for (Object[] ds : datasets) {
                String name = (String) ds[0];
                float[] data = (float[]) ds[1];
                float min = (float) ds[2];
                float max = (float) ds[3];

                double errUniform = testUniformCodebook(data, min, max, bits);
                double errAdaptive = testAdaptiveCodebook(data, bits);
                double errRepresentative = testRepresentativeCodebook(data, min, max, bits);
                double errTQ = testTurboQuant(data, min, max, bits);

                System.out.println(String.format("%-25s %11.2f%% %11.2f%% %11.2f%% %11.2f%%",
                        name, errUniform, errAdaptive, errRepresentative, errTQ));
            }
            System.out.println();
        }

        // 대시보드 표시 비교 (적응형 4-bit)
        System.out.println("=== 대시보드 표시: 적응형 4-bit (16개 코드북) ===");
        System.out.println();

        for (Object[] ds : datasets) {
            String name = (String) ds[0];
            float[] data = (float[]) ds[1];

            // 적응형 코드북 학습
            float[] codebook = buildAdaptiveCodebook(data, 4);

            System.out.println("  " + name + " (코드북: " + Arrays.toString(roundArr(codebook)) + ")");
            Random rng2 = new Random(123);
            for (int i = 0; i < 5; i++) {
                float val = data[rng2.nextInt(data.length)];
                float restored = quantize(val, codebook);
                String diff = Math.abs(val - restored) < 0.5 ? "" :
                        String.format(" (차이: %+.1f)", val - restored);
                System.out.println(String.format("    원본: %8.1f → 복원: %8.1f%s", val, restored, diff));
            }
            System.out.println();
        }

        // 압축률 계산
        System.out.println("=== 압축률 비교 ===");
        System.out.println(String.format("%-25s %10s %10s %10s", "방법", "비트/값", "크기(5값)", "압축률"));
        System.out.println(new String(new char[58]).replace('\0', '-'));
        System.out.println(String.format("%-25s %10s %9dB %9.1fx", "float32 (현재)", "32bit", 20, 1.0));
        System.out.println(String.format("%-25s %10s %9dB %9.1fx", "적응형 4-bit", "4bit+CB", 3+16, 20.0/19)); // codebook overhead
        System.out.println(String.format("%-25s %10s %9dB %9.1fx", "적응형 4-bit (공유CB)", "4bit", 3, 20.0/3));
        System.out.println(String.format("%-25s %10s %9dB %9.1fx", "적응형 8-bit", "8bit", 5, 20.0/5));
        System.out.println(String.format("%-25s %10s %9dB %9.1fx", "스칼라 8-bit (균등)", "8bit", 5, 20.0/5));
        System.out.println(String.format("%-25s %10s %9dB %9.1fx", "TQ 4-bit", "4bit+norm", 7, 20.0/7));

        System.out.println();
        System.out.println("=== 핵심 결론 ===");
        System.out.println("적응형 코드북 = 데이터 분포에 맞는 16개 대표값을 미리 학습");
        System.out.println("회전 없이 직접 매핑 → 오차가 교차 전파되지 않음");
        System.out.println("메트릭 유형별 코드북을 서버 설정에 저장하면 오버헤드 없음");
    }

    // 균등 코드북 (0~max를 N등분)
    static double testUniformCodebook(float[] data, float min, float max, int bits) {
        int k = 1 << bits;
        float[] codebook = new float[k];
        for (int i = 0; i < k; i++) codebook[i] = min + (max - min) * (i + 0.5f) / k;
        return measureError(data, codebook);
    }

    // 적응형 코드북 (실제 데이터에서 Lloyd-Max 학습)
    static double testAdaptiveCodebook(float[] data, int bits) {
        float[] codebook = buildAdaptiveCodebook(data, bits);
        return measureError(data, codebook);
    }

    static float[] buildAdaptiveCodebook(float[] data, int bits) {
        int k = 1 << bits;
        // K-means 스타일 초기화: 데이터에서 균등 퍼센타일로 초기 중심
        float[] sorted = data.clone();
        Arrays.sort(sorted);
        float[] codebook = new float[k];
        for (int i = 0; i < k; i++) {
            int idx = (int)((i + 0.5) * sorted.length / k);
            idx = Math.min(idx, sorted.length - 1);
            codebook[i] = sorted[idx];
        }

        // Lloyd-Max 반복 (100회)
        for (int iter = 0; iter < 100; iter++) {
            double[] sums = new double[k];
            int[] counts = new int[k];

            for (float v : data) {
                int best = nearest(v, codebook);
                sums[best] += v;
                counts[best]++;
            }

            boolean converged = true;
            for (int i = 0; i < k; i++) {
                if (counts[i] > 0) {
                    float newVal = (float)(sums[i] / counts[i]);
                    if (Math.abs(newVal - codebook[i]) > 0.001f) converged = false;
                    codebook[i] = newVal;
                }
            }
            if (converged) break;
        }
        Arrays.sort(codebook);
        return codebook;
    }

    // 대표값 코드북 (모니터링 대표값 기반)
    static double testRepresentativeCodebook(float[] data, float min, float max, int bits) {
        int k = 1 << bits;
        // 데이터 범위의 "둥근 숫자"로 코드북 생성
        float range = max - min;
        float step = range / k;
        // 가장 가까운 "깔끔한 숫자"로 반올림
        float niceStep = niceNumber(step);
        float[] codebook = new float[k];
        float start = (float)(Math.ceil(min / niceStep) * niceStep);
        for (int i = 0; i < k; i++) {
            codebook[i] = start + i * niceStep;
            if (codebook[i] > max) codebook[i] = max;
        }
        return measureError(data, codebook);
    }

    // TurboQuant (회전 포함)
    static double testTurboQuant(float[] data, float min, float max, int bits) {
        io.whatap.turboquant.core.TurboQuantizer tq =
                new io.whatap.turboquant.core.TurboQuantizer(bits, 1, 42L);
        double totalRel = 0;
        int count = 0;
        float range = max - min;
        if (range < 0.001f) range = 1f;
        for (float v : data) {
            float norm = (v - min) / range;
            float[] rest = tq.decompress(tq.compress(new float[]{norm}));
            float restored = rest[0] * range + min;
            if (Math.abs(v) > 0.01f) {
                totalRel += Math.abs(v - restored) / Math.abs(v) * 100;
                count++;
            }
        }
        return count > 0 ? totalRel / count : 0;
    }

    static double measureError(float[] data, float[] codebook) {
        double totalRel = 0;
        int count = 0;
        for (float v : data) {
            float restored = quantize(v, codebook);
            if (Math.abs(v) > 0.01f) {
                totalRel += Math.abs(v - restored) / Math.abs(v) * 100;
                count++;
            }
        }
        return count > 0 ? totalRel / count : 0;
    }

    static float quantize(float v, float[] codebook) {
        int best = nearest(v, codebook);
        return codebook[best];
    }

    static int nearest(float v, float[] codebook) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < codebook.length; i++) {
            float d = Math.abs(v - codebook[i]);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    static float niceNumber(float v) {
        float pow = (float)Math.pow(10, Math.floor(Math.log10(v)));
        float frac = v / pow;
        if (frac < 1.5) return pow;
        if (frac < 3) return 2 * pow;
        if (frac < 7) return 5 * pow;
        return 10 * pow;
    }

    static float[] generateCpuData(Random rng, int n) {
        float[] data = new float[n];
        for (int i = 0; i < n; i++) {
            double v = 55 + rng.nextGaussian() * 15; // 평균 55, 표준편차 15
            if (rng.nextDouble() < 0.05) v = 85 + rng.nextDouble() * 15; // 5% 스파이크
            data[i] = (float)Math.max(0, Math.min(100, v));
        }
        return data;
    }

    static float[] generateTpsData(Random rng, int n) {
        float[] data = new float[n];
        for (int i = 0; i < n; i++) {
            double v = 200 + rng.nextGaussian() * 80;
            if (rng.nextDouble() < 0.03) v = 800 + rng.nextDouble() * 2000; // 3% 스파이크
            data[i] = (float)Math.max(0, Math.min(5000, v));
        }
        return data;
    }

    static float[] generateRtData(Random rng, int n) {
        float[] data = new float[n];
        for (int i = 0; i < n; i++) {
            double v = Math.exp(5.5 + rng.nextGaussian() * 0.8); // log-normal
            data[i] = (float)Math.max(0, Math.min(30000, v));
        }
        return data;
    }

    static float[] generateGcData(Random rng, int n) {
        float[] data = new float[n];
        for (int i = 0; i < n; i++) {
            data[i] = (float)Math.max(0, Math.min(50, rng.nextInt(15) + rng.nextGaussian() * 2));
        }
        return data;
    }

    static float[] roundArr(float[] arr) {
        float[] r = new float[arr.length];
        for (int i = 0; i < arr.length; i++) r[i] = Math.round(arr[i] * 10) / 10f;
        return r;
    }
}
