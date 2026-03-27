package io.whatap.turboquant.anomaly.api;

import io.whatap.turboquant.anomaly.alert.EventPackBuilder;
import io.whatap.turboquant.anomaly.baseline.BaselineBuilder;
import io.whatap.turboquant.anomaly.detector.AnomalyDetector;
import io.whatap.turboquant.anomaly.detector.AnomalyResult;
import io.whatap.turboquant.search.vector.ServerStateVector;
import io.whatap.turboquant.search.vector.VectorBuilder;

import java.util.*;

/**
 * End-to-end anomaly detection demo.
 * 1. Generate normal baseline data for N agents
 * 2. Build baselines
 * 3. Inject anomalies into some agents
 * 4. Detect and report
 */
public class AnomalyMain {

    private static final int NUM_AGENTS = 20;
    private static final int TIME_STEPS = 60;
    private static final int BASELINE_WINDOWS = 24; // 24 windows for baseline
    private static final long PCODE = 12345L;

    public static void main(String[] args) {
        System.out.println("=== TurboQuant Anomaly Detection Demo ===");
        System.out.println("Agents: " + NUM_AGENTS + ", Baseline windows: " + BASELINE_WINDOWS);
        System.out.println();

        Random rng = new Random(42);
        VectorBuilder builder = new VectorBuilder(TIME_STEPS);

        // --- Phase 1: Build baselines from normal data ---
        System.out.println("Phase 1: Building baselines...");
        BaselineBuilder baselineBuilder = new BaselineBuilder();

        Map<Integer, double[]> agentProfiles = new LinkedHashMap<Integer, double[]>();
        for (int a = 0; a < NUM_AGENTS; a++) {
            int oid = 1000 + a;
            double cpuBase = 30 + rng.nextDouble() * 30;
            double memBase = 40 + rng.nextDouble() * 20;
            double tpsBase = 80 + rng.nextDouble() * 60;
            agentProfiles.put(oid, new double[]{cpuBase, memBase, tpsBase});

            List<ServerStateVector> history = new ArrayList<ServerStateVector>();
            for (int w = 0; w < BASELINE_WINDOWS; w++) {
                List<Map<String, Double>> snapshots = generateNormalSnapshots(
                        rng, TIME_STEPS, cpuBase, memBase, tpsBase);
                history.add(builder.build(oid, System.currentTimeMillis() - (BASELINE_WINDOWS - w) * 300000, snapshots));
            }
            baselineBuilder.build(oid, history);
        }
        System.out.println("  Baselines built for " + NUM_AGENTS + " agents");

        // --- Phase 2: Generate current state (some normal, some anomalous) ---
        System.out.println();
        System.out.println("Phase 2: Generating current states with injected anomalies...");

        // Anomaly targets: agents 1002 (CPU spike) and 1007 (response time spike)
        Set<Integer> anomalyOids = new HashSet<Integer>(Arrays.asList(1002, 1007, 1015));
        Map<Integer, String> anomalyType = new LinkedHashMap<Integer, String>();
        anomalyType.put(1002, "CPU spike to 95%");
        anomalyType.put(1007, "Response time 10x");
        anomalyType.put(1015, "Memory spike + TPS drop");

        List<ServerStateVector> currentStates = new ArrayList<ServerStateVector>();
        for (int a = 0; a < NUM_AGENTS; a++) {
            int oid = 1000 + a;
            double[] profile = agentProfiles.get(oid);

            List<Map<String, Double>> snapshots;
            if (oid == 1002) {
                // CPU anomaly: spike to 95%
                snapshots = generateAnomalousSnapshots(rng, TIME_STEPS, 95, profile[1], profile[2], 200);
            } else if (oid == 1007) {
                // Response time anomaly: 10x
                snapshots = generateAnomalousSnapshots(rng, TIME_STEPS, profile[0], profile[1], profile[2], 2000);
            } else if (oid == 1015) {
                // Memory + TPS anomaly
                snapshots = generateAnomalousSnapshots(rng, TIME_STEPS, profile[0], 95, 10, 300);
            } else {
                snapshots = generateNormalSnapshots(rng, TIME_STEPS, profile[0], profile[1], profile[2]);
            }
            currentStates.add(builder.build(oid, System.currentTimeMillis(), snapshots));
        }

        // --- Phase 3: Detect anomalies ---
        System.out.println();
        System.out.println("Phase 3: Running anomaly detection...");

        // sigma=5 gives wider tolerance band: threshold = meanSim - 5*stdSim
        // This reduces false positives while still catching true anomalies
        AnomalyDetector detector = new AnomalyDetector(baselineBuilder, 5.0f, 0.40f);
        List<AnomalyResult> results = detector.detectBatch(currentStates);

        // --- Phase 4: Report ---
        System.out.println();
        System.out.println("=== Detection Results ===");
        System.out.println(String.format("%-8s %-12s %-10s %-10s %-8s %-25s",
                "OID", "Similarity", "Threshold", "Status", "Actual", "Detail"));
        System.out.println(new String(new char[85]).replace('\0', '-'));

        int truePositives = 0, falsePositives = 0, trueNegatives = 0, falseNegatives = 0;

        for (AnomalyResult r : results) {
            boolean actualAnomaly = anomalyOids.contains(r.oid);
            String actual = actualAnomaly ? "ANOMALY" : "NORMAL";
            String status = r.isAnomaly ? "ALERT" : "OK";
            String marker = "";
            if (r.isAnomaly && actualAnomaly) { truePositives++; marker = " [TP]"; }
            else if (r.isAnomaly && !actualAnomaly) { falsePositives++; marker = " [FP]"; }
            else if (!r.isAnomaly && actualAnomaly) { falseNegatives++; marker = " [FN]"; }
            else { trueNegatives++; }

            System.out.println(String.format("%-8d %-12.4f %-10.4f %-10s %-8s %s%s",
                    r.oid, r.similarity, r.threshold, status, actual,
                    anomalyType.containsKey(r.oid) ? anomalyType.get(r.oid) : "", marker));
        }

        System.out.println();
        System.out.println("=== Accuracy Report ===");
        System.out.println(String.format("True Positives:  %d", truePositives));
        System.out.println(String.format("False Positives: %d", falsePositives));
        System.out.println(String.format("True Negatives:  %d", trueNegatives));
        System.out.println(String.format("False Negatives: %d", falseNegatives));
        double precision = truePositives + falsePositives > 0 ?
                (double) truePositives / (truePositives + falsePositives) : 0;
        double recallVal = truePositives + falseNegatives > 0 ?
                (double) truePositives / (truePositives + falseNegatives) : 0;
        double f1 = precision + recallVal > 0 ? 2 * precision * recallVal / (precision + recallVal) : 0;
        System.out.println(String.format("Precision:       %.1f%%", precision * 100));
        System.out.println(String.format("Recall:          %.1f%%", recallVal * 100));
        System.out.println(String.format("F1 Score:        %.1f%%", f1 * 100));

        // Show alert messages for detected anomalies
        System.out.println();
        System.out.println("=== Generated Alerts ===");
        EventPackBuilder alertBuilder = new EventPackBuilder(PCODE);
        for (AnomalyResult r : results) {
            if (r.isAnomaly) {
                EventPackBuilder.AlertMessage alert = alertBuilder.build(r);
                System.out.println(alert);
                System.out.println();
            }
        }
    }

