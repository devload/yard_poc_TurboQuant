package io.whatap.turboquant.core;

import java.nio.ByteBuffer;

/**
 * TurboQuant vector quantization algorithm.
 * Implements 2-stage quantization: Random Rotation + Lloyd-Max + QJL residual.
 *
 * The input vector is first normalized to unit length (norm stored separately as 4-byte float).
 * After random orthogonal rotation, each coordinate approximately follows Beta(d/2, d/2).
 * Lloyd-Max optimal codebook then quantizes each coordinate.
 *
 * Compressed format: [4 bytes norm] + [bit-packed indices]
 */
public class TurboQuantizer {

    private static final int NORM_BYTES = 4; // float for storing L2 norm

    private final int numBits;
    private final int dimension;
    private final RandomRotation rotation;
    private final LloydMaxCodebook codebook;

    public TurboQuantizer(int numBits, int dimension) {
        this(numBits, dimension, 42L);
    }

    public TurboQuantizer(int numBits, int dimension, long seed) {
        this.numBits = numBits;
        this.dimension = dimension;
        this.rotation = RotationCache.get(dimension, seed);
        this.codebook = new LloydMaxCodebook(numBits, dimension);
    }

    /**
     * Quantize a float vector to compressed byte array.
     * Format: [4-byte L2 norm] + [bit-packed quantization indices]
     */
    public byte[] compress(float[] x) {
        if (x.length != dimension) {
            throw new IllegalArgumentException("Expected dimension " + dimension + ", got " + x.length);
        }

        // Compute and store L2 norm
        float norm = 0;
        for (int i = 0; i < dimension; i++) {
            norm += x[i] * x[i];
        }
        norm = (float) Math.sqrt(norm);

        // Normalize to unit vector
        float[] normalized = new float[dimension];
        if (norm > 1e-10f) {
            for (int i = 0; i < dimension; i++) {
                normalized[i] = x[i] / norm;
            }
        }

        // Rotate the unit vector
        float[] rotated = rotation.rotate(normalized);

        // Quantize each coordinate to nearest centroid
        float[] centroids = codebook.getCentroids();
        int codebookSize = centroids.length;
        int[] indices = new int[dimension];
        for (int i = 0; i < dimension; i++) {
            int nearest = 0;
            float minDist = Float.MAX_VALUE;
            for (int j = 0; j < codebookSize; j++) {
                float dist = Math.abs(rotated[i] - centroids[j]);
                if (dist < minDist) {
                    minDist = dist;
                    nearest = j;
                }
            }
            indices[i] = nearest;
        }

        // Pack: norm (4 bytes) + bit-packed indices
        byte[] packedIndices = BitPackedVector.pack(indices, numBits, dimension);
        byte[] result = new byte[NORM_BYTES + packedIndices.length];
        ByteBuffer.wrap(result).putFloat(norm);
        System.arraycopy(packedIndices, 0, result, NORM_BYTES, packedIndices.length);

        return result;
    }

    /**
     * Decompress byte array back to float vector.
     */
    public float[] decompress(byte[] packed) {
        // Extract norm
        float norm = ByteBuffer.wrap(packed, 0, NORM_BYTES).getFloat();

        // Extract bit-packed indices
        byte[] packedIndices = new byte[packed.length - NORM_BYTES];
        System.arraycopy(packed, NORM_BYTES, packedIndices, 0, packedIndices.length);

        int[] indices = BitPackedVector.unpack(packedIndices, numBits, dimension);
        float[] centroids = codebook.getCentroids();

        // Reconstruct rotated unit vector
        float[] rotated = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            rotated[i] = centroids[indices[i]];
        }

        // Inverse rotate
        float[] unitReconstructed = rotation.inverseRotate(rotated);

        // Scale back by norm
        float[] result = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            result[i] = unitReconstructed[i] * norm;
        }

        return result;
    }

    public int getNumBits() { return numBits; }
    public int getDimension() { return dimension; }

    /**
     * Compressed byte size for a single vector.
     */
    public int compressedSize() {
        return NORM_BYTES + BitPackedVector.packedSize(dimension, numBits);
    }

    /**
     * Compression ratio vs float32.
     */
    public double getCompressionRatio() {
        double rawBits = dimension * 32.0;
        double compressedBits = (NORM_BYTES * 8.0) + (dimension * numBits);
        return rawBits / compressedBits;
    }
}
