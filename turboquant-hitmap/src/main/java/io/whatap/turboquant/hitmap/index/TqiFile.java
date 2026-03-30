package io.whatap.turboquant.hitmap.index;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * .tqi (TurboQuant Index) 파일 포맷.
 *
 * Append-only, 고정 크기 엔트리로 구성.
 * YARD 재시작 시 파일에서 인덱스를 복원할 수 있다.
 *
 * 파일 구조:
 * ┌──────────── 헤더 (20바이트) ────────────┐
 * │ magic: "TQI1" (4B)                      │
 * │ version: 1 (1B)                         │
 * │ dim: 120 (2B)                           │
 * │ bits: 4 (1B)                            │
 * │ compressedSize: 64 (2B)                 │
 * │ rotationSeed: 42 (8B)                   │
 * │ reserved: (2B)                          │
 * ├──────────── 엔트리 반복 ────────────────┤
 * │ time: (8B)                              │
 * │ pcode: (8B)                             │
 * │ oid: (4B)                               │
 * │ totalHits: (4B)                         │
 * │ compressed: (compressedSize B)          │
 * │ ─── 엔트리당 24 + compressedSize 바이트 │
 * └─────────────────────────────────────────┘
 */
public class TqiFile implements Closeable {

    static final byte[] MAGIC = {'T', 'Q', 'I', '1'};
    static final int HEADER_SIZE = 20;
    static final byte VERSION = 1;

    private final File file;
    private final int dim;
    private final int bits;
    private final int compressedSize;
    private final long rotationSeed;
    private final int entrySize;

    private RandomAccessFile raf;
    private int count;

    private TqiFile(File file, int dim, int bits, int compressedSize, long rotationSeed) {
        this.file = file;
        this.dim = dim;
        this.bits = bits;
        this.compressedSize = compressedSize;
        this.rotationSeed = rotationSeed;
        this.entrySize = 8 + 8 + 4 + 4 + compressedSize; // time + pcode + oid + totalHits + compressed
    }

    /**
     * 새 인덱스 파일 생성.
     */
    public static TqiFile create(File file, int dim, int bits, int compressedSize, long rotationSeed) throws IOException {
        TqiFile tqi = new TqiFile(file, dim, bits, compressedSize, rotationSeed);
        tqi.raf = new RandomAccessFile(file, "rw");
        tqi.writeHeader();
        tqi.count = 0;
        return tqi;
    }

    /**
     * 기존 인덱스 파일 열기. 헤더 검증 후 엔트리 수 계산.
     */
    public static TqiFile open(File file) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");

        // 헤더 읽기
        byte[] header = new byte[HEADER_SIZE];
        raf.readFully(header);
        ByteBuffer buf = ByteBuffer.wrap(header);

        // magic 확인
        byte[] magic = new byte[4];
        buf.get(magic);
        if (magic[0] != 'T' || magic[1] != 'Q' || magic[2] != 'I' || magic[3] != '1') {
            raf.close();
            throw new IOException("Invalid TQI file: " + file);
        }

        byte version = buf.get();
        int dim = buf.getShort() & 0xFFFF;
        int bits = buf.get() & 0xFF;
        int compressedSize = buf.getShort() & 0xFFFF;
        long rotationSeed = buf.getLong();

        TqiFile tqi = new TqiFile(file, dim, bits, compressedSize, rotationSeed);
        tqi.raf = raf;

        // 엔트리 수 계산
        long dataSize = file.length() - HEADER_SIZE;
        tqi.count = (int) (dataSize / tqi.entrySize);

        return tqi;
    }

    /**
     * 엔트리 추가 (append-only).
     */
    public synchronized void append(long time, long pcode, int oid, int totalHits, byte[] compressed) throws IOException {
        if (compressed.length != compressedSize) {
            throw new IllegalArgumentException("Expected " + compressedSize + " bytes, got " + compressed.length);
        }

        raf.seek(HEADER_SIZE + (long) count * entrySize);

        ByteBuffer buf = ByteBuffer.allocate(entrySize);
        buf.putLong(time);
        buf.putLong(pcode);
        buf.putInt(oid);
        buf.putInt(totalHits);
        buf.put(compressed);
        buf.flip();

        raf.write(buf.array());
        count++;
    }

    /**
     * 특정 위치의 엔트리 읽기 (랜덤 액세스).
     */
    public synchronized TqiEntry read(int index) throws IOException {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("index=" + index + ", count=" + count);
        }

        raf.seek(HEADER_SIZE + (long) index * entrySize);
        byte[] data = new byte[entrySize];
        raf.readFully(data);

        ByteBuffer buf = ByteBuffer.wrap(data);
        long time = buf.getLong();
        long pcode = buf.getLong();
        int oid = buf.getInt();
        int totalHits = buf.getInt();
        byte[] compressed = new byte[compressedSize];
        buf.get(compressed);

        return new TqiEntry(time, pcode, oid, totalHits, compressed);
    }

    /**
     * 전체 엔트리를 한 번에 읽기.
     */
    public List<TqiEntry> readAll() throws IOException {
        List<TqiEntry> entries = new ArrayList<TqiEntry>();
        for (int i = 0; i < count; i++) {
            entries.add(read(i));
        }
        return entries;
    }

    /**
     * 시간 범위로 엔트리 검색 (순차 스캔).
     */
    public List<TqiEntry> readRange(long startTime, long endTime) throws IOException {
        List<TqiEntry> results = new ArrayList<TqiEntry>();
        for (int i = 0; i < count; i++) {
            TqiEntry e = read(i);
            if (e.time >= startTime && e.time <= endTime) {
                results.add(e);
            }
        }
        return results;
    }

    public int getCount() { return count; }
    public int getDim() { return dim; }
    public int getBits() { return bits; }
    public int getCompressedSize() { return compressedSize; }
    public long getRotationSeed() { return rotationSeed; }
    public int getEntrySize() { return entrySize; }
    public File getFile() { return file; }

    public long fileSizeBytes() { return HEADER_SIZE + (long) count * entrySize; }

    private void writeHeader() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.put(MAGIC);
        buf.put(VERSION);
        buf.putShort((short) dim);
        buf.put((byte) bits);
        buf.putShort((short) compressedSize);
        buf.putLong(rotationSeed);
        buf.putShort((short) 0); // reserved
        buf.flip();

        raf.seek(0);
        raf.write(buf.array());
    }

    public void flush() throws IOException {
        raf.getFD().sync();
    }

    public void close() throws IOException {
        if (raf != null) {
            raf.close();
        }
    }

    /**
     * 인덱스 엔트리.
     */
    public static class TqiEntry {
        public final long time;
        public final long pcode;
        public final int oid;
        public final int totalHits;
        public final byte[] compressed;

        public TqiEntry(long time, long pcode, int oid, int totalHits, byte[] compressed) {
            this.time = time;
            this.pcode = pcode;
            this.oid = oid;
            this.totalHits = totalHits;
            this.compressed = compressed;
        }

        public String timeString() {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date(time));
        }
    }
}
