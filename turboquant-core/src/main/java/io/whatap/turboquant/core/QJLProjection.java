package io.whatap.turboquant.core;

import java.util.Random;

/**
 * Quantized Johnson-Lindenstrauss (QJL) projection for 1-bit residual correction.
 * Projects the quantization residual to a lower dimension and stores only signs.
 * This provides unbiased inner product estimation.
 */
public class QJLProjection {

    private final int originalDim;
    private final int projectionDim;
    private final float[][] projMatrix; // projectionDim x originalDim
    private final float[][] projMatrixT; // originalDim x projectionDim

    public QJLProjection(int originalDim, int projectionDim, long seed) {
        this.originalDim = originalDim;
        this.projectionDim = projectionDim;
        this.projMatrix = new float[projectionDim][originalDim];
        this.projMatrixT = new float[originalDim][projectionDim];

        Random rng = new Random(seed);
        float scale = 1.0f / (float) Math.sqrt(originalDim);
        for (int i = 0; i < projectionDim; i++) {
            for (int j = 0; j < originalDim; j++) {
                float v = rng.nextBoolean() ? scale : -scale; // random sign matrix
                projMatrix[i][j] = v;
                projMatrixT[j][i] = v;
            }
        }
    }

    /**
     * Project residual and extract signs (1-bit per dimension).
     * @param residual the quantization error vector
     * @return signs packed as bytes, plus the residual norm as float
     */
    public ProjectionResult project(float[] residual) {
        float norm = 0;
        for (float v : residual) norm += v * v;
        norm = (float) Math.sqrt(norm);

        // Project: z = S * r
        float[] z = new float[projectionDim];
        for (int i = 0; i < projectionDim; i++) {
            float sum = 0;
            for (int j = 0; j < originalDim; j++) {
                sum += projMatrix[i][j] * residual[j];
            }
            z[i] = sum;
        }

        // Extract signs (pack into bytes)
        byte[] signs = new byte[(projectionDim + 7) / 8];
        for (int i = 0; i < projectionDim; i++) {
            if (z[i] >= 0) {
                signs[i / 8] |= (byte) (1 << (7 - i % 8));
            }
        }

        return new ProjectionResult(signs, norm);
    }

    /**
     * Reconstruct the residual approximation from signs and norm.
     */
    public float[] reconstruct(byte[] signs, float norm) {
        // Unpack signs to +1/-1
        float[] signVec = new float[projectionDim];
        for (int i = 0; i < projectionDim; i++) {
            int bit = (signs[i / 8] >> (7 - i % 8)) & 1;
            signVec[i] = bit == 1 ? 1.0f : -1.0f;
        }

        // y = S^T * signs
        float[] y = new float[originalDim];
        for (int i = 0; i < originalDim; i++) {
            float sum = 0;
            for (int j = 0; j < projectionDim; j++) {
                sum += projMatrixT[i][j] * signVec[j];
            }
            y[i] = sum;
        }

        // Scale: r_approx = (norm / ||y||) * y
        float yNorm = 0;
        for (float v : y) yNorm += v * v;
        yNorm = (float) Math.sqrt(yNorm);

        if (yNorm > 1e-10f) {
            float scale = norm / yNorm;
            for (int i = 0; i < originalDim; i++) {
                y[i] *= scale;
            }
        }

        return y;
    }

    public int getProjectionDim() { return projectionDim; }

    public static class ProjectionResult {
        public final byte[] signs;
        public final float norm;

        public ProjectionResult(byte[] signs, float norm) {
            this.signs = signs;
            this.norm = norm;
        }
    }
}
