package io.whatap.turboquant.hitmap;

import io.whatap.turboquant.core.TurboQuantizer;
import io.whatap.turboquant.hitmap.HitmapReader.HitmapEntry;

import java.util.*;

/**
 * 히트맵 벡터 인덱스.
 * TurboQuant로 120차원 히트맵 벡터를 압축 저장하고,
 * 유사 패턴 검색과 이상 탐지를 제공한다.
 *
 * 히트맵은 int[120]이고 모두 count(건수)라 같은 스케일.
 * → 회전 걱정 없이 TurboQuant를 바로 적용할 수 있는 최적 대상.
 */
public class HitmapVectorIndex {

    private static final int DIM = 120; // HitMapPack1.LENGTH
    private final int numBits;
    private final TurboQuantizer quantizer;
    private final List<IndexEntry> entries = new ArrayList<IndexEntry>();

    public HitmapVectorIndex(int numBits) {
        this.numBits = numBits;
        this.quantizer = new TurboQuantizer(numBits, DIM, 42L);
    }

    /**
     * 히트맵을 인덱스에 추가한다.
     */
    public void add(HitmapEntry entry) {
        float[] vec = normalize(entry.toVector());
        byte[] compressed = quantizer.compress(vec);
        entries.add(new IndexEntry(entry.time, entry.pcode, entry.date, entry.hour,
                compressed, entry.totalHits()));
    }

    /**
     * 주어진 히트맵과 가장 유사한 패턴을 검색한다.
     * @param query 검색할 히트맵
     * @param topK 상위 K개 결과
     * @return 유사도 순 결과 (자기 자신 제외)
     */
    public List<SearchResult> search(HitmapEntry query, int topK) {
        float[] qVec = normalize(query.toVector());
        byte[] qCompressed = quantizer.compress(qVec);
        float[] qRecon = quantizer.decompress(qCompressed);

        List<SearchResult> results = new ArrayList<SearchResult>();
        for (IndexEntry e : entries) {
            if (e.time == query.time && e.pcode.equals(query.pcode)) continue; // skip self

            float[] eRecon = quantizer.decompress(e.compressed);
            float similarity = cosineSimilarity(qRecon, eRecon);

            results.add(new SearchResult(e.time, e.pcode, e.date, e.hour,
                    similarity, e.totalHits));
        }

        Collections.sort(results, new Comparator<SearchResult>() {
            public int compare(SearchResult a, SearchResult b) {
                return Float.compare(b.similarity, a.similarity); // descending
            }
        });

        return results.subList(0, Math.min(topK, results.size()));
    }

    /**
     * 베이스라인 대비 이상 히트맵을 탐지한다.
     * @param current 현재 히트맵
     * @param baselineEntries 최근 N개 히트맵 (정상 베이스라인)
     * @param sigma 임계값 배수 (기본 2.0)
     * @return 이상 여부와 유사도 점수
     */
    public AnomalyResult detectAnomaly(HitmapEntry current, List<HitmapEntry> baselineEntries, float sigma) {
        if (baselineEntries.isEmpty()) {
            return new AnomalyResult(false, 0, 0, "No baseline data");
        }

        float[] currentVec = normalize(current.toVector());

        // 베이스라인 평균 벡터 계산
        float[] meanVec = new float[DIM];
        for (HitmapEntry e : baselineEntries) {
            float[] vec = normalize(e.toVector());
            for (int i = 0; i < DIM; i++) meanVec[i] += vec[i] / baselineEntries.size();
        }

        // 베이스라인 유사도 분포 계산
        double sumSim = 0, sumSimSq = 0;
        for (HitmapEntry e : baselineEntries) {
            float sim = cosineSimilarity(normalize(e.toVector()), meanVec);
            sumSim += sim;
            sumSimSq += sim * sim;
        }
        double meanSim = sumSim / baselineEntries.size();
        double stdSim = Math.sqrt(Math.max(0, sumSimSq / baselineEntries.size() - meanSim * meanSim));

        // 현재 히트맵과 베이스라인 유사도
        float currentSim = cosineSimilarity(currentVec, meanVec);
        double threshold = meanSim - sigma * stdSim;
        boolean isAnomaly = currentSim < threshold;

        return new AnomalyResult(isAnomaly, currentSim, (float) threshold,
                String.format("sim=%.4f, threshold=%.4f (mean=%.4f, std=%.4f)",
                        currentSim, threshold, meanSim, stdSim));
    }

    public int size() { return entries.size(); }

    public int compressedBytesPerEntry() { return quantizer.compressedSize(); }

    /**
     * L2 정규화 — 히트맵 건수 분포를 단위 벡터로 변환.
     * 총 건수가 다르더라도 "분포 형태"만 비교하기 위함.
     */
    private float[] normalize(float[] vec) {
        float norm = 0;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm < 1e-10f) return vec;
        float[] result = new float[vec.length];
        for (int i = 0; i < vec.length; i++) result[i] = vec[i] / norm;
        return result;
    }

    private float cosineSimilarity(float[] a, float[] b) {
        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        normA = (float) Math.sqrt(normA);
        normB = (float) Math.sqrt(normB);
        if (normA < 1e-10f || normB < 1e-10f) return 0;
        return dot / (normA * normB);
    }

    private static class IndexEntry {
        final long time;
        final String pcode, date, hour;
        final byte[] compressed;
        final int totalHits;

        IndexEntry(long time, String pcode, String date, String hour, byte[] compressed, int totalHits) {
            this.time = time; this.pcode = pcode; this.date = date; this.hour = hour;
            this.compressed = compressed; this.totalHits = totalHits;
        }
    }

    public static class SearchResult {
        public final long time;
        public final String pcode, date, hour;
        public final float similarity;
        public final int totalHits;

        SearchResult(long time, String pcode, String date, String hour, float similarity, int totalHits) {
            this.time = time; this.pcode = pcode; this.date = date; this.hour = hour;
            this.similarity = similarity; this.totalHits = totalHits;
        }
    }

    public static class AnomalyResult {
        public final boolean isAnomaly;
        public final float similarity;
        public final float threshold;
        public final String detail;

        AnomalyResult(boolean isAnomaly, float similarity, float threshold, String detail) {
            this.isAnomaly = isAnomaly; this.similarity = similarity;
            this.threshold = threshold; this.detail = detail;
        }
    }
}
