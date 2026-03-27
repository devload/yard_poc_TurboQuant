package io.whatap.turboquant.core;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches RandomRotation matrices by (dimension, seed) key.
 * QR decomposition is O(n^3) and dominates TurboQuant initialization.
 * By caching, we pay the cost only once per unique (dim, seed) pair.
 */
public class RotationCache {

    private static final ConcurrentHashMap<Long, RandomRotation> cache =
            new ConcurrentHashMap<Long, RandomRotation>();

    /**
     * Get or create a cached RandomRotation for the given dimension and seed.
     */
    public static RandomRotation get(int dimension, long seed) {
        long key = ((long) dimension << 32) ^ seed;
        RandomRotation existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        RandomRotation rotation = new RandomRotation(dimension, seed);
        cache.putIfAbsent(key, rotation);
        return cache.get(key);
    }

    /**
     * Clear all cached rotations (for testing/memory management).
     */
    public static void clear() {
        cache.clear();
    }

    /**
     * Number of cached rotation matrices.
     */
    public static int size() {
        return cache.size();
    }
}
