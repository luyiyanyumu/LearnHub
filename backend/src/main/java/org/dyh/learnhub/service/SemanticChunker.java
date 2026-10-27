package org.dyh.learnhub.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Groups structural prose units at measured topic changes. No model or IO on the query path. */
public final class SemanticChunker {
    private SemanticChunker() { }

    /** Vectors correspond only to the non-protected units, in their original order. */
    public static List<TextChunker.Chunk> split(List<TextChunker.Unit> units, TextChunker.Options options,
                                               List<float[]> proseVectors, int breakpointPercentile) {
        if (breakpointPercentile < 50 || breakpointPercentile > 99)
            throw new IllegalArgumentException("语义切点百分位须在50至99之间");
        if (units == null || options == null || proseVectors == null)
            throw new IllegalArgumentException("语义切块参数不能为空");
        long proseCount = units.stream().filter(unit -> !unit.protectedBlock()).count();
        if (proseCount != proseVectors.size()) throw new IllegalStateException("语义边界向量数量不匹配");
        int dimension = 0;
        for (float[] vector : proseVectors) {
            if (vector == null || vector.length == 0) throw new IllegalStateException("语义边界向量为空");
            if (dimension != 0 && dimension != vector.length) throw new IllegalStateException("语义边界向量维度不一致");
            dimension = vector.length;
            double norm = 0;
            for (float value : vector) {
                if (!Float.isFinite(value)) throw new IllegalStateException("语义边界向量包含无效值");
                norm += (double) value * value;
            }
            if (norm == 0) throw new IllegalStateException("语义边界向量为零");
        }
        double[] gaps = new double[units.size()];
        Arrays.fill(gaps, -1);
        List<Double> distances = new ArrayList<>();
        int vectorIndex = 0;
        float[] previous = null;
        TextChunker.Unit priorUnit = null;
        for (int i = 0; i < units.size(); i++) {
            TextChunker.Unit unit = units.get(i);
            if (unit.protectedBlock()) {
                previous = null;
            } else {
                float[] vector = proseVectors.get(vectorIndex++);
                if (previous != null && priorUnit != null && Objects.equals(priorUnit.heading(), unit.heading())) {
                    double gap = 1 - cosine(previous, vector);
                    gaps[i] = gap;
                    distances.add(gap);
                }
                previous = vector;
            }
            priorUnit = unit;
        }
        double threshold = Double.POSITIVE_INFINITY;
        if (!distances.isEmpty()) {
            distances.sort(Double::compareTo);
            threshold = Math.max(0.02, distances.get(Math.max(0,
                    (int) Math.ceil(distances.size() * breakpointPercentile / 100.0) - 1)));
        }
        List<TextChunker.Chunk> result = new ArrayList<>();
        List<TextChunker.Unit> current = new ArrayList<>();
        String heading = "";
        for (int i = 0; i < units.size(); i++) {
            TextChunker.Unit unit = units.get(i);
            if (unit.text().isBlank()) continue;
            if (unit.protectedBlock()) {
                flush(result, heading, current);
                current.clear();
                // Protected syntax is atomic, including blocks longer than the prose maximum.
                result.add(new TextChunker.Chunk(unit.heading(), unit.text()));
                heading = unit.heading();
                continue;
            }
            boolean newHeading = !Objects.equals(heading, unit.heading());
            boolean topicChange = gaps[i] >= threshold && length(current) >= Math.max(1, options.target() / 2);
            boolean full = !current.isEmpty() && length(current) + 2 + unit.text().length() > options.target();
            if (!current.isEmpty() && (newHeading || topicChange || full)) {
                flush(result, heading, current);
                List<TextChunker.Unit> overlap = new ArrayList<>();
                // Never carry old-topic text across a detected boundary or heading change.
                if (!newHeading && !topicChange) {
                    int chars = 0;
                    for (int j = current.size() - 1; j >= 0; j--) {
                        TextChunker.Unit tail = current.get(j);
                        int needed = tail.text().length() + (overlap.isEmpty() ? 0 : 2);
                        if (chars + needed > options.overlap()
                                || chars + needed + 2 + unit.text().length() > options.max()) break;
                        overlap.add(0, tail);
                        chars += needed;
                    }
                }
                current.clear();
                current.addAll(overlap);
            }
            heading = unit.heading();
            current.add(unit);
        }
        flush(result, heading, current);
        return List.copyOf(result);
    }

    private static int length(List<TextChunker.Unit> units) {
        return units.stream().mapToInt(unit -> unit.text().length()).sum() + Math.max(0, units.size() - 1) * 2;
    }

    private static void flush(List<TextChunker.Chunk> result, String heading, List<TextChunker.Unit> units) {
        if (!units.isEmpty()) result.add(new TextChunker.Chunk(heading,
                String.join("\n\n", units.stream().map(TextChunker.Unit::text).toList())));
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        return Math.max(-1, Math.min(1, dot / Math.sqrt(leftNorm * rightNorm)));
    }
}
