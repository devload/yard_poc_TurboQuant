package io.whatap.turboquant.demo.report;

public class DemoReport {
    private long startTime;
    private long endTime;
    private long totalCounterPacks;
    private long totalTagCountPacks;
    private long totalBytes;
    private int agentCount;
    private long errors;

    public void start(int agentCount) {
        this.startTime = System.currentTimeMillis();
        this.agentCount = agentCount;
    }

    public void finish(long counterPacks, long tagCountPacks, long totalBytes, long errors) {
        this.endTime = System.currentTimeMillis();
        this.totalCounterPacks = counterPacks;
        this.totalTagCountPacks = tagCountPacks;
        this.totalBytes = totalBytes;
        this.errors = errors;
    }

    public void print() {
        long durationMs = endTime - startTime;
        double durationSec = durationMs / 1000.0;
        long totalPacks = totalCounterPacks + totalTagCountPacks;

        System.out.println();
        System.out.println("=== YARD Demo Generator Report ===");
        System.out.println(String.format("Duration:         %.1f seconds", durationSec));
        System.out.println(String.format("Agents:           %d", agentCount));
        System.out.println(String.format("CounterPacks:     %d", totalCounterPacks));
        System.out.println(String.format("TagCountPacks:    %d", totalTagCountPacks));
        System.out.println(String.format("Total Packs:      %d", totalPacks));
        System.out.println(String.format("Total Bytes:      %s", formatBytes(totalBytes)));
        System.out.println(String.format("Send Rate:        %.1f packs/sec", totalPacks / durationSec));
        System.out.println(String.format("Throughput:       %s/sec", formatBytes((long) (totalBytes / durationSec))));
        System.out.println(String.format("Errors:           %d", errors));
        System.out.println("==================================");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
