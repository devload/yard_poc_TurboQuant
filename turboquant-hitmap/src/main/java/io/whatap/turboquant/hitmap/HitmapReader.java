package io.whatap.turboquant.hitmap;

import io.whatap.io.DataInputX;
import io.whatap.lang.pack.HitMapPack1;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * yardbase에서 실제 HitMapPack1 데이터를 읽어오는 리더.
 *
 * db3tx 디렉토리 구조:
 *   db3_rum_pageload_hitmap.tim  — 시간 인덱스 [time(8B), pos(8B)] pairs
 *   db3_rum_pageload_hitmap.dat  — HitMapPack1 바이너리 데이터
 *
 * WriteSAM 포맷: [int3(3B) length][data]...
 */
public class HitmapReader {

    /**
     * yardbase 루트에서 모든 히트맵 데이터를 읽어온다.
     * @param yardbasePath yardbase 경로 (예: /path/to/yardbase)
     * @return 시간순 정렬된 히트맵 목록
     */
    public static List<HitmapEntry> readAll(String yardbasePath) throws Exception {
        List<HitmapEntry> entries = new ArrayList<HitmapEntry>();

        // db3tx 디렉토리에서 히트맵 .dat 파일 찾기
        Files.walk(Paths.get(yardbasePath))
                .filter(p -> p.toString().contains("db3tx") &&
                        p.toString().contains("hitmap") &&
                        p.toString().endsWith(".dat"))
                .forEach(datFile -> {
                    try {
                        // 같은 디렉토리의 .tim 파일
                        String timPath = datFile.toString().replace(".dat", ".tim");
                        File timFile = new File(timPath);
                        if (!timFile.exists()) return;

                        // 경로에서 pcode, date, hour 추출
                        // 예: yardbase/1/20260202/02/db3tx/db3_rum_pageload_hitmap.dat
                        String[] parts = datFile.toString().split("/");
                        String pcode = "unknown";
                        String date = "unknown";
                        String hour = "unknown";
                        for (int i = 0; i < parts.length; i++) {
                            if (parts[i].equals("db3tx") && i >= 3) {
                                hour = parts[i - 1];
                                date = parts[i - 2];
                                pcode = parts[i - 3];
                            }
                        }

                        // .tim 읽기: [time(8B), pos(8B)] 반복
                        byte[] timBytes = Files.readAllBytes(timFile.toPath());
                        byte[] datBytes = Files.readAllBytes(datFile);

                        DataInputX timIn = new DataInputX(timBytes);
                        int numEntries = timBytes.length / 16; // 각 엔트리 16B

                        for (int i = 0; i < numEntries; i++) {
                            long time = timIn.readLong();
                            long pos = timIn.readLong();

                            if (pos >= 0 && pos < datBytes.length) {
                                try {
                                    byte[] record = readRecord(datBytes, (int) pos);
                                    if (record != null && record.length > 4) {
                                        HitMapPack1 pack = new HitMapPack1();
                                        pack.read(new DataInputX(record));

                                        HitmapEntry entry = new HitmapEntry();
                                        entry.time = time;
                                        entry.pcode = pcode;
                                        entry.date = date;
                                        entry.hour = hour;
                                        entry.hit = Arrays.copyOf(pack.hit, HitMapPack1.LENGTH);
                                        entry.error = Arrays.copyOf(pack.error, HitMapPack1.LENGTH);
                                        entry.source = datFile.toString();
                                        entries.add(entry);
                                    }
                                } catch (Exception e) {
                                    // 파싱 실패 — 건너뜀
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Error reading " + datFile + ": " + e.getMessage());
                    }
                });

        // 시간순 정렬
        entries.sort(new Comparator<HitmapEntry>() {
            public int compare(HitmapEntry a, HitmapEntry b) {
                return Long.compare(a.time, b.time);
            }
        });

        return entries;
    }

    /**
     * WriteSAM 포맷에서 특정 위치의 레코드를 읽는다.
     * int3(3B) length + data
     */
    private static byte[] readRecord(byte[] data, int pos) {
        if (pos + 3 > data.length) return null;
        int len = ((data[pos] & 0xFF) << 16) | ((data[pos + 1] & 0xFF) << 8) | (data[pos + 2] & 0xFF);
        pos += 3;
        if (len == 0x7FFFFF) {
            if (pos + 4 > data.length) return null;
            len = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
        }
        if (len < 0 || pos + len > data.length) return null;
        byte[] record = new byte[len];
        System.arraycopy(data, pos, record, 0, len);
        return record;
    }

    /**
     * 히트맵 엔트리
     */
    public static class HitmapEntry {
        public long time;
        public String pcode;
        public String date;
        public String hour;
        public int[] hit;
        public int[] error;
        public String source;

        public int totalHits() {
            int sum = 0;
            for (int h : hit) sum += h;
            return sum;
        }

        public float[] toVector() {
            float[] vec = new float[HitMapPack1.LENGTH];
            for (int i = 0; i < HitMapPack1.LENGTH; i++) {
                vec[i] = (float) hit[i];
            }
            return vec;
        }

        public String timeString() {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date(time));
        }
    }
}
