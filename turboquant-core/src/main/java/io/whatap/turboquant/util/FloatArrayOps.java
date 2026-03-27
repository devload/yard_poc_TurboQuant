package io.whatap.turboquant.util;

public class FloatArrayOps {

    public static float dot(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    public static float norm(float[] a) {
        return (float) Math.sqrt(dot(a, a));
    }

    public static float mse(float[] original, float[] reconstructed) {
        float sum = 0;
        for (int i = 0; i < original.length; i++) {
            float diff = original[i] - reconstructed[i];
            sum += diff * diff;
        }
        return sum / original.length;
    }

    public static float maxAbsError(float[] original, float[] reconstructed) {
        float maxErr = 0;
        for (int i = 0; i < original.length; i++) {
            float err = Math.abs(original[i] - reconstructed[i]);
            if (err > maxErr) maxErr = err;
        }
        return maxErr;
    }

    public static float cosineSimilarity(float[] a, float[] b) {
        float dotAB = dot(a, b);
        float normA = norm(a);
        float normB = norm(b);
        if (normA < 1e-10f || normB < 1e-10f) return 0f;
        return dotAB / (normA * normB);
    }
}
