package io.whatap.turboquant.search.api;

import io.whatap.turboquant.core.RotationCache;
import io.whatap.turboquant.search.index.CompressedVectorStore;
import io.whatap.turboquant.search.index.TopKResult;
import io.whatap.turboquant.search.vector.ServerStateVector;
import io.whatap.turboquant.search.vector.VectorBuilder;
import io.whatap.turboquant.util.FloatArrayOps;

import java.util.*;

/**
 * End-to-end similarity search demo with Phase 6: QJL dimension comparison.
 */
public class SimilaritySearchMain {

    private static final int NUM_AGENTS = 50;
    private static final int TIME_STEPS = 60;
    private static final int DIMENSION = ServerStateVector.NUM_METRICS * TIME_STEPS; // 480
    private static final int TOP_K = 5;

    public static void main(String[] args) {
        System.out.println("=== TurboQuant Similarity Search Demo ===");
        System.out.println("Agents: " + NUM_AGENTS + ", TimeSteps: " + TIME_STEPS + ", Dim: " + DIMENSION);
        System.out.println();

        Random rng = new Random(42);
        VectorBuilder builder = new VectorBuilder(TIME_STEPS);

        // Create 5 clusters of 10 agents each
        int clustersCount = 5;
        int agentsPerCluster = NUM_AGENTS / clustersCount;

        List<ServerStateVector> allVectors = new ArrayList<ServerStateVector>();
        int[] clusterOf = new int[NUM_AGENTS];

        for (int c = 0; c < clustersCount; c++) {
            double cpuBase = 20 + c * 15;
            double memBase = 30 + c * 12;
            double tpsBase = 50 + c * 40;

            for (int a = 0; a < agentsPerCluster; a++) {
                int oid = c * agentsPerCluster + a + 1000;
                int idx = c * agentsPerCluster + a;
                clusterOf[idx] = c;

                List<Map<String, Double>> snapshots = new ArrayList<Map<String, Double>>();
                for (int t = 0; t < TIME_STEPS; t++) {
                    Map<String, Double> snap = new LinkedHashMap<String, Double>();
                    snap.put("cpu", cpuBase + rng.nextGaussian() * 3 + 2 * Math.sin(2 * Math.PI * t / 30.0));
                    snap.put("mem", memBase + rng.nextGaussian() * 2);
                    snap.put("heap_use", 50 + rng.nextGaussian() * 5);
                    snap.put("tps", tpsBase + rng.nextGaussian() * 10);
                    snap.put("resp_time", 100 + c * 50 + rng.nextGaussian() * 20);
                    snap.put("gc_count", 3.0 + rng.nextGaussian());
                    snap.put("service_error", 1.0 + rng.nextDouble() * 2);
                    snap.put("act_svc_count", 2.0 + rng.nextGaussian());
                    snapshots.add(snap);
                }
                allVectors.add(builder.build(oid, System.currentTimeMillis(), snapshots));
            }
        }

        System.out.println("Generated " + allVectors.size() + " vectors in " + clustersCount + " clusters");
        System.out.println();

        // --- Phase 6: QJL Dimension Comparison ---
        System.out.println("=== Phase 6: QJL Projection Dimension Comparison ===");
        System.out.println(String.format("%-12s %15s %12s %15s %15s",
                "QJL Dim", "Cluster Acc", "Recall@5", "Compress(ms)", "Search(ms)"));
        System.out.println(new String(new char[75]).replace('\0', '-'));

        int[] qjlDims = {16, 32, 64, 128, 256};
        for (int qjlDim : qjlDims) {
            RotationCache.clear(); // fair comparison
            SearchMetrics m = runSearch(allVectors, clusterOf, DIMENSION, 4, qjlDim, TOP_K);
            System.out.println(String.format("%-12d %14.1f%% %11.1f%% %14.1f %14.2f",
                    qjlDim, m.clusterAccuracy, m.recall, m.compressTimeMs, m.avgSearchMs));
        }

        // Sample query with best config (128)
        System.out.println();
        System.out.println("--- Sample Query with QJL=128: Agent 1000 (Cluster 0) Top-5 ---");
        CompressedVectorStore bestStore = new CompressedVectorStore(DIMENSION, 4, 128);
        for (ServerStateVector sv : allVectors) bestStore.add(sv);
        List<TopKResult> sampleResults = bestStore.search(allVectors.get(0), TOP_K + 1);
        int rank = 1;
        for (TopKResult r : sampleResults) {
            if (r.oid == allVectors.get(0).getOid()) continue;
            int rIdx = -1;
            for (int i = 0; i < allVectors.size(); i++) {
                if (allVectors.get(i).getOid() == r.oid) { rIdx = i; break; }
            }
            int rc = rIdx >= 0 ? clusterOf[rIdx] : -1;
            System.out.println(String.format("  #%d: oid=%d, cluster=%d, score=%.4f %s",
                    rank, r.oid, rc, r.score, rc == clusterOf[0] ? "(SAME)" : "(DIFF)"));
            rank++;
            if (rank > TOP_K) break;
        }
    }

