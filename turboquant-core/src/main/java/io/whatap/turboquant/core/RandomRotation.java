package io.whatap.turboquant.core;

import java.util.Random;

/**
 * Random orthogonal rotation matrix via QR decomposition (Modified Gram-Schmidt).
 * After rotation, each coordinate follows approximately Beta(d/2, d/2) distribution.
 */
public class RandomRotation {

    private final int dimension;
    private final float[][] Q; // orthogonal matrix
    private final float[][] QT; // transpose (for inverse)

    public RandomRotation(int dimension, long seed) {
        this.dimension = dimension;
        Random rng = new Random(seed);

        // Generate random Gaussian matrix
        float[][] A = new float[dimension][dimension];
        for (int i = 0; i < dimension; i++) {
            for (int j = 0; j < dimension; j++) {
                A[i][j] = (float) rng.nextGaussian();
            }
        }

        // QR decomposition via Modified Gram-Schmidt
        this.Q = qrGramSchmidt(A, dimension);
        this.QT = transpose(Q, dimension);
    }

    public float[] rotate(float[] x) {
        return matvec(Q, x, dimension);
    }

    public float[] inverseRotate(float[] y) {
        return matvec(QT, y, dimension);
    }

    private static float[][] qrGramSchmidt(float[][] A, int n) {
        float[][] Q = new float[n][n];

        // Copy columns
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                Q[j][i] = A[j][i]; // column i
            }
        }

        for (int i = 0; i < n; i++) {
            // Normalize column i
            float norm = 0;
            for (int j = 0; j < n; j++) {
                norm += Q[j][i] * Q[j][i];
            }
            norm = (float) Math.sqrt(norm);
            if (norm < 1e-10f) norm = 1e-10f;
            for (int j = 0; j < n; j++) {
                Q[j][i] /= norm;
            }

            // Orthogonalize subsequent columns against column i
            for (int k = i + 1; k < n; k++) {
                float dot = 0;
                for (int j = 0; j < n; j++) {
                    dot += Q[j][i] * Q[j][k];
                }
                for (int j = 0; j < n; j++) {
                    Q[j][k] -= dot * Q[j][i];
                }
            }
        }

        return Q;
    }

    private static float[][] transpose(float[][] M, int n) {
        float[][] T = new float[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                T[i][j] = M[j][i];
            }
        }
        return T;
    }

    private static float[] matvec(float[][] M, float[] v, int n) {
        float[] result = new float[n];
        for (int i = 0; i < n; i++) {
            float sum = 0;
            for (int j = 0; j < n; j++) {
                sum += M[i][j] * v[j];
            }
            result[i] = sum;
        }
        return result;
    }
}
