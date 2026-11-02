package com.subhanmalik.countmin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * A Count-Min Sketch: a probabilistic data structure that estimates how many
 * times each item has been seen, using sublinear memory.
 *
 * <p>It never undercounts: {@link #estimate(Object)} is always &gt;= the true
 * count. With width {@code w = ceil(e / epsilon)} and depth
 * {@code d = ceil(ln(1 / delta))}, the overestimate is at most
 * {@code epsilon * totalCount} with probability at least {@code 1 - delta}.
 *
 * <p>Two options worth knowing about:
 * <ul>
 *   <li><b>Conservative update</b> — on {@code add}, only the counters that
 *   currently equal the item's minimum estimate are incremented. This can only
 *   reduce overestimation and is the right default when accuracy matters more
 *   than raw update speed.</li>
 *   <li><b>Merging</b> — sketches with identical dimensions can be added
 *   together ({@link #merge(CountMinSketch)}), so counts collected on separate
 *   machines can be combined without shipping the raw events.</li>
 * </ul>
 *
 * <p>Instances are thread-safe: updates and estimates are synchronized. The
 * table itself is small (width &times; depth longs), so contention is rarely
 * the bottleneck; for extreme write throughput, shard by item instead.
 *
 * @param <T> the item type; items are hashed via {@link Object#hashCode()}
 *            mixed through SplitMix64, so a decent hashCode matters
 */
public final class CountMinSketch<T> {

    private static final int MAGIC = 0x434D5301; // "CMS\x01"
    private static final int FORMAT_VERSION = 1;

    private final int width;
    private final int depth;
    private final boolean conservativeUpdate;
    private final long[] rowSeeds;
    private final long[][] table;
    private long totalCount;

    /** Builds a sketch sized from the requested error bounds. */
    public CountMinSketch(double epsilon, double delta) {
        this(epsilon, delta, false, 0x9E3779B97F4A7C15L);
    }

    /** Builds a sketch of explicit dimensions. */
    public CountMinSketch(int width, int depth) {
        this(width, depth, false, 0x9E3779B97F4A7C15L);
    }

    /** Builds a sketch with conservative updates enabled. */
    public static <T> CountMinSketch<T> conservative(int width, int depth) {
        return new CountMinSketch<>(width, depth, true, 0x9E3779B97F4A7C15L);
    }

    /** Builds a sketch with a fixed seed, for reproducible tests and demos. */
    public static <T> CountMinSketch<T> seeded(int width, int depth, boolean conservativeUpdate, long seed) {
        return new CountMinSketch<>(width, depth, conservativeUpdate, seed);
    }

    private CountMinSketch(double epsilon, double delta, boolean conservativeUpdate, long seed) {
        this(widthFor(epsilon), depthFor(delta), conservativeUpdate, seed);
    }

    private static int widthFor(double epsilon) {
        if (!(epsilon > 0 && epsilon < 1)) {
            throw new IllegalArgumentException("epsilon must be in (0, 1), got " + epsilon);
        }
        return (int) Math.ceil(Math.E / epsilon);
    }

    private static int depthFor(double delta) {
        if (!(delta > 0 && delta < 1)) {
            throw new IllegalArgumentException("delta must be in (0, 1), got " + delta);
        }
        return (int) Math.ceil(Math.log(1.0 / delta));
    }

    private CountMinSketch(int width, int depth, boolean conservativeUpdate, long seed) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive, got " + width);
        }
        if (depth <= 0) {
            throw new IllegalArgumentException("depth must be positive, got " + depth);
        }
        this.width = width;
        this.depth = depth;
        this.conservativeUpdate = conservativeUpdate;
        this.rowSeeds = new long[depth];
        this.table = new long[depth][width];
        long s = seed;
        for (int i = 0; i < depth; i++) {
            s = splitMix64(s);
            rowSeeds[i] = s;
        }
    }

    /** Records one occurrence of {@code item}. */
    public synchronized void add(T item) {
        add(item, 1);
    }

    /** Records {@code count} occurrences of {@code item}. */
    public synchronized void add(T item, long count) {
        Objects.requireNonNull(item, "item");
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative, got " + count);
        }
        if (count == 0) {
            return;
        }
        int[] columns = columnsFor(item);
        if (conservativeUpdate) {
            long estimate = Long.MAX_VALUE;
            for (int row = 0; row < depth; row++) {
                estimate = Math.min(estimate, table[row][columns[row]]);
            }
            for (int row = 0; row < depth; row++) {
                if (table[row][columns[row]] == estimate) {
                    table[row][columns[row]] += count;
                }
            }
        } else {
            for (int row = 0; row < depth; row++) {
                table[row][columns[row]] += count;
            }
        }
        totalCount += count;
    }

    /**
     * Estimates how many times {@code item} was added. Never below the true
     * count; above it by at most {@code epsilon * totalCount} with probability
     * {@code 1 - delta} (for sketches sized from error bounds).
     */
    public synchronized long estimate(T item) {
        Objects.requireNonNull(item, "item");
        int[] columns = columnsFor(item);
        long estimate = Long.MAX_VALUE;
        for (int row = 0; row < depth; row++) {
            estimate = Math.min(estimate, table[row][columns[row]]);
        }
        return estimate;
    }

    /**
     * Adds every counter of {@code other} into this sketch. Both sketches must
     * share width, depth, and row seeds — in practice, build them with the same
     * constructor arguments or the same seed.
     */
    public synchronized void merge(CountMinSketch<T> other) {
        Objects.requireNonNull(other, "other");
        if (other.width != width || other.depth != depth) {
            throw new IllegalArgumentException(
                    "cannot merge sketches of different dimensions: this=" + width + "x" + depth
                            + ", other=" + other.width + "x" + other.depth);
        }
        for (int row = 0; row < depth; row++) {
            if (other.rowSeeds[row] != rowSeeds[row]) {
                throw new IllegalArgumentException("cannot merge sketches built with different seeds");
            }
            for (int col = 0; col < width; col++) {
                table[row][col] += other.table[row][col];
            }
        }
        totalCount += other.totalCount;
    }

    /** Total number of items added (the {@code N} in the error bound). */
    public synchronized long totalCount() {
        return totalCount;
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    public boolean isConservativeUpdate() {
        return conservativeUpdate;
    }

    /** Serializes the sketch (dimensions, seeds, counters) to bytes. */
    public synchronized byte[] toByteArray() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(FORMAT_VERSION);
        out.writeInt(width);
        out.writeInt(depth);
        out.writeBoolean(conservativeUpdate);
        out.writeLong(totalCount);
        for (long seed : rowSeeds) {
            out.writeLong(seed);
        }
        for (int row = 0; row < depth; row++) {
            for (int col = 0; col < width; col++) {
                out.writeLong(table[row][col]);
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    /** Restores a sketch previously written by {@link #toByteArray()}. */
    public static <T> CountMinSketch<T> fromByteArray(byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readInt() != MAGIC) {
            throw new IOException("not a CountMinSketch byte stream");
        }
        if (in.readInt() != FORMAT_VERSION) {
            throw new IOException("unsupported CountMinSketch format version");
        }
        int width = in.readInt();
        int depth = in.readInt();
        boolean conservativeUpdate = in.readBoolean();
        long totalCount = in.readLong();
        CountMinSketch<T> sketch = new CountMinSketch<>(width, depth, conservativeUpdate, 0L);
        for (int row = 0; row < depth; row++) {
            sketch.rowSeeds[row] = in.readLong();
        }
        for (int row = 0; row < depth; row++) {
            for (int col = 0; col < width; col++) {
                sketch.table[row][col] = in.readLong();
            }
        }
        sketch.totalCount = totalCount;
        return sketch;
    }

    private int[] columnsFor(T item) {
        long mixed = splitMix64((long) item.hashCode() + 0x9E3779B97F4A7C15L);
        int[] columns = new int[depth];
        for (int row = 0; row < depth; row++) {
            long hash = splitMix64(mixed ^ rowSeeds[row]);
            columns[row] = (int) Long.remainderUnsigned(hash, width);
        }
        return columns;
    }

    /** SplitMix64 finalizer: turns a mediocre hash into a well-spread one. */
    static long splitMix64(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }
}
