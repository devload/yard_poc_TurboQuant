package io.whatap.turboquant.hitmap.quantile;

import io.whatap.turboquant.core.TurboQuantizer;

import java.util.*;

/**
 * Phase 10: KLL Quantile 분포 벡터 검색.
 *
 * 응답시간 퍼센타일 분포를 벡터로 표현하여 유사 패턴 검색.
 * 퍼센타일 값들은 모두 ms 단위로 같은 스케일 → TurboQuant 직접 적용 가능.
 *
 * 벡터: [P10, P20, P30, P40, P50, P60, P70, P75, P80, P85, P90, P95, P99, P99.9]
 * 차원: 14 (저차원이지만 같은 스케일이라 회전 OK)
 *
 * 더 풍부한 분포를 위해 히스토그램 버킷(50개)도 지원.
 */
public class QuantileIndex {

    static final int PERCENTILE_DIM = 14;
    static final float[] PERCENTILE_POINTS = {10,20,30,40,50,60,70,75,80,85,90,95,99,99.9f};

    private final int numBits;
    private final TurboQuantizer quantizer;
    private final List<UrlEntry> entries = new ArrayList<UrlEntry>();

    public QuantileIndex(int numBits, int dim) {
        this.numBits = numBits;
        this.quantizer = new TurboQuantizer(numBits, dim, 42L);
    }

    /**
     * URL별 퍼센타일 분포를 인덱스에 추가.
     */
    public void add(String url, long time, float[] percentiles) {
        float[] normalized = normalizeLog(percentiles);
        byte[] compressed = quantizer.compress(normalized);
        entries.add(new UrlEntry(url, time, compressed, percentiles));
    }

    /**
     * 유사 응답시간 분포 URL 검색.
     */
    public List<SearchResult> search(float[] queryPercentiles, String queryUrl, int topK) {
        float[] qNorm = normalizeLog(queryPercentiles);
        float[] qRecon = quantizer.decompress(quantizer.compress(qNorm));

        List<SearchResult> results = new ArrayList<SearchResult>();
        for (UrlEntry e : entries) {
            if (e.url.equals(queryUrl)) continue;
            float[] eRecon = quantizer.decompress(e.compressed);
            float sim = cosineSim(qRecon, eRecon);
            results.add(new SearchResult(e.url, e.time, sim, e.originalPercentiles));
        }
        Collections.sort(results);
        return results.subList(0, Math.min(topK, results.size()));
    }

    /**
     * 분포 변화 감지: 이전 분포와 현재 분포 비교.
     */
    public float compareDistributions(float[] prev, float[] current) {
        float[] pNorm = normalizeLog(prev);
        float[] cNorm = normalizeLog(current);
        float[] pRecon = quantizer.decompress(quantizer.compress(pNorm));
        float[] cRecon = quantizer.decompress(quantizer.compress(cNorm));
        return cosineSim(pRecon, cRecon);
    }

    public int size() { return entries.size(); }
    public int compressedBytesPerEntry() { return quantizer.compressedSize(); }

    /**
     * 로그 스케일 정규화 — 응답시간은 log-normal 분포라 log 변환 후 정규화.
     */
    private float[] normalizeLog(float[] vals) {
        float[] result = new float[vals.length];
        for (int i = 0; i < vals.length; i++) {
            result[i] = (float) Math.log1p(Math.max(0, vals[i])); // log(1+x)
        }
        // L2 normalize
        float norm = 0;
        for (float v : result) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 1e-10f) {
            for (int i = 0; i < result.length; i++) result[i] /= norm;
        }
        return result;
    }

    private float cosineSim(float[] a, float[] b) {
        float dot=0,na=0,nb=0;
        for(int i=0;i<a.length;i++){dot+=a[i]*b[i];na+=a[i]*a[i];nb+=b[i]*b[i];}
        na=(float)Math.sqrt(na);nb=(float)Math.sqrt(nb);
        return (na<1e-10f||nb<1e-10f)?0:dot/(na*nb);
    }

    static class UrlEntry {
        final String url; final long time; final byte[] compressed; final float[] originalPercentiles;
        UrlEntry(String url,long time,byte[] c,float[] p) {
            this.url=url;this.time=time;this.compressed=c;this.originalPercentiles=p;
        }
    }

    public static class SearchResult implements Comparable<SearchResult> {
        public final String url; public final long time;
        public final float similarity; public final float[] percentiles;
        SearchResult(String url,long time,float sim,float[] p) {
            this.url=url;this.time=time;this.similarity=sim;this.percentiles=p;
        }
        public int compareTo(SearchResult o) { return Float.compare(o.similarity, this.similarity); }
    }
}
