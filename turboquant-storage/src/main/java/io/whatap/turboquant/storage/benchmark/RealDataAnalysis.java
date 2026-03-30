package io.whatap.turboquant.storage.benchmark;

import io.whatap.io.DataInputX;
import io.whatap.lang.value.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 실제 yardbase .dat2 데이터를 파싱해서:
 * 1. 카테고리별 필드 이름과 값 분포 분석
 * 2. 실제 min/max/mean/std 파악
 * 3. 최적 양자화 비트 수 + 코드북 결정
 * 4. 실제 데이터로 양자화 round-trip 오차 측정
 */
public class RealDataAnalysis {

    static final String YARDBASE = "/Users/devload/Documents/whatap-server/yardbase";

    public static void main(String[] args) throws Exception {
        System.out.println("=== 실제 YARD 데이터 분석 ===");
        System.out.println();

        // 카테고리별 필드 통계 수집
        Map<String, Map<String, FieldStats>> categoryStats = new LinkedHashMap<String, Map<String, FieldStats>>();
        // 모든 float 값 수집 (양자화 테스트용)
        Map<String, Map<String, List<Float>>> categoryValues = new LinkedHashMap<String, Map<String, List<Float>>>();

        List<Path> dat2Files = new ArrayList<Path>();
        Files.walk(Paths.get(YARDBASE))
                .filter(p -> p.toString().endsWith(".dat2"))
                .forEach(dat2Files::add);

        int totalRecords = 0;
        int parseErrors = 0;

        for (Path dat2 : dat2Files) {
            String category = dat2.getFileName().toString()
                    .replace("tagc-", "").replace(".dat2", "");

            byte[] raw = Files.readAllBytes(dat2);
            if (raw.length < 4) continue;

            List<byte[]> records = parseInt3Records(raw);
            for (byte[] record : records) {
                if (record.length < 2) continue;
                totalRecords++;
                try {
                    parseFieldRecord(record, category, categoryStats, categoryValues);
                } catch (Exception e) {
                    parseErrors++;
                }
            }
        }

        System.out.println("파싱 완료: " + totalRecords + "개 레코드, 에러: " + parseErrors + "개");
        System.out.println("카테고리: " + categoryStats.size() + "개");
        System.out.println();

        // 카테고리별 필드 분포 출력
        System.out.println("=== 카테고리별 필드 값 분포 ===");
        System.out.println();

        for (Map.Entry<String, Map<String, FieldStats>> catEntry : categoryStats.entrySet()) {
            String cat = catEntry.getKey();
            // 집계 접미사 제거해서 그룹핑
            String baseCat = cat.replaceAll("\\{.*\\}", "");

            System.out.println("[" + cat + "]");
            System.out.println(String.format("  %-25s %8s %12s %12s %12s %12s %6s",
                    "필드명", "개수", "min", "max", "mean", "std", "타입"));
            System.out.println("  " + new String(new char[95]).replace('\0', '-'));

            Map<String, FieldStats> fields = catEntry.getValue();
            for (Map.Entry<String, FieldStats> fEntry : fields.entrySet()) {
                FieldStats fs = fEntry.getValue();
                double mean = fs.count > 0 ? fs.sum / fs.count : 0;
                double variance = fs.count > 1 ? (fs.sumSq / fs.count - mean * mean) : 0;
                double stddev = Math.sqrt(Math.max(0, variance));

                System.out.println(String.format("  %-25s %8d %12.2f %12.2f %12.2f %12.2f %6s",
                        fEntry.getKey(), fs.count, fs.min, fs.max, mean, stddev, fs.type));
            }
            System.out.println();
        }

        // 양자화 테스트: 실제 데이터로
        System.out.println("=== 실제 데이터 양자화 오차 테스트 ===");
        System.out.println();
        System.out.println(String.format("%-35s %6s %8s %8s %8s %8s %8s",
                "카테고리.필드", "개수", "4bit", "6bit", "8bit", "TQ4bit", "TQ8bit"));
        System.out.println(new String(new char[90]).replace('\0', '-'));

        for (Map.Entry<String, Map<String, List<Float>>> catEntry : categoryValues.entrySet()) {
            String cat = catEntry.getKey();
            for (Map.Entry<String, List<Float>> fEntry : catEntry.getValue().entrySet()) {
                String field = fEntry.getKey();
                List<Float> values = fEntry.getValue();
                if (values.size() < 3) continue;

                float fmin = Float.MAX_VALUE, fmax = -Float.MAX_VALUE;
                for (float v : values) {
                    if (v < fmin) fmin = v;
                    if (v > fmax) fmax = v;
                }
                float range = fmax - fmin;
                if (range < 1e-6f) range = 1f;

                // 스칼라 N-bit 오차
                double err4 = scalarError(values, fmin, range, 4);
                double err6 = scalarError(values, fmin, range, 6);
                double err8 = scalarError(values, fmin, range, 8);

                // TurboQuant 오차 (같은 필드 값들을 벡터로)
                double errTQ4 = tqError(values, fmin, range, 4);
                double errTQ8 = tqError(values, fmin, range, 8);

                String key = cat + "." + field;
                if (key.length() > 35) key = key.substring(0, 32) + "...";

                System.out.println(String.format("%-35s %6d %7.2f%% %7.2f%% %7.2f%% %7.2f%% %7.2f%%",
                        key, values.size(), err4, err6, err8, errTQ4, errTQ8));
            }
        }
    }

