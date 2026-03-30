package io.whatap.turboquant.hitmap.counter;

import io.whatap.turboquant.core.TurboQuantizer;

import java.util.*;

/**
 * Phase 9: CounterPack 그룹 회전 벡터 검색.
 *
 * 78개 메트릭을 스케일별 3그룹으로 나눠서 각 그룹 내에서만 TurboQuant 회전.
 * - 그룹A (퍼센트): cpu, mem, disk, swap 등 0~100 스케일
 * - 그룹B (카운트): tps, service_count, sql_count, gc_count 등 0~10000
 * - 그룹C (시간/크기): resp_time, heap_use, gc_time 등 큰 수
 *
 * 각 그룹을 중심값 빼기 + 정규화 후 TurboQuant 적용.
 */
public class GroupRotationIndex {

    // 그룹 정의 (실제 CounterPack 필드 기반)
    public static final String[] GROUP_A_NAMES = {"cpu","cpu_sys","cpu_usr","cpu_proc","mem","swap","disk"};
    public static final float[] GROUP_A_CENTERS = {50, 20, 30, 15, 65, 10, 50};
    public static final float[] GROUP_A_RANGES = {100, 100, 100, 100, 100, 100, 100};

    public static final String[] GROUP_B_NAMES = {"tps","service_count","service_error","gc_count","sql_count","httpc_count","act_svc_count","thread_count"};
    public static final float[] GROUP_B_CENTERS = {200, 100, 5, 10, 50, 30, 5, 100};
    public static final float[] GROUP_B_RANGES = {5000, 2000, 100, 100, 500, 300, 50, 500};

    public static final String[] GROUP_C_NAMES = {"resp_time","heap_use","heap_tot","gc_time","sql_time","httpc_time","service_time"};
    public static final float[] GROUP_C_CENTERS = {500, 2000, 4000, 50, 100, 200, 1000};
    public static final float[] GROUP_C_RANGES = {30000, 8192, 16384, 1000, 5000, 5000, 60000};

    public static final int DIM_A = GROUP_A_NAMES.length;  // 7
    public static final int DIM_B = GROUP_B_NAMES.length;  // 8
    public static final int DIM_C = GROUP_C_NAMES.length;  // 7
    static final int TOTAL_DIM = DIM_A + DIM_B + DIM_C; // 22 (대표 메트릭)

    private final int numBits;
    private final TurboQuantizer tqA, tqB, tqC;
    private final List<ServerEntry> entries = new ArrayList<ServerEntry>();

    public GroupRotationIndex(int numBits) {
        this.numBits = numBits;
        this.tqA = new TurboQuantizer(numBits, DIM_A, 42L);
        this.tqB = new TurboQuantizer(numBits, DIM_B, 43L);
        this.tqC = new TurboQuantizer(numBits, DIM_C, 44L);
    }

    /**
     * 서버 상태를 인덱스에 추가.
     */
    public void add(int oid, long time, Map<String, Float> metrics) {
        float[] normA = normalizeGroup(metrics, GROUP_A_NAMES, GROUP_A_CENTERS, GROUP_A_RANGES);
        float[] normB = normalizeGroup(metrics, GROUP_B_NAMES, GROUP_B_CENTERS, GROUP_B_RANGES);
        float[] normC = normalizeGroup(metrics, GROUP_C_NAMES, GROUP_C_CENTERS, GROUP_C_RANGES);

        byte[] compA = tqA.compress(normA);
        byte[] compB = tqB.compress(normB);
        byte[] compC = tqC.compress(normC);

        entries.add(new ServerEntry(oid, time, compA, compB, compC, metrics));
    }

