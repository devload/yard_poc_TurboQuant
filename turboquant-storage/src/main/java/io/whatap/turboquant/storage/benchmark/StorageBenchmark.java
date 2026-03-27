package io.whatap.turboquant.storage.benchmark;

import io.whatap.io.DataOutputX;
import io.whatap.lang.value.MetricValue;
import io.whatap.turboquant.core.RotationCache;
import io.whatap.turboquant.storage.codec.MetricValueCodec;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class StorageBenchmark {

    private static final int TIME_STEPS = 60;
    private static final int NUM_BLOCKS = 100;

    public static void main(String[] args) throws Exception {
        System.out.println("=== TurboQuant Storage Benchmark ===");
        System.out.println("Time steps per block: " + TIME_STEPS);
        System.out.println("Number of blocks: " + NUM_BLOCKS);
        System.out.println();

        Random rng = new Random(42);
        List<MetricBlock> blocks = new ArrayList<MetricBlock>();
        for (int b = 0; b < NUM_BLOCKS; b++) {
            blocks.add(generateBlock(rng, TIME_STEPS));
        }

        // --- Phase 5: Rotation Cache Benchmark ---
        System.out.println("--- Phase 5: Rotation Matrix Caching ---");
        RotationCache.clear();
        int dim = TIME_STEPS * 5;

        // Cold start (no cache)
        long coldStart = System.nanoTime();
        new io.whatap.turboquant.core.TurboQuantizer(4, dim, 42L);
        long coldTime = System.nanoTime() - coldStart;
        System.out.println(String.format("  Cold init (QR decomposition):  %,d us", coldTime / 1000));

        // Warm start (cached)
        long warmStart = System.nanoTime();
        new io.whatap.turboquant.core.TurboQuantizer(4, dim, 42L);
        long warmTime = System.nanoTime() - warmStart;
        System.out.println(String.format("  Warm init (cache hit):         %,d us", warmTime / 1000));
        System.out.println(String.format("  Speedup:                       %.0fx", (double) coldTime / warmTime));
        System.out.println(String.format("  Cache entries:                 %d", RotationCache.size()));
        System.out.println();

        List<BenchmarkResult> results = new ArrayList<BenchmarkResult>();
        results.add(benchmarkRaw(blocks));
        results.add(benchmarkGzip(blocks));
        results.add(benchmarkTurboQuant(blocks, 3, false));
        results.add(benchmarkTurboQuant(blocks, 3, true));
        results.add(benchmarkTurboQuant(blocks, 4, false));
        results.add(benchmarkTurboQuant(blocks, 4, true));

        // CSV output
        System.out.println();
        System.out.println(BenchmarkResult.csvHeader());
        for (BenchmarkResult r : results) {
            System.out.println(r.toCsvLine());
        }

        // Pretty print
        System.out.println();
        System.out.println(String.format("%-25s %12s %12s %8s %10s %10s %10s %10s",
                "Method", "Raw", "Compressed", "Ratio", "Enc(us)", "Dec(us)", "MSE(sum)", "MSE(last)"));
        System.out.println(new String(new char[105]).replace('\0', '-'));
        for (BenchmarkResult r : results) {
            System.out.println(String.format("%-25s %12d %12d %7.2fx %9d %9d %10.4f %10.4f",
                    r.method, r.rawBytes, r.compressedBytes, r.ratio,
                    r.encodeNanos / 1000, r.decodeNanos / 1000, r.mseSums, r.mseLasts));
        }

        // Write CSV report
        String csvPath = "benchmark_report.csv";
        PrintWriter pw = new PrintWriter(new FileWriter(csvPath));
        pw.println(BenchmarkResult.csvHeader());
        for (BenchmarkResult r : results) {
            pw.println(r.toCsvLine());
        }
        pw.close();
        System.out.println("\nReport saved to: " + csvPath);
    }

    private static BenchmarkResult benchmarkRaw(List<MetricBlock> blocks) {
        long totalRaw = 0;
        long encStart = System.nanoTime();
        for (MetricBlock block : blocks) {
            byte[] raw = serializeRaw(block);
            totalRaw += raw.length;
        }
        long encTime = System.nanoTime() - encStart;
        return new BenchmarkResult("Raw", totalRaw, totalRaw, encTime, 0, 0, 0, 0, 0);
    }

    private static BenchmarkResult benchmarkGzip(List<MetricBlock> blocks) {
        long totalRaw = 0;
        long totalCompressed = 0;
        long encStart = System.nanoTime();
        List<byte[]> compressed = new ArrayList<byte[]>();
        for (MetricBlock block : blocks) {
            byte[] raw = serializeRaw(block);
            totalRaw += raw.length;
            byte[] gz = gzipCompress(raw);
            totalCompressed += gz.length;
            compressed.add(gz);
        }
        long encTime = System.nanoTime() - encStart;

        long decStart = System.nanoTime();
        for (byte[] gz : compressed) {
            gzipDecompress(gz);
        }
        long decTime = System.nanoTime() - decStart;

        return new BenchmarkResult("GZIP", totalRaw, totalCompressed, encTime, decTime, 0, 0, 0, 0);
    }

    private static BenchmarkResult benchmarkTurboQuant(List<MetricBlock> blocks, int bits, boolean withGzip) {
        String name = "TurboQuant-" + bits + "bit" + (withGzip ? "+GZIP" : "");
        MetricValueCodec codec = new MetricValueCodec(bits, TIME_STEPS);

        long totalRaw = 0;
        long totalCompressed = 0;
        double totalMseSums = 0, totalMseMins = 0, totalMseMaxs = 0, totalMseLasts = 0;

        long encStart = System.nanoTime();
        List<byte[]> compressedList = new ArrayList<byte[]>();
        for (MetricBlock block : blocks) {
            totalRaw += serializeRaw(block).length;
            byte[] encoded = codec.encode(block.counts, block.sums, block.mins, block.maxs, block.lasts);
            if (withGzip) {
                encoded = gzipCompress(encoded);
            }
            totalCompressed += encoded.length;
            compressedList.add(encoded);
        }
        long encTime = System.nanoTime() - encStart;

        long decStart = System.nanoTime();
        int blockIdx = 0;
        for (byte[] enc : compressedList) {
            byte[] data = withGzip ? gzipDecompress(enc) : enc;
            MetricValueCodec.DecodedMetrics decoded = codec.decode(data);
            MetricBlock orig = blocks.get(blockIdx);

            totalMseSums += mseDoubleFloat(orig.sums, decoded.sums);
            totalMseMins += mseFloat(orig.mins, decoded.mins);
            totalMseMaxs += mseFloat(orig.maxs, decoded.maxs);
            totalMseLasts += mseFloat(orig.lasts, decoded.lasts);
            blockIdx++;
        }
        long decTime = System.nanoTime() - decStart;

        int n = blocks.size();
        return new BenchmarkResult(name, totalRaw, totalCompressed, encTime, decTime,
                totalMseSums / n, totalMseMins / n, totalMseMaxs / n, totalMseLasts / n);
    }

    private static byte[] serializeRaw(MetricBlock block) {
        DataOutputX out = new DataOutputX();
        for (int t = 0; t < block.counts.length; t++) {
            MetricValue mv = new MetricValue();
            mv.count = block.counts[t];
            mv.sum = block.sums[t];
            mv.min = block.mins[t];
            mv.max = block.maxs[t];
            mv.last = block.lasts[t];
            mv.write(out);
        }
        return out.toByteArray();
    }

    private static MetricBlock generateBlock(Random rng, int timeSteps) {
        MetricBlock block = new MetricBlock(timeSteps);
        double base = 50 + rng.nextDouble() * 50;
        for (int t = 0; t < timeSteps; t++) {
            double v = base + rng.nextGaussian() * 10 + 5 * Math.sin(2 * Math.PI * t / 60.0);
            block.counts[t] = 1 + rng.nextInt(10);
            block.sums[t] = v * block.counts[t];
            block.mins[t] = (float) (v - Math.abs(rng.nextGaussian()) * 5);
            block.maxs[t] = (float) (v + Math.abs(rng.nextGaussian()) * 5);
            block.lasts[t] = (float) v;
        }
        return block;
    }

    private static double mseDoubleFloat(double[] orig, float[] decoded) {
        double sum = 0;
        for (int i = 0; i < orig.length; i++) {
            double diff = orig[i] - decoded[i];
            sum += diff * diff;
        }
        return sum / orig.length;
    }

    private static double mseFloat(float[] orig, float[] decoded) {
        double sum = 0;
        for (int i = 0; i < orig.length; i++) {
            double diff = orig[i] - decoded[i];
            sum += diff * diff;
        }
        return sum / orig.length;
    }

    private static byte[] gzipCompress(byte[] data) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(baos);
            gzip.write(data);
            gzip.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] gzipDecompress(byte[] data) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            GZIPInputStream gzip = new GZIPInputStream(bais);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = gzip.read(buf)) > 0) {
                baos.write(buf, 0, len);
            }
            gzip.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static class MetricBlock {
        int[] counts;
        double[] sums;
        float[] mins;
        float[] maxs;
        float[] lasts;

        MetricBlock(int timeSteps) {
            counts = new int[timeSteps];
            sums = new double[timeSteps];
            mins = new float[timeSteps];
            maxs = new float[timeSteps];
            lasts = new float[timeSteps];
        }
    }
}
