package com.subhanmalik.countmin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CountMinSketchTest {

    private static final long SEED = 0xC0FFEE1234L;

    @Test
    void emptySketchEstimatesZero() {
        CountMinSketch<String> sketch = CountMinSketch.seeded(100, 5, false, SEED);
        assertEquals(0, sketch.estimate("never-seen"));
        assertEquals(0, sketch.totalCount());
    }

    @Test
    void estimateNeverUndercounts() {
        CountMinSketch<String> sketch = CountMinSketch.seeded(200, 5, false, SEED);
        Map<String, Long> truth = new HashMap<>();
        Random random = new Random(42);
        for (int i = 0; i < 20_000; i++) {
            String item = "item-" + random.nextInt(500);
            sketch.add(item);
            truth.merge(item, 1L, Long::sum);
        }
        for (Map.Entry<String, Long> entry : truth.entrySet()) {
            assertTrue(sketch.estimate(entry.getKey()) >= entry.getValue(),
                    "undercount for " + entry.getKey());
        }
    }

    @Test
    void overestimateStaysWithinBound() {
        // epsilon = 0.01, so error should stay well under 1% of N for the
        // hottest items on a seeded sketch; a loose 2x margin keeps the test
        // deterministic without weakening the point.
        CountMinSketch<String> sketch = CountMinSketch.seeded(272, 5, false, SEED);
        Map<String, Long> truth = new HashMap<>();
        Random random = new Random(7);
        for (int i = 0; i < 50_000; i++) {
            String item = "key-" + random.nextInt(2000);
            sketch.add(item);
            truth.merge(item, 1L, Long::sum);
        }
        long allowedError = (long) (0.02 * sketch.totalCount());
        for (Map.Entry<String, Long> entry : truth.entrySet()) {
            long error = sketch.estimate(entry.getKey()) - entry.getValue();
            assertTrue(error <= allowedError,
                    "error " + error + " exceeds bound for " + entry.getKey());
        }
    }

    @Test
    void conservativeUpdateNeverOverestimatesMoreThanStandard() {
        CountMinSketch<String> standard = CountMinSketch.seeded(50, 4, false, SEED);
        CountMinSketch<String> conservative = CountMinSketch.seeded(50, 4, true, SEED);
        Random random = new Random(99);
        for (int i = 0; i < 5_000; i++) {
            String item = "word-" + random.nextInt(100);
            standard.add(item);
            conservative.add(item);
        }
        // A rare item collides with hot ones in the narrow sketch; the
        // conservative variant must not report a larger overcount for it.
        for (int i = 0; i < 100; i++) {
            String item = "word-" + i;
            assertTrue(conservative.estimate(item) <= standard.estimate(item),
                    "conservative estimate larger for " + item);
        }
    }

    @Test
    void addWithCountAndTotalTracking() {
        CountMinSketch<String> sketch = CountMinSketch.seeded(100, 5, false, SEED);
        sketch.add("a", 7);
        sketch.add("b", 3);
        sketch.add("a");
        assertEquals(11, sketch.totalCount());
        assertEquals(8, sketch.estimate("a"));
        assertEquals(3, sketch.estimate("b"));
    }

    @Test
    void mergeSumsCountsFromBothSketches() {
        CountMinSketch<String> left = CountMinSketch.seeded(200, 5, false, SEED);
        CountMinSketch<String> right = CountMinSketch.seeded(200, 5, false, SEED);
        left.add("x", 10);
        left.add("y", 2);
        right.add("x", 5);
        right.add("z", 4);
        left.merge(right);
        assertEquals(21, left.totalCount());
        assertEquals(15, left.estimate("x"));
        assertEquals(2, left.estimate("y"));
        assertEquals(4, left.estimate("z"));
    }

    @Test
    void mergeRejectsMismatchedDimensionsAndSeeds() {
        CountMinSketch<String> sketch = CountMinSketch.seeded(200, 5, false, SEED);
        assertThrows(IllegalArgumentException.class,
                () -> sketch.merge(CountMinSketch.seeded(100, 5, false, SEED)));
        assertThrows(IllegalArgumentException.class,
                () -> sketch.merge(CountMinSketch.seeded(200, 5, false, SEED + 1)));
    }

    @Test
    void serializationRoundTripPreservesEstimates() throws Exception {
        CountMinSketch<String> sketch = CountMinSketch.seeded(200, 5, true, SEED);
        sketch.add("alpha", 12);
        sketch.add("beta", 3);
        CountMinSketch<String> restored = CountMinSketch.fromByteArray(sketch.toByteArray());
        assertEquals(15, restored.totalCount());
        assertEquals(sketch.estimate("alpha"), restored.estimate("alpha"));
        assertEquals(sketch.estimate("beta"), restored.estimate("beta"));
        assertEquals(sketch.estimate("gamma"), restored.estimate("gamma"));
    }

    @Test
    void serializationRejectsGarbage() {
        assertThrows(Exception.class,
                () -> CountMinSketch.fromByteArray(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void invalidArgumentsThrow() {
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch<>(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch<>(100, 0));
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch<>(0.0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch<>(0.01, 1.0));
        CountMinSketch<String> sketch = CountMinSketch.seeded(100, 5, false, SEED);
        assertThrows(IllegalArgumentException.class, () -> sketch.add("a", -1));
        assertThrows(NullPointerException.class, () -> sketch.add(null));
        assertThrows(NullPointerException.class, () -> sketch.estimate(null));
    }

    @Test
    void epsilonDeltaConstructorSizesSketchCorrectly() {
        // w = ceil(e / 0.01) = 272, d = ceil(ln(1 / 0.01)) = 5
        CountMinSketch<String> sketch = new CountMinSketch<>(0.01, 0.01);
        assertEquals(272, sketch.width());
        assertEquals(5, sketch.depth());
    }
}