    static SearchMetrics runSearch(List<ServerStateVector> allVectors, int[] clusterOf,
                                    int dimension, int numBits, int qjlDim, int topK) {
        CompressedVectorStore store = new CompressedVectorStore(dimension, numBits, qjlDim);
        long compressStart = System.nanoTime();
        for (ServerStateVector sv : allVectors) store.add(sv);
        double compressTimeMs = (System.nanoTime() - compressStart) / 1e6;

        int correctInCluster = 0, totalChecks = 0, recallHits = 0;
        long totalSearchTime = 0;

        for (int q = 0; q < allVectors.size(); q++) {
            ServerStateVector query = allVectors.get(q);
            int queryCluster = clusterOf[q];

            long searchStart = System.nanoTime();
            List<TopKResult> results = store.search(query, topK + 1);
            totalSearchTime += System.nanoTime() - searchStart;

            // Brute-force exact top-K
            List<int[]> exactScores = new ArrayList<int[]>();
            for (int i = 0; i < allVectors.size(); i++) {
                if (i == q) continue;
                float cos = FloatArrayOps.cosineSimilarity(query.getVector(), allVectors.get(i).getVector());
                exactScores.add(new int[]{i, (int) (cos * 10000)});
            }
            exactScores.sort(new Comparator<int[]>() {
                public int compare(int[] a, int[] b) { return b[1] - a[1]; }
            });
            Set<Integer> exactTopKOids = new HashSet<Integer>();
            for (int i = 0; i < Math.min(topK, exactScores.size()); i++) {
                exactTopKOids.add(allVectors.get(exactScores.get(i)[0]).getOid());
            }

            for (TopKResult r : results) {
                if (r.oid == query.getOid()) continue;
                totalChecks++;
                int rIdx = -1;
                for (int i = 0; i < allVectors.size(); i++) {
                    if (allVectors.get(i).getOid() == r.oid) { rIdx = i; break; }
                }
                if (rIdx >= 0 && clusterOf[rIdx] == queryCluster) correctInCluster++;
                if (exactTopKOids.contains(r.oid)) recallHits++;
            }
        }

        SearchMetrics m = new SearchMetrics();
        m.clusterAccuracy = totalChecks > 0 ? (double) correctInCluster / totalChecks * 100 : 0;
        m.recall = totalChecks > 0 ? (double) recallHits / totalChecks * 100 : 0;
        m.compressTimeMs = compressTimeMs;
        m.avgSearchMs = totalSearchTime / (double) allVectors.size() / 1e6;
        return m;
    }

    static class SearchMetrics {
        double clusterAccuracy;
        double recall;
        double compressTimeMs;
        double avgSearchMs;
    }
}
