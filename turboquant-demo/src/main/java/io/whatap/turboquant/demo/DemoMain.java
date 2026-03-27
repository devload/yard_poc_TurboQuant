package io.whatap.turboquant.demo;

import io.whatap.lang.pack.CounterPack;
import io.whatap.lang.pack.TagCountPack;
import io.whatap.turboquant.demo.agent.VirtualAgent;
import io.whatap.turboquant.demo.config.DemoConfig;
import io.whatap.turboquant.demo.report.DemoReport;
import io.whatap.turboquant.demo.sender.PackBuilder;
import io.whatap.turboquant.demo.sender.YardTcpSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DemoMain {

    public static void main(String[] args) {
        DemoConfig config = DemoConfig.fromArgs(args);
        System.out.println("[DemoMain] Starting with config: " + config);

        long startMs = System.currentTimeMillis();
        long endMs = startMs + config.getDurationSec() * 1000;

        // Create virtual agents
        List<VirtualAgent> agents = new ArrayList<VirtualAgent>();
        for (int i = 0; i < config.getAgentCount(); i++) {
            int oid = 1000 + i;
            agents.add(new VirtualAgent(config.getPcode(), oid, startMs));
        }

        DemoReport report = new DemoReport();
        report.start(config.getAgentCount());

        long counterPacks = 0;
        long tagCountPacks = 0;
        long errors = 0;

        YardTcpSender sender = new YardTcpSender(config.getHost(), config.getPort());
        try {
            sender.connect();

            long tick = 0;
            while (System.currentTimeMillis() < endMs) {
                long now = System.currentTimeMillis();

                for (VirtualAgent agent : agents) {
                    try {
                        Map<String, Double> snapshot = agent.tick(now);

                        // Send CounterPack
                        CounterPack cp = PackBuilder.buildCounterPack(
                                agent.getPcode(), agent.getOid(), now, snapshot);
                        sender.send(agent.getPcode(), agent.getOid(), cp);
                        counterPacks++;

                        // Send TagCountPack
                        TagCountPack tp = PackBuilder.buildTagCountPack(
                                agent.getPcode(), agent.getOid(), agent.getOname(), now, snapshot);
                        sender.send(agent.getPcode(), agent.getOid(), tp);
                        tagCountPacks++;
                    } catch (Exception e) {
                        errors++;
                        if (errors <= 5) {
                            System.err.println("[DemoMain] Send error for agent " + agent.getOid() + ": " + e.getMessage());
                        }
                        if (!sender.isConnected()) {
                            System.err.println("[DemoMain] Connection lost. Reconnecting...");
                            try {
                                sender.close();
                                sender = new YardTcpSender(config.getHost(), config.getPort());
                                sender.connect();
                            } catch (Exception reconnectErr) {
                                System.err.println("[DemoMain] Reconnect failed: " + reconnectErr.getMessage());
                            }
                        }
                    }
                }

                tick++;
                if (tick % 10 == 0) {
                    System.out.println(String.format("[DemoMain] Tick %d: sent %d counter + %d tagcount packs, %s bytes",
                            tick, counterPacks, tagCountPacks, sender.getTotalBytes()));
                }

                // Sleep until next interval
                long elapsed = System.currentTimeMillis() - now;
                long sleepMs = config.getSendIntervalMs() - elapsed;
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
            }
        } catch (InterruptedException e) {
            System.out.println("[DemoMain] Interrupted");
        } catch (Exception e) {
            System.err.println("[DemoMain] Fatal error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try { sender.close(); } catch (Exception e) { /* ignore */ }
        }

        report.finish(counterPacks, tagCountPacks, sender.getTotalBytes(), errors);
        report.print();
    }
}
