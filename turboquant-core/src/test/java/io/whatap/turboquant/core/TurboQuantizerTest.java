package io.whatap.turboquant.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class TurboQuantizerTest {

    @Test
    public void testRoundTrip4Bit() {
        int dim = 100;
        TurboQuantizer q = new TurboQuantizer(4, dim, 42L);

        // Generate test vector
        float[] original = new float[dim];
        java.util.Random rng = new java.util.Random(99);
        for (int i = 0; i < dim; i++) {
            original[i] = (float) (rng.nextGaussian() * 10 + 50);
        }

        byte[] compressed = q.compress(original);
        float[] reconstructed = q.decompress(compressed);

        assertEquals(dim, reconstructed.length);

        // Check MSE is reasonable
        double mse = 0;
        for (int i = 0; i < dim; i++) {
            double diff = original[i] - reconstructed[i];
            mse += diff * diff;
        }
        mse /= dim;
        System.out.println("4-bit MSE: " + mse + " RMSE: " + Math.sqrt(mse));
        // For dim=100, RMSE ~10 is expected. Higher dimensions yield much better results.
        assertTrue("MSE should be reasonable for dim=100", mse < 500);
    }

    @Test
    public void testRoundTrip3Bit() {
        int dim = 50;
        TurboQuantizer q = new TurboQuantizer(3, dim, 42L);

        float[] original = new float[dim];
        java.util.Random rng = new java.util.Random(77);
        for (int i = 0; i < dim; i++) {
            original[i] = (float) (rng.nextGaussian() * 5 + 20);
        }

        byte[] compressed = q.compress(original);
        float[] reconstructed = q.decompress(compressed);
        assertEquals(dim, reconstructed.length);

        double mse = 0;
        for (int i = 0; i < dim; i++) {
            double diff = original[i] - reconstructed[i];
            mse += diff * diff;
        }
        mse /= dim;
        System.out.println("3-bit MSE: " + mse);
    }

    @Test
    public void testCompressionRatio() {
        TurboQuantizer q4 = new TurboQuantizer(4, 500);
        TurboQuantizer q3 = new TurboQuantizer(3, 500);

        // With 4-byte norm overhead, ratio is slightly less than 32/numBits
        // For dim=500: raw=16000bits, compressed=32+2000=2032bits -> ~7.87x for 4-bit
        assertTrue("4-bit compression ratio > 7x", q4.getCompressionRatio() > 7.0);
        assertTrue("3-bit compression ratio > 10x", q3.getCompressionRatio() > 10.0);
    }

    @Test
    public void testBitPacking() {
        int[] indices = {0, 1, 2, 3, 4, 5, 6, 7};
        byte[] packed = BitPackedVector.pack(indices, 3, 8);
        int[] unpacked = BitPackedVector.unpack(packed, 3, 8);

        assertArrayEquals(indices, unpacked);
    }

    @Test
    public void testBitPacking4Bit() {
        int[] indices = {0, 5, 10, 15, 3, 8, 12, 1};
        byte[] packed = BitPackedVector.pack(indices, 4, 8);
        int[] unpacked = BitPackedVector.unpack(packed, 4, 8);

        assertArrayEquals(indices, unpacked);
    }
}
