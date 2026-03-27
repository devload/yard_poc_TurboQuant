package io.whatap.turboquant.storage.benchmark;

public class BenchmarkResult {
    public final String method;
    public final long rawBytes;
    public final long compressedBytes;
    public final double ratio;
    public final long encodeNanos;
    public final long decodeNanos;
    public final double mseSums;
    public final double mseMins;
    public final double mseMaxs;
    public final double mseLasts;

    public BenchmarkResult(String method, long rawBytes, long compressedBytes,
                           long encodeNanos, long decodeNanos,
                           double mseSums, double mseMins, double mseMaxs, double mseLasts) {
        this.method = method;
        this.rawBytes = rawBytes;
        this.compressedBytes = compressedBytes;
        this.ratio = rawBytes > 0 ? (double) rawBytes / compressedBytes : 0;
        this.encodeNanos = encodeNanos;
        this.decodeNanos = decodeNanos;
        this.mseSums = mseSums;
        this.mseMins = mseMins;
        this.mseMaxs = mseMaxs;
        this.mseLasts = mseLasts;
    }

    public String toCsvLine() {
        return String.format("%s,%d,%d,%.2f,%d,%d,%.6f,%.6f,%.6f,%.6f",
                method, rawBytes, compressedBytes, ratio,
                encodeNanos, decodeNanos,
                mseSums, mseMins, mseMaxs, mseLasts);
    }

    public static String csvHeader() {
        return "method,raw_bytes,compressed_bytes,ratio,encode_ns,decode_ns,mse_sums,mse_mins,mse_maxs,mse_lasts";
    }
}
