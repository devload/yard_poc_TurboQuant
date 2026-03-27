package io.whatap.turboquant.core;

/**
 * Lloyd-Max optimal quantizer codebook for Beta(d/2, d/2) distribution.
 * Pre-computes centroids for the given bit-width and dimension.
 */
public class LloydMaxCodebook {

    private final int numBits;
    private final int codebookSize;
    private final float[] centroids;

    public LloydMaxCodebook(int numBits, int dimension) {
        this.numBits = numBits;
        this.codebookSize = 1 << numBits;
        this.centroids = computeCodebook(numBits, dimension);
    }

    public float[] getCentroids() {
        return centroids;
    }

    public int getCodebookSize() {
        return codebookSize;
    }

    /**
     * Compute Lloyd-Max optimal codebook for Beta(alpha, alpha) distribution
     * where alpha = d/2. For high dimensions, Beta(d/2,d/2) is approximately
     * normal with mean=0, variance=1/d (after centering).
     */
    private static float[] computeCodebook(int numBits, int dimension) {
        int k = 1 << numBits;
        double alpha = dimension / 2.0;

        // For high-dimensional case, approximate Beta(d/2,d/2) as N(0, 1/(4*alpha))
        // after mapping [0,1] -> centered. The standard deviation is approx 1/(2*sqrt(d))
        double sigma = 1.0 / (2.0 * Math.sqrt(dimension));

        // Initialize centroids uniformly in [-3*sigma, 3*sigma]
        float[] centroids = new float[k];
        double range = 6.0 * sigma;
        for (int i = 0; i < k; i++) {
            centroids[i] = (float) (-3.0 * sigma + range * (i + 0.5) / k);
        }

        // Lloyd-Max iteration
        int maxIter = 300;
        int numSamples = 10000;
        double[] samples = generateBetaSamples(alpha, numSamples);

        for (int iter = 0; iter < maxIter; iter++) {
            // Compute decision boundaries (midpoints between adjacent centroids)
            double[] boundaries = new double[k + 1];
            boundaries[0] = Double.NEGATIVE_INFINITY;
            boundaries[k] = Double.POSITIVE_INFINITY;
            for (int i = 1; i < k; i++) {
                boundaries[i] = (centroids[i - 1] + centroids[i]) / 2.0;
            }

            // Update centroids: mean of samples in each partition
            double[] sums = new double[k];
            int[] counts = new int[k];

            for (double sample : samples) {
                // Find which partition this sample belongs to
                int bin = 0;
                for (int i = 1; i < k; i++) {
                    if (sample >= boundaries[i]) {
                        bin = i;
                    } else {
                        break;
                    }
                }
                sums[bin] += sample;
                counts[bin]++;
            }

            boolean converged = true;
            for (int i = 0; i < k; i++) {
                if (counts[i] > 0) {
                    float newCentroid = (float) (sums[i] / counts[i]);
                    if (Math.abs(newCentroid - centroids[i]) > 1e-8) {
                        converged = false;
                    }
                    centroids[i] = newCentroid;
                }
            }

            if (converged) break;
        }

        return centroids;
    }

    /**
     * Generate samples from centered Beta(alpha, alpha) distribution.
     * Uses rejection sampling: Beta(a,a) can be generated from Gamma distributions.
     * For simplicity, we use the normal approximation for large alpha.
     */
    private static double[] generateBetaSamples(double alpha, int n) {
        double[] samples = new double[n];
        double sigma = 1.0 / (2.0 * Math.sqrt(2.0 * alpha));

        java.util.Random rng = new java.util.Random(12345);
        for (int i = 0; i < n; i++) {
            // Centered Beta: mean=0.5, var=1/(4*(2*alpha+1))
            // Shifted to center at 0: subtract 0.5
            samples[i] = rng.nextGaussian() * sigma;
        }
        return samples;
    }
}
