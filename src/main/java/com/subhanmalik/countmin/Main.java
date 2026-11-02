package com.subhanmalik.countmin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo CLI: feeds whitespace-separated tokens into a Count-Min Sketch and
 * prints each distinct token with its true count and its sketch estimate, so
 * you can see the "never undercounts, slightly overcounts" guarantee live.
 *
 * <pre>
 *   echo "apple banana apple cherry banana apple" | java -jar count-min-sketch.jar
 *   java -jar count-min-sketch.jar --conservative --top 5 access.log
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        double epsilon = 0.001;
        double delta = 0.01;
        boolean conservative = false;
        int top = Integer.MAX_VALUE;
        Path input = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--epsilon" -> epsilon = Double.parseDouble(args[++i]);
                case "--delta" -> delta = Double.parseDouble(args[++i]);
                case "--conservative" -> conservative = true;
                case "--top" -> top = Integer.parseInt(args[++i]);
                default -> {
                    if (args[i].startsWith("--")) {
                        throw new IllegalArgumentException("unknown option: " + args[i]);
                    }
                    if (input != null) {
                        throw new IllegalArgumentException("only one input file is supported");
                    }
                    input = Path.of(args[i]);
                }
            }
        }

        CountMinSketch<String> sketch = conservative
                ? conservativeSketch(epsilon, delta)
                : new CountMinSketch<>(epsilon, delta);
        Map<String, Long> truth = new HashMap<>();

        try (BufferedReader reader = input == null
                ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                : Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (String token : line.split("\\s+")) {
                    if (token.isEmpty()) {
                        continue;
                    }
                    sketch.add(token);
                    truth.merge(token, 1L, Long::sum);
                }
            }
        }

        List<Map.Entry<String, Long>> rows = new ArrayList<>(truth.entrySet());
        rows.sort(Comparator.comparingLong((Map.Entry<String, Long> e) -> sketch.estimate(e.getKey()))
                .reversed()
                .thenComparing(Map.Entry::getKey));

        System.out.printf("sketch: %dx%d (epsilon=%.4f, delta=%.4f, conservative=%s), %d tokens, %d distinct%n",
                sketch.width(), sketch.depth(), epsilon, delta, conservative,
                sketch.totalCount(), truth.size());
        System.out.printf("%-30s %12s %12s %12s%n", "item", "true", "estimate", "overcount");
        for (Map.Entry<String, Long> row : rows.subList(0, Math.min(top, rows.size()))) {
            long estimate = sketch.estimate(row.getKey());
            System.out.printf("%-30s %12d %12d %12d%n",
                    row.getKey(), row.getValue(), estimate, estimate - row.getValue());
        }
    }

    private static CountMinSketch<String> conservativeSketch(double epsilon, double delta) {
        int width = (int) Math.ceil(Math.E / epsilon);
        int depth = (int) Math.ceil(Math.log(1.0 / delta));
        return CountMinSketch.conservative(width, depth);
    }
}
