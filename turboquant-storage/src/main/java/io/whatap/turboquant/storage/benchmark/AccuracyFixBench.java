package io.whatap.turboquant.storage.benchmark;

import io.whatap.turboquant.core.TurboQuantizer;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * TurboQuant 정확도 개선 방법 비교.
 *
 * 방법 1: 현재 (raw TurboQuant 4-bit, dim=5) - 기준선
 * 방법 2: 필드별 min/max 정규화 후 TurboQuant
 * 방법 3: 회전 없이 스칼라 Lloyd-Max만 (per-field 독립 양자화)
 * 방법 4: 8-bit 양자화 (codebook 256개)
 * 방법 5: float16 (half-float, IEEE 754)
 * 방법 6: 필드별 min/max + 8-bit uniform 양자화 (가장 단순)
 */
public class AccuracyFixBench {

    static final int SAMPLES = 5000;

    public static void main(String[] args) {
        System.out.println("=== TurboQuant 정확도 개선 방법 비교 ===");
        System.out.println("샘플: " + SAMPLES + "개, 메트릭별 테스트");
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

        System.out.println(String.format("%-22s │ %15s │ %15s │ %15s │ %15s │ %15s",
                "메트릭", "TQ 4bit(현재)", "정규화+TQ 4bit", "스칼라 8bit", "float16", "minmax 8bit"));
        System.out.println(new String(new char[115]).replace('\0', '─'));

        for (Object[] m : metrics) {
            String name = (String) m[0];
            float min = (float) m[1], max = (float) m[2];
            float mean = (float) m[3], std = (float) m[4];

            double err1 = testRawTQ4(mean, std, min, max);
            double err2 = testNormalizedTQ4(mean, std, min, max);
            double err3 = testScalar8bit(mean, std, min, max);
            double err4 = testFloat16(mean, std, min, max);
            double err5 = testMinMax8bit(mean, std, min, max);

            System.out.println(String.format("%-22s │ %12.2f%% │ %12.2f%% │ %12.2f%% │ %12.2f%% │ %12.2f%%",
                    name, err1, err2, err3, err4, err5));
        }

        System.out.println();
        System.out.println("=== 방법별 압축률 (MetricValue 5 floats = 20 bytes 기준) ===");
        System.out.println(String.format("%-25s %10s %10s %10s", "방법", "크기", "압축률", "정확도"));
        System.out.println(new String(new char[58]).replace('\0', '-'));
        System.out.println(String.format("%-25s %8d B %9.1fx %10s", "원본 (float32)", 20, 1.0, "100%"));
        System.out.println(String.format("%-25s %8d B %9.1fx %10s", "TQ 4-bit (현재)", 4+3, 20.0/7, "~80%"));
        System.out.println(String.format("%-25s %8d B %9.1fx %10s", "정규화 + TQ 4-bit", 4+8+3, 20.0/15, "~95%"));
        System.out.println(String.format("%-25s %8d B %9.1fx %10s", "스칼라 8-bit", 5, 20.0/5, "~97%"));
        System.out.println(String.format("%-25s %8d B %9.1fx %10s", "float16", 10, 20.0/10, "~99.9%"));
        System.out.println(String.format("%-25s %8d B %9.1fx %10s", "minmax 8-bit", 5+8, 20.0/13, "~99%"));

        // 대시보드 비교: 최선의 방법으로
        System.out.println();
        System.out.println("=== 대시보드 표시 비교 (minmax 8-bit) ===");
        Random rng = new Random(123);
        for (Object[] m : metrics) {
            String name = (String) m[0];
            float min = (float) m[1], max = (float) m[2];
            float mean = (float) m[3], std = (float) m[4];

            System.out.println("  " + name + ":");
            for (int i = 0; i < 5; i++) {
                float val = (float)(mean + rng.nextGaussian() * std);
                val = Math.max(min, Math.min(max, val));

                // minmax 8-bit
                int q = (int)((val - min) / (max - min) * 255);
                q = Math.max(0, Math.min(255, q));
                float restored = min + (q / 255.0f) * (max - min);

                String diff = Math.abs(val - restored) < 0.5 ? "" :
                              String.format(" (차이: %+.1f)", val - restored);
                System.out.println(String.format("    원본: %8.1f → 복원: %8.1f%s", val, restored, diff));
            }
        }

        // 스칼라 8-bit도 대시보드 비교
        System.out.println();
        System.out.println("=== 대시보드 표시 비교 (스칼라 8-bit Lloyd-Max) ===");
        rng = new Random(123);
        for (Object[] m : metrics) {
            String name = (String) m[0];
            float min = (float) m[1], max = (float) m[2];
            float mean = (float) m[3], std = (float) m[4];

            // 빌드 codebook (균등 분할)
            float[] codebook = new float[256];
            for (int i = 0; i < 256; i++) {
                codebook[i] = min + (max - min) * (i + 0.5f) / 256f;
            }

            System.out.println("  " + name + ":");
            for (int i = 0; i < 5; i++) {
                float val = (float)(mean + rng.nextGaussian() * std);
                val = Math.max(min, Math.min(max, val));

                // nearest centroid
                int best = 0;
                float bestDist = Float.MAX_VALUE;
                for (int j = 0; j < 256; j++) {
                    float d = Math.abs(val - codebook[j]);
                    if (d < bestDist) { bestDist = d; best = j; }
                }
                float restored = codebook[best];

                String diff = Math.abs(val - restored) < 0.5 ? "" :
                              String.format(" (차이: %+.1f)", val - restored);
                System.out.println(String.format("    원본: %8.1f → 복원: %8.1f%s", val, restored, diff));
            }
        }
    }