    static void parseFieldRecord(byte[] record, String category,
                                  Map<String, Map<String, FieldStats>> categoryStats,
                                  Map<String, Map<String, List<Float>>> categoryValues) {
        DataInputX din = new DataInputX(record);

        // TagCountPack._readMap 방식으로 파싱
        // 형식: [boolean:hasMore][decimal:fieldIdx][value]...
        // 하지만 fieldIdx → fieldName 매핑이 없으므로, 직접 Value만 추출
        // 실제 .dat2는 writeField() 결과: _writeMap(fields, info.field, dout)
        // _writeMap: while(hasMore) { boolean(true), decimal(idx), writeValue(value) }

        Map<String, FieldStats> fields = categoryStats.get(category);
        if (fields == null) { fields = new LinkedHashMap<String, FieldStats>(); categoryStats.put(category, fields); }
        Map<String, List<Float>> values = categoryValues.get(category);
        if (values == null) { values = new LinkedHashMap<String, List<Float>>(); categoryValues.put(category, values); }

        int fieldIdx = 0;
        try {
            while (din.readBoolean()) {
                long idx = din.readDecimal();
                Value value = din.readValue();
                String fieldName = "field_" + idx;

                FieldStats fs = fields.get(fieldName);
                if (fs == null) { fs = new FieldStats(); fields.put(fieldName, fs); }

                if (value instanceof MetricValue) {
                    MetricValue mv = (MetricValue) value;
                    fs.type = "Metric";
                    if (mv.count > 0) {
                        double avg = mv.avg();
                        fs.add(avg);
                        List<Float> vlist = values.get(fieldName);
                        if (vlist == null) { vlist = new ArrayList<Float>(); values.put(fieldName, vlist); }
                        vlist.add((float) avg);
                        // min/max/last도
                        fs.add(mv.min);
                        fs.add(mv.max);
                        fs.add(mv.last);
                        vlist.add((float) mv.min);
                        vlist.add((float) mv.max);
                        vlist.add((float) mv.last);
                    }
                } else if (value instanceof FloatValue) {
                    fs.type = "Float";
                    fs.add(((FloatValue) value).value);
                    List<Float> vlist = values.get(fieldName);
                    if (vlist == null) { vlist = new ArrayList<Float>(); values.put(fieldName, vlist); }
                    vlist.add(((FloatValue) value).value);
                } else if (value instanceof DoubleValue) {
                    fs.type = "Double";
                    fs.add(((DoubleValue) value).value);
                } else if (value instanceof DecimalValue) {
                    fs.type = "Decimal";
                    fs.add(((DecimalValue) value).value);
                } else if (value instanceof IntValue) {
                    fs.type = "Int";
                    fs.add(((IntValue) value).intValue());
                } else if (value instanceof LongValue) {
                    fs.type = "Long";
                    fs.add(((LongValue) value).longValue());
                } else {
                    fs.type = value != null ? value.getClass().getSimpleName() : "null";
                }
                fieldIdx++;
            }
        } catch (Exception e) {
            // 파싱 끝
        }
    }

    static double scalarError(List<Float> values, float min, float range, int bits) {
        int levels = 1 << bits;
        double totalRel = 0;
        int count = 0;
        for (float v : values) {
            int q = Math.round((v - min) / range * (levels - 1));
            q = Math.max(0, Math.min(levels - 1, q));
            float restored = min + (q / (float)(levels - 1)) * range;
            if (Math.abs(v) > 0.001) {
                totalRel += Math.abs(v - restored) / Math.abs(v) * 100;
                count++;
            }
        }
        return count > 0 ? totalRel / count : 0;
    }

    static double tqError(List<Float> values, float min, float range, int bits) {
        if (values.size() < 4) return 0;
        // 같은 필드 값들을 벡터로 묶어서 TurboQuant 적용
        int dim = Math.min(values.size(), 64); // 최대 64차원
        io.whatap.turboquant.core.TurboQuantizer tq =
                new io.whatap.turboquant.core.TurboQuantizer(bits, dim, 42L);

        float[] vec = new float[dim];
        for (int i = 0; i < dim; i++) {
            vec[i] = (values.get(i) - min) / range; // normalize [0,1]
        }

        float[] restored = tq.decompress(tq.compress(vec));

        double totalRel = 0;
        int count = 0;
        for (int i = 0; i < dim; i++) {
            float orig = values.get(i);
            float rest = restored[i] * range + min;
            if (Math.abs(orig) > 0.001) {
                totalRel += Math.abs(orig - rest) / Math.abs(orig) * 100;
                count++;
            }
        }
        return count > 0 ? totalRel / count : 0;
    }

    static List<byte[]> parseInt3Records(byte[] data) {
        List<byte[]> records = new ArrayList<byte[]>();
        int pos = 0;
        while (pos + 3 <= data.length) {
            int len = ((data[pos] & 0xFF) << 16) | ((data[pos + 1] & 0xFF) << 8) | (data[pos + 2] & 0xFF);
            pos += 3;
            if (len == 0x7FFFFF) {
                if (pos + 4 > data.length) break;
                len = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                        | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                pos += 4;
            }
            if (len < 0 || pos + len > data.length) break;
            byte[] record = new byte[len];
            System.arraycopy(data, pos, record, 0, len);
            pos += len;
            records.add(record);
        }
        return records;
    }

    static class FieldStats {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        double sum = 0, sumSq = 0;
        int count = 0;
        String type = "";

        void add(double v) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
            sumSq += v * v;
            count++;
        }
    }
}