    /**
     * 유사 서버 검색.
     */
    public List<SearchResult> search(Map<String, Float> query, int oid, int topK) {
        float[] qA = normalizeGroup(query, GROUP_A_NAMES, GROUP_A_CENTERS, GROUP_A_RANGES);
        float[] qB = normalizeGroup(query, GROUP_B_NAMES, GROUP_B_CENTERS, GROUP_B_RANGES);
        float[] qC = normalizeGroup(query, GROUP_C_NAMES, GROUP_C_CENTERS, GROUP_C_RANGES);

        float[] qReconA = tqA.decompress(tqA.compress(qA));
        float[] qReconB = tqB.decompress(tqB.compress(qB));
        float[] qReconC = tqC.decompress(tqC.compress(qC));

        List<SearchResult> results = new ArrayList<SearchResult>();
        for (ServerEntry e : entries) {
            if (e.oid == oid && e.time == query.hashCode()) continue;

            float[] eA = tqA.decompress(e.compA);
            float[] eB = tqB.decompress(e.compB);
            float[] eC = tqC.decompress(e.compC);

            // 그룹별 유사도 계산 후 가중 평균
            float simA = cosineSim(qReconA, eA);
            float simB = cosineSim(qReconB, eB);
            float simC = cosineSim(qReconC, eC);
            float avgSim = (simA + simB + simC) / 3.0f;

            results.add(new SearchResult(e.oid, e.time, avgSim, simA, simB, simC, e.metrics));
        }

        Collections.sort(results);
        return results.subList(0, Math.min(topK, results.size()));
    }

    /**
     * 그룹 회전 정확도 측정 — 원본 vs 복원 비교.
     */
    public Map<String, Float> measureAccuracy(Map<String, Float> metrics) {
        Map<String, Float> errors = new LinkedHashMap<String, Float>();
        measureGroupAccuracy(metrics, GROUP_A_NAMES, GROUP_A_CENTERS, GROUP_A_RANGES, tqA, errors);
        measureGroupAccuracy(metrics, GROUP_B_NAMES, GROUP_B_CENTERS, GROUP_B_RANGES, tqB, errors);
        measureGroupAccuracy(metrics, GROUP_C_NAMES, GROUP_C_CENTERS, GROUP_C_RANGES, tqC, errors);
        return errors;
    }

    private void measureGroupAccuracy(Map<String, Float> metrics, String[] names, float[] centers, float[] ranges,
                                       TurboQuantizer tq, Map<String, Float> errors) {
        float[] norm = normalizeGroup(metrics, names, centers, ranges);
        float[] recon = tq.decompress(tq.compress(norm));
        for (int i = 0; i < names.length; i++) {
            float orig = metrics.containsKey(names[i]) ? metrics.get(names[i]) : centers[i];
            float restored = recon[i] * ranges[i] + centers[i];
            float relErr = Math.abs(orig) > 0.01f ? Math.abs(orig - restored) / Math.abs(orig) * 100 : 0;
            errors.put(names[i], relErr);
        }
    }

    public int size() { return entries.size(); }
    public int compressedBytesPerEntry() { return tqA.compressedSize() + tqB.compressedSize() + tqC.compressedSize(); }

    private float[] normalizeGroup(Map<String, Float> metrics, String[] names, float[] centers, float[] ranges) {
        float[] result = new float[names.length];
        for (int i = 0; i < names.length; i++) {
            float val = metrics.containsKey(names[i]) ? metrics.get(names[i]) : centers[i];
            result[i] = (val - centers[i]) / ranges[i];
        }
        return result;
    }

    private float cosineSim(float[] a, float[] b) {
        float dot=0,na=0,nb=0;
        for(int i=0;i<a.length;i++){dot+=a[i]*b[i];na+=a[i]*a[i];nb+=b[i]*b[i];}
        na=(float)Math.sqrt(na);nb=(float)Math.sqrt(nb);
        return (na<1e-10f||nb<1e-10f)?0:dot/(na*nb);
    }

    static class ServerEntry {
        final int oid; final long time;
        final byte[] compA, compB, compC;
        final Map<String, Float> metrics;
        ServerEntry(int oid,long time,byte[] a,byte[] b,byte[] c,Map<String,Float> m) {
            this.oid=oid;this.time=time;this.compA=a;this.compB=b;this.compC=c;this.metrics=m;
        }
    }

    public static class SearchResult implements Comparable<SearchResult> {
        public final int oid; public final long time;
        public final float similarity, simA, simB, simC;
        public final Map<String, Float> metrics;
        SearchResult(int oid,long time,float sim,float a,float b,float c,Map<String,Float> m) {
            this.oid=oid;this.time=time;this.similarity=sim;this.simA=a;this.simB=b;this.simC=c;this.metrics=m;
        }
        public int compareTo(SearchResult o) { return Float.compare(o.similarity, this.similarity); }
    }
}
