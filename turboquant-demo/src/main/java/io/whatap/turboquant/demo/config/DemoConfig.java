package io.whatap.turboquant.demo.config;

public class DemoConfig {
    private String host = "127.0.0.1";
    private int port = 6610;
    private long pcode = 12345L;
    private int agentCount = 10;
    private long sendIntervalMs = 5000L;
    private long durationSec = 300L;

    public static DemoConfig fromArgs(String[] args) {
        DemoConfig config = new DemoConfig();
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host": config.host = args[++i]; break;
                case "--port": config.port = Integer.parseInt(args[++i]); break;
                case "--pcode": config.pcode = Long.parseLong(args[++i]); break;
                case "--agents": config.agentCount = Integer.parseInt(args[++i]); break;
                case "--interval": config.sendIntervalMs = Long.parseLong(args[++i]); break;
                case "--duration": config.durationSec = Long.parseLong(args[++i]); break;
            }
        }
        return config;
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public long getPcode() { return pcode; }
    public int getAgentCount() { return agentCount; }
    public long getSendIntervalMs() { return sendIntervalMs; }
    public long getDurationSec() { return durationSec; }

    public String toString() {
        return String.format("DemoConfig{host=%s, port=%d, pcode=%d, agents=%d, interval=%dms, duration=%ds}",
                host, port, pcode, agentCount, sendIntervalMs, durationSec);
    }
}