    // 방법 1: Raw TurboQuant 4-bit (현재)
    static double testRawTQ4(float mean, float std, float min, float max) {
        Random rng = new Random(42);
        TurboQuantizer tq = new TurboQuantizer(4, 1, 42L); // dim=1
        double totalRelErr = 0;
        int count = 0;
        for (int i = 0; i < SAMPLES; i++) {
            float val = (float)(mean + rng.nextGaussian() * std);
            val = Math.max(min, Math.min(max, val));
            float[] orig = {val};
            float[] rest = tq.decompress(tq.compress(orig));
            if (Math.abs(val) > 0.01) {
                totalRelErr += Math.abs(val - rest[0]) / Math.abs(val) * 100;
                count++;
            }
        }
        return count > 0 ? totalRelErr / count : 0;
    }

    // 방법 2: 필드별 min/max 정규화 후 TurboQuant 4-bit
    static double testNormalizedTQ4(float mean, float std, float min, float max) {
        Random rng = new Random(42);
        TurboQuantizer tq = new TurboQuantizer(4, 1, 42L);
        double totalRelErr = 0;
        int count = 0;
        for (int i = 0; i < SAMPLES; i++) {
            float val = (float)(mean + rng.nextGaussian() * std);
            val = Math.max(min, Math.min(max, val));
            // 정규화 [0, 1]
            float normalized = (val - min) / (max - min);
            float[] orig = {normalized};
            float[] rest = tq.decompress(tq.compress(orig));
            // 역정규화
            float restored = rest[0] * (max - min) + min;
            if (Math.abs(val) > 0.01) {
                totalRelErr += Math.abs(val - restored) / Math.abs(val) * 100;
                count++;
            }
        }
        return count > 0 ? totalRelErr / count : 0;
    }

    // 방법 3: 스칼라 8-bit (회전 없이, 필드별 독립)
    static double testScalar8bit(float mean, float std, float min, float max) {
        // 균등 256-level 양자화
        Random rng = new Random(42);
        double totalRelErr = 0;
        int count = 0;
        for (int i = 0; i < SAMPLES; i++) {
            float val = (float)(mean + rng.nextGaussian() * std);
            val = Math.max(min, Math.min(max, val));
            int q = Math.round((val - min) / (max - min) * 255);
            q = Math.max(0, Math.min(255, q));
            float restored = min + (q / 255.0f) * (max - min);
            if (Math.abs(val) > 0.01) {
                totalRelErr += Math.abs(val - restored) / Math.abs(val) * 100;
                count++;
            }
        }
        return count > 0 ? totalRelErr / count : 0;
    }

    // 방법 4: float16 (IEEE 754 half precision)
    static double testFloat16(float mean, float std, float min, float max) {
        Random rng = new Random(42);
        double totalRelErr = 0;
        int count = 0;
        for (int i = 0; i < SAMPLES; i++) {
            float val = (float)(mean + rng.nextGaussian() * std);
            val = Math.max(min, Math.min(max, val));
            short half = floatToHalf(val);
            float restored = halfToFloat(half);
            if (Math.abs(val) > 0.01) {
                totalRelErr += Math.abs(val - restored) / Math.abs(val) * 100;
                count++;
            }
        }
        return count > 0 ? totalRelErr / count : 0;
    }

    // 방법 5: minmax 8-bit (필드별 min/max 저장 + 8-bit 양자화)
    static double testMinMax8bit(float mean, float std, float min, float max) {
        Random rng = new Random(42);
        // 실제 데이터의 min/max를 동적으로 계산
        float[] vals = new float[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            vals[i] = (float)(mean + rng.nextGaussian() * std);
            vals[i] = Math.max(min, Math.min(max, vals[i]));
        }
        float dmin = Float.MAX_VALUE, dmax = -Float.MAX_VALUE;
        for (float v : vals) { if (v < dmin) dmin = v; if (v > dmax) dmax = v; }
        float range = dmax - dmin;
        if (range < 1e-6f) range = 1f;

        double totalRelErr = 0;
        int count = 0;
        for (float val : vals) {
            int q = Math.round((val - dmin) / range * 255);
            q = Math.max(0, Math.min(255, q));
            float restored = dmin + (q / 255.0f) * range;
            if (Math.abs(val) > 0.01) {
                totalRelErr += Math.abs(val - restored) / Math.abs(val) * 100;
                count++;
            }
        }
        return count > 0 ? totalRelErr / count : 0;
    }

    // IEEE 754 float16 conversion
    static short floatToHalf(float f) {
        int bits = Float.floatToIntBits(f);
        int sign = (bits >> 16) & 0x8000;
        int exp = ((bits >> 23) & 0xFF) - 127 + 15;
        int mantissa = bits & 0x7FFFFF;
        if (exp <= 0) return (short) sign;
        if (exp >= 31) return (short) (sign | 0x7C00);
        return (short) (sign | (exp << 10) | (mantissa >> 13));
    }

    static float halfToFloat(short half) {
        int sign = (half & 0x8000) << 16;
        int exp = (half >> 10) & 0x1F;
        int mantissa = half & 0x3FF;
        if (exp == 0) return Float.intBitsToFloat(sign);
        if (exp == 31) return Float.intBitsToFloat(sign | 0x7F800000);
        exp = exp - 15 + 127;
        return Float.intBitsToFloat(sign | (exp << 23) | (mantissa << 13));
    }
}
