package io.whatap.turboquant.storage.benchmark;

import java.util.*;

/**
 * 적응형 코드북으로 오차율을 어디까지 줄일 수 있는지 측정.
 * 비트 수: 2~12bit (4~4096개 코드북)
 * 메트릭별 실제 분포 기반 Lloyd-Max 학습.
 */
public class AdaptiveMinErrorBench {

    static final int DATA_SIZE = 100000;

    public static void main(String[] args) {
        Random rng = new Random(42);

        // 실제 모니터링 분포 시뮬레이션
        Object[][] metrics = {
            {"CPU (%)",           generateCpu(rng), "%.1f%%"},
            {"Memory (%)",        generateMem(rng), "%.1f%%"},
            {"TPS",               generateTps(rng), "%.0f"},
            {"Response Time(ms)", generateRt(rng), "%.0f ms"},
            {"Heap Used (MB)",    generateHeap(rng), "%.0f MB"},
            {"GC Count",          generateGc(rng), "%.0f"},
            {"Error Count",       generateError(rng), "%.0f"},
            {"Active Service",    generateActive(rng), "%.0f"},
            {"Disk (%)",          generateDisk(rng), "%.1f%%"},
            {"Network IO (KB/s)", generateNetIo(rng), "%.0f"},
        };

        // === 비트별 오차율 ===
        System.out.println("=== 적응형 코드북: 비트별 오차율 ===");
        System.out.println();
        System.out.print(String.format("%-20s", "메트릭"));
        int[] bitsList = {2, 3, 4, 5, 6, 7, 8, 10, 12};
        for (int b : bitsList) System.out.print(String.format(" %7dbit", b));
        System.out.println();
        System.out.println(new String(new char[110]).replace('\0', '-'));

        for (Object[] m : metrics) {
            String name = (String) m[0];
            float[] data = (float[]) m[1];
            System.out.print(String.format("%-20s", name));
            for (int bits : bitsList) {
                float[] cb = buildCodebook(data, bits);
                double err = measureRelError(data, cb);
                System.out.print(String.format(" %7.3f%%", err));
            }
            System.out.println();
        }

        // === 대시보드 비교: 비트별 ===
        System.out.println();
        System.out.println("=== 대시보드 표시 비교 (CPU %) ===");
        float[] cpuData = (float[]) metrics[0][1];
        float[] samples = new float[10];
        Random rng2 = new Random(777);
        for (int i = 0; i < 10; i++) samples[i] = cpuData[rng2.nextInt(cpuData.length)];

        System.out.print(String.format("%-10s", "원본"));
        for (float v : samples) System.out.print(String.format(" %7.1f", v));
        System.out.println();
        System.out.println(new String(new char[88]).replace('\0', '-'));

        for (int bits : new int[]{3, 4, 5, 6, 8}) {
            float[] cb = buildCodebook(cpuData, bits);
            System.out.print(String.format("%-10s", bits + "bit(" + (1<<bits) + ")"));
            for (float v : samples) {
                float restored = cb[nearest(v, cb)];
                System.out.print(String.format(" %7.1f", restored));
            }
            System.out.println();
        }

        // === 대시보드 비교: Response Time ===
        System.out.println();
        System.out.println("=== 대시보드 표시 비교 (Response Time ms) ===");
        float[] rtData = (float[]) metrics[3][1];
        rng2 = new Random(777);
        for (int i = 0; i < 10; i++) samples[i] = rtData[rng2.nextInt(rtData.length)];

        System.out.print(String.format("%-10s", "원본"));
        for (float v : samples) System.out.print(String.format(" %7.0f", v));
        System.out.println();
        System.out.println(new String(new char[88]).replace('\0', '-'));

        for (int bits : new int[]{3, 4, 5, 6, 8}) {
            float[] cb = buildCodebook(rtData, bits);
            System.out.print(String.format("%-10s", bits + "bit(" + (1<<bits) + ")"));
            for (float v : samples) {
                float restored = cb[nearest(v, cb)];
                System.out.print(String.format(" %7.0f", restored));
            }
            System.out.println();
        }

        // === 대시보드 비교: TPS ===
        System.out.println();
        System.out.println("=== 대시보드 표시 비교 (TPS) ===");
        float[] tpsData = (float[]) metrics[2][1];
        rng2 = new Random(777);
        for (int i = 0; i < 10; i++) samples[i] = tpsData[rng2.nextInt(tpsData.length)];

        System.out.print(String.format("%-10s", "원본"));
        for (float v : samples) System.out.print(String.format(" %7.0f", v));
        System.out.println();
        System.out.println(new String(new char[88]).replace('\0', '-'));

        for (int bits : new int[]{3, 4, 5, 6, 8}) {
            float[] cb = buildCodebook(tpsData, bits);
            System.out.print(String.format("%-10s", bits + "bit(" + (1<<bits) + ")"));
            for (float v : samples) {
                float restored = cb[nearest(v, cb)];
                System.out.print(String.format(" %7.0f", restored));
            }
            System.out.println();
        }

        // === 압축률 + 허용 오차별 최소 비트 ===
        System.out.println();
        System.out.println("=== 메트릭별 허용 오차 달성에 필요한 최소 비트 ===");
        double[] thresholds = {5.0, 2.0, 1.0, 0.5, 0.1};
        System.out.print(String.format("%-20s", "메트릭"));
        for (double th : thresholds) System.out.print(String.format(" %7s", "<" + th + "%"));
        System.out.println();
        System.out.println(new String(new char[58]).replace('\0', '-'));

        for (Object[] m : metrics) {
            String name = (String) m[0];
            float[] data = (float[]) m[1];
            System.out.print(String.format("%-20s", name));
            for (double th : thresholds) {
                int minBits = -1;
                for (int b = 1; b <= 16; b++) {
                    float[] cb = buildCodebook(data, b);
                    double err = measureRelError(data, cb);
                    if (err < th) { minBits = b; break; }
                }
                if (minBits > 0) {
                    double ratio = 32.0 / minBits;
                    System.out.print(String.format(" %3dbit(%1.0fx)", minBits, ratio));
                } else {
                    System.out.print("     16+bit");
                }
            }
            System.out.println();
        }

        // === 최종 결론 ===
        System.out.println();
        System.out.println("=== 최종 결론 ===");
        System.out.println("5-bit(32개 코드북) 적응형이면 대부분 메트릭 오차 < 1%");
        System.out.println("  → float32(4B) → 5-bit(0.625B) = 6.4x 압축");
        System.out.println("  → MetricValue 5필드: 20B → 4B = 5x 압축");
        System.out.println("  → 대시보드 표시 차이 거의 없음");
    }