    private static List<Map<String, Double>> generateNormalSnapshots(
            Random rng, int timeSteps, double cpuBase, double memBase, double tpsBase) {
        List<Map<String, Double>> snapshots = new ArrayList<Map<String, Double>>();
        for (int t = 0; t < timeSteps; t++) {
            Map<String, Double> snap = new LinkedHashMap<String, Double>();
            snap.put("cpu", cpuBase + rng.nextGaussian() * 3 + 2 * Math.sin(2 * Math.PI * t / 30.0));
            snap.put("mem", memBase + rng.nextGaussian() * 2);
            snap.put("heap_use", 55.0 + rng.nextGaussian() * 5);
            snap.put("tps", tpsBase + rng.nextGaussian() * 8);
            snap.put("resp_time", 200.0 + rng.nextGaussian() * 30);
            snap.put("gc_count", 3.0 + rng.nextGaussian());
            snap.put("service_error", 1.0 + rng.nextDouble() * 2);
            snap.put("act_svc_count", 2.0 + Math.abs(rng.nextGaussian()));
            snapshots.add(snap);
        }
        return snapshots;
    }

    private static List<Map<String, Double>> generateAnomalousSnapshots(
            Random rng, int timeSteps, double cpuBase, double memBase, double tpsBase, double respTime) {
        List<Map<String, Double>> snapshots = new ArrayList<Map<String, Double>>();
        for (int t = 0; t < timeSteps; t++) {
            Map<String, Double> snap = new LinkedHashMap<String, Double>();
            snap.put("cpu", cpuBase + rng.nextGaussian() * 2);
            snap.put("mem", memBase + rng.nextGaussian() * 2);
            snap.put("heap_use", 80.0 + rng.nextGaussian() * 3);
            snap.put("tps", tpsBase + rng.nextGaussian() * 5);
            snap.put("resp_time", respTime + rng.nextGaussian() * 50);
            snap.put("gc_count", 10.0 + Math.abs(rng.nextGaussian()) * 5);
            snap.put("service_error", 10.0 + rng.nextDouble() * 10);
            snap.put("act_svc_count", 8.0 + Math.abs(rng.nextGaussian()) * 3);
            snapshots.add(snap);
        }
        return snapshots;
    }
}
