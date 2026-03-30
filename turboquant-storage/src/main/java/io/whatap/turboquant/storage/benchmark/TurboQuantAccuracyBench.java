package io.whatap.turboquant.storage.benchmark;

import io.whatap.turboquant.core.TurboQuantizer;

import java.util.Random;

/**
 * TurboQuant 적용 시 API 응답값이 얼마나 달라지는지 측정.
 * 실제 모니터링 메트릭 범위의 데이터로 테스트.
 */
public class TurboQuantAccuracyBench {

    public static void main(String[] args) {
        System.out.println("=== TurboQuant 적용 시 API 응답값 차이 분석 ===");
        System.out.println();

        // 실제 메트릭 범위별 테스트
        // MetricValue: count(int), sum(double), min(float), max(float), last(float)
        testMetric("CPU (%)", 0, 100, 40, 20);
        testMetric("Memory (%)", 0, 100, 65, 10);
        testMetric("TPS", 0, 10000, 500, 200);
        testMetric("Response Time (ms)", 0, 30000, 1500, 800);
        testMetric("Heap Used (MB)", 0, 4096, 2000, 500);
        testMetric("GC Count", 0, 100, 10, 5);
        testMetric("Error Count", 0, 50, 3, 2);
        testMetric("Active Service", 0, 200, 15, 8);

        // 대시보드에서 보이는 포맷으로 비교
        System.out.println();
        System.out.println("=== 대시보드 표시 비교 (소수점 반올림) ===");
        System.out.println();
        dashboardCompare("CPU (%)", 0, 100, 40, 20, "%.1f%%");
        dashboardCompare("Memory (%)", 0, 100, 65, 10, "%.1f%%");
        dashboardCompare("TPS", 0, 10000, 500, 200, "%.0f");
        dashboardCompare("Response Time (ms)", 0, 30000, 1500, 800, "%.0f ms");
        dashboardCompare("Heap Used (MB)", 0, 4096, 2000, 500, "%.0f MB");
        dashboardCompare("GC Count", 0, 100, 10, 5, "%.0f");
        dashboardCompare("Error Count", 0, 50, 3, 2, "%.0f");
        dashboardCompare("Active Service", 0, 200, 15, 8, "%.0f");

        // 다차원 벡터 테스트 (TagCountPack의 여러 필드를 함께 양자화)
        System.out.println();
        System.out.println("=== 다차원 벡터 양자화 (dim=20, 4-bit) ===");
        testMultiDim();
    }

    static void testMetric(String name, float min, float max, float mean, float std) {
        Random rng = new Random(42);
        int samples = 1000;
        int dim = 5; // count, sum, min, max, last

        // 3-bit과 4-bit 비교
        for (int bits : new int[]{3, 4}) {
            TurboQuantizer tq = new TurboQuantizer(bits, dim, 42L);

            double sumAbsErr = 0, maxAbsErr = 0;
            double sumRelErr = 0;
            int relCount = 0;

            for (int s = 0; s < samples; s++) {
                float val = (float) (mean + rng.nextGaussian() * std);
                val = Math.max(min, Math.min(max, val));

                float count = 1 + rng.nextInt(10);
                float sum = val * count;
                float fmin = val - Math.abs((float) rng.nextGaussian()) * std * 0.3f;
                float fmax = val + Math.abs((float) rng.nextGaussian()) * std * 0.3f;
                float last = val;

                float[] original = {count, sum, fmin, fmax, last};
                byte[] compressed = tq.compress(original);
                float[] restored = tq.decompress(compressed);

                // last 필드만 비교 (대시보드에 주로 표시되는 값)
                float absErr = Math.abs(original[4] - restored[4]);
                sumAbsErr += absErr;
                if (absErr > maxAbsErr) maxAbsErr = absErr;

                if (Math.abs(original[4]) > 0.01) {
                    sumRelErr += absErr / Math.abs(original[4]) * 100;
                    relCount++;
                }
            }

            double avgAbsErr = sumAbsErr / samples;
            double avgRelErr = relCount > 0 ? sumRelErr / relCount : 0;

            if (bits == 3) {
                System.out.println(String.format("%-25s [3-bit] 평균오차: %.2f, 최대오차: %.2f, 상대오차: %.2f%%",
                        name, avgAbsErr, maxAbsErr, avgRelErr));
            } else {
                System.out.println(String.format("%-25s [4-bit] 평균오차: %.2f, 최대오차: %.2f, 상대오차: %.2f%%",
                        name, avgAbsErr, maxAbsErr, avgRelErr));
            }
        }
    }

    static void dashboardCompare(String name, float min, float max, float mean, float std, String fmt) {
        Random rng = new Random(123);
        TurboQuantizer tq = new TurboQuantizer(4, 5, 42L);

        System.out.println(String.format("  %-25s", name));
        System.out.println(String.format("  %-12s %-15s %-15s %-10s", "", "원본", "TQ 4-bit", "차이"));

        for (int i = 0; i < 5; i++) {
            float val = (float) (mean + rng.nextGaussian() * std);
            val = Math.max(min, Math.min(max, val));

            float count = 1 + rng.nextInt(5);
            float[] original = {count, val * count, val * 0.9f, val * 1.1f, val};
            byte[] compressed = tq.compress(original);
            float[] restored = tq.decompress(compressed);

            String origStr = String.format(fmt, original[4]);
            String restStr = String.format(fmt, restored[4]);
            float diff = original[4] - restored[4];
            String diffStr = String.format("%+.2f", diff);

            boolean same = origStr.equals(restStr);
            System.out.println(String.format("  샘플 %-5d %-15s %-15s %-10s %s",
                    i + 1, origStr, restStr, diffStr, same ? "" : "<-- 차이 있음"));
        }
        System.out.println();
    }

    static void testMultiDim() {
        Random rng = new Random(42);
        int dim = 20;
        TurboQuantizer tq = new TurboQuantizer(4, dim, 42L);

        // 실제 TagCountPack 필드 시뮬레이션
        String[] fieldNames = {
                "cpu", "mem", "heap_use", "heap_tot", "tps",
                "resp_time", "service_count", "service_error", "gc_count", "gc_time",
                "sql_count", "sql_time", "httpc_count", "httpc_time", "act_svc_count",
                "thread_count", "disk", "swap", "status200", "status500"
        };
        float[] ranges = {
                100, 100, 4096, 8192, 10000,
                30000, 1000, 100, 100, 1000,
                500, 5000, 200, 3000, 50,
                500, 100, 100, 10000, 100
        };

        System.out.println(String.format("%-20s %12s %12s %10s %10s",
                "필드", "원본값", "TQ값", "절대오차", "상대오차"));
        System.out.println(new String(new char[68]).replace('\0', '-'));

        float[] original = new float[dim];
        for (int i = 0; i < dim; i++) {
            original[i] = (float) (ranges[i] * (0.3 + rng.nextDouble() * 0.5));
        }

        byte[] compressed = tq.compress(original);
        float[] restored = tq.decompress(compressed);

        for (int i = 0; i < dim; i++) {
            float absErr = Math.abs(original[i] - restored[i]);
            float relErr = Math.abs(original[i]) > 0.01f ? absErr / Math.abs(original[i]) * 100 : 0;
            System.out.println(String.format("%-20s %12.2f %12.2f %10.2f %9.1f%%",
                    fieldNames[i], original[i], restored[i], absErr, relErr));
        }

        System.out.println();
        System.out.println("압축: " + (dim * 4) + " bytes → " + compressed.length + " bytes ("
                + String.format("%.1fx", (float)(dim * 4) / compressed.length) + ")");
    }
}