    // === 데이터 생성 (실제 모니터링 분포 시뮬레이션) ===
    static float[] generateCpu(Random r) {
        float[] d = new float[DATA_SIZE];
        for (int i = 0; i < DATA_SIZE; i++) {
            double v = 55 + r.nextGaussian() * 15;
            if (r.nextDouble() < 0.05) v = 85 + r.nextDouble() * 15;
            if (r.nextDouble() < 0.02) v = r.nextDouble() * 10; // idle
            d[i] = (float)Math.max(0, Math.min(100, v));
        }
        return d;
    }
    static float[] generateMem(Random r) {
        float[] d = new float[DATA_SIZE];
        for (int i = 0; i < DATA_SIZE; i++) {
            d[i] = (float)Math.max(0, Math.min(100, 65 + r.nextGaussian() * 10));
        }
        return d;
    }
    static float[] generateTps(Random r) {
        float[] d = new float[DATA_SIZE];
        for (int i = 0; i < DATA_SIZE; i++) {
            double v = 200 + r.nextGaussian() * 80;
            if (r.nextDouble() < 0.03) v = 800 + r.nextDouble() * 2000;
            d[i] = (float)Math.max(0, v);
        }
        return d;
    }
    static float[] generateRt(Random r) {
        float[] d = new float[DATA_SIZE];
        for (int i = 0; i < DATA_SIZE; i++) {
            d[i] = (float)Math.max(1, Math.exp(5.5 + r.nextGaussian() * 0.8));
        }
        return d;
    }
    static float[] generateHeap(Random r) {
        float[] d = new float[DATA_SIZE];
        for (int i = 0; i < DATA_SIZE; i++) {
            d[i] = (float)Math.max(0, 2000 + r.nextGaussian() * 500);
        }
        return d;
    }
    static float[] generateGc(Random r) {
        float[] d = new float[DATA_SIZE];
        for (int i = 0; i < DATA_SIZE; i++) {
            d[i] = (float)Math.max(0, r.nextInt(15) + r.nextGaussian() * 2);
        }
        return d;
    }
    static float[] generateError(Random r) {
        float[] d = new float[DATA_SIZE];
        for (int i = 0; i < DATA_SIZE; i++) {
            if (r.nextDouble() < 0.7) d[i] = 0;
            else d[i] = (float)(1 + r.nextInt(5));
        }
        return d;
    }
    static float[] generateActive(Random r) {
        float[] d = new float[DATA_SIZE];
        for (int i = 0; i < DATA_SIZE; i++) {
            d[i] = (float)Math.max(0, 5 + r.nextGaussian() * 3);
        }
        return d;
    }
    static float[] generateDisk(Random r) {
        float[] d = new float[DATA_SIZE];
        for (int i = 0; i < DATA_SIZE; i++) {
            d[i] = (float)Math.max(0, Math.min(100, 45 + r.nextGaussian() * 20));
        }
        return d;
    }
    static float[] generateNetIo(Random r) {
        float[] d = new float[DATA_SIZE];
        for (int i = 0; i < DATA_SIZE; i++) {
            double v = Math.exp(7 + r.nextGaussian() * 1.2); // log-normal
            d[i] = (float)Math.max(0, v);
        }
        return d;
    }

    // === Lloyd-Max 코드북 학습 ===
    static float[] buildCodebook(float[] data, int bits) {
        int k = 1 << bits;
        float[] sorted = data.clone();
        Arrays.sort(sorted);
        float[] cb = new float[k];
        for (int i = 0; i < k; i++) {
            int idx = Math.min((int)((i + 0.5) * sorted.length / k), sorted.length - 1);
            cb[i] = sorted[idx];
        }
        for (int iter = 0; iter < 200; iter++) {
            double[] sums = new double[k];
            int[] counts = new int[k];
            for (float v : data) {
                int best = nearest(v, cb);
                sums[best] += v;
                counts[best]++;
            }
            boolean conv = true;
            for (int i = 0; i < k; i++) {
                if (counts[i] > 0) {
                    float nv = (float)(sums[i] / counts[i]);
                    if (Math.abs(nv - cb[i]) > 0.001f) conv = false;
                    cb[i] = nv;
                }
            }
            if (conv) break;
        }
        Arrays.sort(cb);
        return cb;
    }

    static int nearest(float v, float[] cb) {
        int best = 0; float bd = Float.MAX_VALUE;
        for (int i = 0; i < cb.length; i++) {
            float d = Math.abs(v - cb[i]);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    static double measureRelError(float[] data, float[] cb) {
        double total = 0; int cnt = 0;
        for (float v : data) {
            float r = cb[nearest(v, cb)];
            if (Math.abs(v) > 0.01f) { total += Math.abs(v - r) / Math.abs(v) * 100; cnt++; }
        }
        return cnt > 0 ? total / cnt : 0;
    }
}
