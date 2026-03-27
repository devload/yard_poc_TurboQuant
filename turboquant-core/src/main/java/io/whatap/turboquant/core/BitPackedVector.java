package io.whatap.turboquant.core;

/**
 * Packs/unpacks integer indices into bit-packed byte arrays.
 * Supports 1-8 bits per element.
 */
public class BitPackedVector {

    /**
     * Pack indices into a byte array using numBits per index.
     */
    public static byte[] pack(int[] indices, int numBits, int dimension) {
        int totalBits = dimension * numBits;
        int numBytes = (totalBits + 7) / 8;
        byte[] packed = new byte[numBytes];

        int bitPos = 0;
        for (int i = 0; i < dimension; i++) {
            int value = indices[i];
            for (int b = numBits - 1; b >= 0; b--) {
                int bit = (value >> b) & 1;
                int byteIdx = bitPos / 8;
                int bitIdx = 7 - (bitPos % 8);
                packed[byteIdx] |= (byte) (bit << bitIdx);
                bitPos++;
            }
        }

        return packed;
    }

    /**
     * Unpack indices from a bit-packed byte array.
     */
    public static int[] unpack(byte[] packed, int numBits, int dimension) {
        int[] indices = new int[dimension];

        int bitPos = 0;
        for (int i = 0; i < dimension; i++) {
            int value = 0;
            for (int b = numBits - 1; b >= 0; b--) {
                int byteIdx = bitPos / 8;
                int bitIdx = 7 - (bitPos % 8);
                int bit = (packed[byteIdx] >> bitIdx) & 1;
                value |= (bit << b);
                bitPos++;
            }
            indices[i] = value;
        }

        return indices;
    }

    /**
     * Calculate packed byte array size for given dimension and bits.
     */
    public static int packedSize(int dimension, int numBits) {
        return (dimension * numBits + 7) / 8;
    }
}
