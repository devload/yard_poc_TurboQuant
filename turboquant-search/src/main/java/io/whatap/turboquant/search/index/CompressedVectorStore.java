package io.whatap.turboquant.search.index;

import io.whatap.turboquant.core.QJLProjection;
import io.whatap.turboquant.core.TurboQuantizer;
import io.whatap.turboquant.search.vector.ServerStateVector;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores TurboQuant-compressed server state vectors.
 * Supports approximate inner product search in compressed space.
 */
public class CompressedVectorStore {

    private final int dimension;
    private final int numBits;
    private final TurboQuantizer quantizer;
    private final QJLProjection qjl;
    private final List<CompressedEntry> entries;

    public CompressedVectorStore(int dimension, int numBits, int qjlProjectionDim) {
        this.dimension = dimension;
        this.numBits = numBits;
        this.quantizer = new TurboQuantizer(numBits, dimension, 42L);
        this.qjl = new QJLProjection(dimension, qjlProjectionDim, 123L);
        this.entries = new ArrayList<CompressedEntry>();
    }

    /**
     * Add a server state vector to the store.
     */
    public void add(ServerStateVector sv) {
        float[] vec = sv.getVector();

        // Stage 1: TurboQuant compression
        byte[] quantized = quantizer.compress(vec);

        // Compute residual for QJL
        float[] reconstructed = quantizer.decompress(quantized);
        float[] residual = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            residual[i] = vec[i] - reconstructed[i];
        }

        // Stage 2: QJL projection
        QJLProjection.ProjectionResult qjlResult = qjl.project(residual);

        entries.add(new CompressedEntry(sv.getOid(), sv.getTimestamp(),
                quantized, qjlResult.signs, qjlResult.norm));
    }

    /**
     * Search for top-K most similar vectors using approximate inner product.
     */
    public List<TopKResult> search(ServerStateVector query, int topK) {
        float[] qVec = query.getVector();
        byte[] qQuantized = quantizer.compress(qVec);
        float[] qReconstructed = quantizer.decompress(qQuantized);

        // Compute residual for query
        float[] qResidual = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            qResidual[i] = qVec[i] - qReconstructed[i];
        }
        QJLProjection.ProjectionResult qQjl = qjl.project(qResidual);

        // Score all entries
        List<TopKResult> allScores = new ArrayList<TopKResult>();
        for (CompressedEntry entry : entries) {
            float[] eReconstructed = quantizer.decompress(entry.quantized);

            // Base inner product from quantized vectors
            float baseDot = 0;
            for (int i = 0; i < dimension; i++) {
                baseDot += qReconstructed[i] * eReconstructed[i];
            }

            // QJL correction
            float[] qResRecon = qjl.reconstruct(qQjl.signs, qQjl.norm);
            float[] eResRecon = qjl.reconstruct(entry.qjlSigns, entry.qjlNorm);
            float resDot = 0;
            for (int i = 0; i < dimension; i++) {
                resDot += qResRecon[i] * eResRecon[i];
            }

            float totalScore = baseDot + resDot;

            // Normalize to cosine similarity
            float qNorm = 0, eNorm = 0;
            for (int i = 0; i < dimension; i++) {
                float qFull = qReconstructed[i] + qResRecon[i];
                float eFull = eReconstructed[i] + eResRecon[i];
                qNorm += qFull * qFull;
                eNorm += eFull * eFull;
            }
            qNorm = (float) Math.sqrt(qNorm);
            eNorm = (float) Math.sqrt(eNorm);
            float cosine = (qNorm > 1e-10f && eNorm > 1e-10f) ? totalScore / (qNorm * eNorm) : 0f;

            allScores.add(new TopKResult(entry.oid, entry.timestamp, cosine));
        }

        // Sort by score descending
        allScores.sort(null); // TopKResult implements Comparable
        return allScores.subList(0, Math.min(topK, allScores.size()));
    }

    public int size() { return entries.size(); }

    private static class CompressedEntry {
        final int oid;
        final long timestamp;
        final byte[] quantized;
        final byte[] qjlSigns;
        final float qjlNorm;

        CompressedEntry(int oid, long timestamp, byte[] quantized, byte[] qjlSigns, float qjlNorm) {
            this.oid = oid;
            this.timestamp = timestamp;
            this.quantized = quantized;
            this.qjlSigns = qjlSigns;
            this.qjlNorm = qjlNorm;
        }
    }
}
