package io.whatap.turboquant.storage.codec;

import io.whatap.turboquant.core.TurboQuantizer;

import java.nio.ByteBuffer;

/**
 * Encodes/decodes arrays of MetricValue-like float data using TurboQuant.
 * Batches multiple time steps into a high-dimensional vector for effective quantization.
 *
 * Per-field min/max normalization is applied before quantization to handle
 * vastly different value ranges (e.g., count=1~10 vs sum=500~5000).
 *
 * Layout: [count0,sum0,min0,max0,last0, count1,sum1,...] normalized to [0,1]
 *
 * Compressed format:
 *   [4 bytes: timeSteps] [5 * (4+4) = 40 bytes: per-field min/max] [TurboQuant compressed data]
 */
public class MetricValueCodec {

    private static final int FIELDS_PER_METRIC = 5; // count, sum, min, max, last
    private static final int HEADER_FIELD_BYTES = FIELDS_PER_METRIC * 8; // 5 fields * (min+max) * 4 bytes

    private final int numBits;
    private final int timeSteps;

    public MetricValueCodec(int numBits, int timeSteps) {
        this.numBits = numBits;
        this.timeSteps = timeSteps;
    }

    /**
     * Encode an array of metric snapshots with per-field normalization.
     */
    public byte[] encode(int[] counts, double[] sums, float[] mins, float[] maxs, float[] lasts) {
        int dim = timeSteps * FIELDS_PER_METRIC;

        // Organize raw data per field
        float[][] fieldData = new float[FIELDS_PER_METRIC][timeSteps];
        for (int t = 0; t < timeSteps; t++) {
            fieldData[0][t] = (float) counts[t];
            fieldData[1][t] = (float) sums[t];
            fieldData[2][t] = mins[t];
            fieldData[3][t] = maxs[t];
            fieldData[4][t] = lasts[t];
        }

        // Compute per-field min/max for normalization
        float[] fieldMin = new float[FIELDS_PER_METRIC];
        float[] fieldMax = new float[FIELDS_PER_METRIC];
        for (int f = 0; f < FIELDS_PER_METRIC; f++) {
            fieldMin[f] = Float.MAX_VALUE;
            fieldMax[f] = -Float.MAX_VALUE;
            for (int t = 0; t < timeSteps; t++) {
                if (fieldData[f][t] < fieldMin[f]) fieldMin[f] = fieldData[f][t];
                if (fieldData[f][t] > fieldMax[f]) fieldMax[f] = fieldData[f][t];
            }
            // Avoid zero range
            if (fieldMax[f] - fieldMin[f] < 1e-6f) {
                fieldMax[f] = fieldMin[f] + 1.0f;
            }
        }

        // Build normalized vector [0, 1]
        float[] vector = new float[dim];
        for (int t = 0; t < timeSteps; t++) {
            for (int f = 0; f < FIELDS_PER_METRIC; f++) {
                float normalized = (fieldData[f][t] - fieldMin[f]) / (fieldMax[f] - fieldMin[f]);
                vector[t * FIELDS_PER_METRIC + f] = normalized;
            }
        }

        // TurboQuant compress
        TurboQuantizer quantizer = new TurboQuantizer(numBits, dim, 42L);
        byte[] quantized = quantizer.compress(vector);

        // Pack: [timeSteps(4)] + [fieldMin/Max(40)] + [quantized data]
        ByteBuffer buf = ByteBuffer.allocate(4 + HEADER_FIELD_BYTES + quantized.length);
        buf.putInt(timeSteps);
        for (int f = 0; f < FIELDS_PER_METRIC; f++) {
            buf.putFloat(fieldMin[f]);
            buf.putFloat(fieldMax[f]);
        }
        buf.put(quantized);

        return buf.array();
    }

    /**
     * Decode compressed bytes back to separate field arrays.
     */
    public DecodedMetrics decode(byte[] compressed) {
        ByteBuffer buf = ByteBuffer.wrap(compressed);
        int ts = buf.getInt();

        float[] fieldMin = new float[FIELDS_PER_METRIC];
        float[] fieldMax = new float[FIELDS_PER_METRIC];
        for (int f = 0; f < FIELDS_PER_METRIC; f++) {
            fieldMin[f] = buf.getFloat();
            fieldMax[f] = buf.getFloat();
        }

        byte[] quantized = new byte[compressed.length - 4 - HEADER_FIELD_BYTES];
        buf.get(quantized);

        int dim = ts * FIELDS_PER_METRIC;
        TurboQuantizer quantizer = new TurboQuantizer(numBits, dim, 42L);
        float[] normalized = quantizer.decompress(quantized);

        // Denormalize
        DecodedMetrics result = new DecodedMetrics(ts);
        for (int t = 0; t < ts; t++) {
            for (int f = 0; f < FIELDS_PER_METRIC; f++) {
                float val = normalized[t * FIELDS_PER_METRIC + f];
                float denorm = val * (fieldMax[f] - fieldMin[f]) + fieldMin[f];
                switch (f) {
                    case 0: result.counts[t] = denorm; break;
                    case 1: result.sums[t] = denorm; break;
                    case 2: result.mins[t] = denorm; break;
                    case 3: result.maxs[t] = denorm; break;
                    case 4: result.lasts[t] = denorm; break;
                }
            }
        }
        return result;
    }

    public int getTimeSteps() { return timeSteps; }
    public int getDimension() { return timeSteps * FIELDS_PER_METRIC; }

    public static class DecodedMetrics {
        public final float[] counts;
        public final float[] sums;
        public final float[] mins;
        public final float[] maxs;
        public final float[] lasts;

        public DecodedMetrics(int timeSteps) {
            counts = new float[timeSteps];
            sums = new float[timeSteps];
            mins = new float[timeSteps];
            maxs = new float[timeSteps];
            lasts = new float[timeSteps];
        }
    }
}
