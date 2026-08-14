package com.tj.crypto.backtest.robustness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic Combinatorially Symmetric Cross-Validation (CSCV) implementation.
 *
 * <p>Rows of {@code chronologicalReturns} are equally spaced, chronologically ordered return
 * observations; columns correspond exactly to {@code trialIds}. Rows are split into equal,
 * contiguous partitions. For each half-partition combination, the highest in-sample mean-return
 * trial is selected and its complementary out-of-sample rank logit is measured. A non-positive
 * logit counts as overfit. In-sample ties select the lowest registered column deterministically.</p>
 */
public final class CscvPboAnalyzer {
    private static final int MAX_COMBINATIONS = 100_000;

    public CscvPboResult analyze(
            List<String> trialIds,
            double[][] chronologicalReturns,
            int partitionCount) {
        validate(trialIds, chronologicalReturns, partitionCount);
        List<String> immutableTrialIds = trialIds.stream().map(String::trim).toList();
        int observationCount = chronologicalReturns.length;
        int trialCount = immutableTrialIds.size();
        int half = partitionCount / 2;
        int segmentLength = observationCount / partitionCount;
        int combinationCount = Math.toIntExact(combinationCount(partitionCount, half));

        double[][] partitionSums = new double[partitionCount][trialCount];
        double[] totalSums = new double[trialCount];
        for (int row = 0; row < observationCount; row++) {
            int partition = row / segmentLength;
            for (int trial = 0; trial < trialCount; trial++) {
                double value = chronologicalReturns[row][trial];
                partitionSums[partition][trial] += value;
                totalSums[trial] += value;
            }
        }

        List<CscvSplitResult> results = new ArrayList<>(combinationCount);
        int overfitCount = 0;
        int inSampleTieCount = 0;
        int[] combination = initialCombination(half);
        int splitIndex = 0;
        do {
            double[] inSampleScores = new double[trialCount];
            double[] outOfSampleScores = new double[trialCount];
            for (int trial = 0; trial < trialCount; trial++) {
                double inSampleSum = 0;
                for (int partition : combination) {
                    inSampleSum += partitionSums[partition][trial];
                }
                inSampleScores[trial] = inSampleSum / (segmentLength * half);
                outOfSampleScores[trial] = (totalSums[trial] - inSampleSum) / (segmentLength * half);
            }

            int selected = indexOfMaximum(inSampleScores);
            if (countEqualTo(inSampleScores, inSampleScores[selected]) > 1) inSampleTieCount++;
            double rankFromWorst = averageRankFromWorst(outOfSampleScores, outOfSampleScores[selected]);
            double relativeRank = rankFromWorst / (trialCount + 1D);
            double rankLogit = Math.log(relativeRank / (1 - relativeRank));
            boolean overfit = rankLogit <= 0;
            if (overfit) overfitCount++;

            results.add(new CscvSplitResult(
                    splitIndex++,
                    Arrays.stream(combination).boxed().toList(),
                    immutableTrialIds.get(selected),
                    RobustnessStatistics.decimal(inSampleScores[selected]),
                    RobustnessStatistics.decimal(outOfSampleScores[selected]),
                    RobustnessStatistics.decimal(rankFromWorst),
                    RobustnessStatistics.decimal(relativeRank),
                    RobustnessStatistics.decimal(rankLogit),
                    overfit));
        } while (advanceCombination(combination, partitionCount));

        if (results.size() != combinationCount) {
            throw new IllegalStateException("generated CSCV combination count does not match expected count");
        }
        double pbo = (double) overfitCount / combinationCount;
        double medianLogit = median(results.stream()
                .mapToDouble(result -> result.rankLogit().doubleValue())
                .toArray());
        List<String> limitations = new ArrayList<>();
        limitations.add("PBO is valid only when every tried strategy/parameter variant is included as a column");
        limitations.add("Rows must be chronological, equally spaced and free of look-ahead leakage before CSCV");
        limitations.add("Mean return is the fixed selection metric; costs must already be included in input returns");
        limitations.add(RobustnessStatistics.FORWARD_VALIDATION_LIMITATION);
        if (inSampleTieCount > 0) {
            limitations.add("In-sample ties occurred in " + inSampleTieCount
                    + " splits and were resolved by registered column order");
        }

        return new CscvPboResult(
                observationCount,
                trialCount,
                partitionCount,
                combinationCount,
                RobustnessStatistics.decimal(pbo),
                RobustnessStatistics.decimal(medianLogit),
                results,
                limitations);
    }

    private void validate(List<String> trialIds, double[][] returns, int partitionCount) {
        Objects.requireNonNull(trialIds, "trialIds");
        Objects.requireNonNull(returns, "chronologicalReturns");
        if (trialIds.size() < 2) throw new IllegalArgumentException("at least two registered trials are required");
        Set<String> uniqueIds = new HashSet<>();
        for (String trialId : trialIds) {
            if (trialId == null || trialId.isBlank()) throw new IllegalArgumentException("trialId must not be blank");
            if (!uniqueIds.add(trialId.trim())) throw new IllegalArgumentException("duplicate trialId: " + trialId.trim());
        }
        if (partitionCount < 4 || partitionCount % 2 != 0) {
            throw new IllegalArgumentException("partitionCount must be even and at least 4");
        }
        if (partitionCount > 18) {
            throw new IllegalArgumentException("partitionCount creates more than "
                    + MAX_COMBINATIONS + " CSCV combinations");
        }
        if (returns.length < partitionCount || returns.length % partitionCount != 0) {
            throw new IllegalArgumentException(
                    "observation count must be at least partitionCount and divisible into equal partitions");
        }
        long combinations = combinationCount(partitionCount, partitionCount / 2);
        if (combinations > MAX_COMBINATIONS) {
            throw new IllegalArgumentException("partitionCount creates more than " + MAX_COMBINATIONS + " CSCV combinations");
        }
        for (int row = 0; row < returns.length; row++) {
            if (returns[row] == null || returns[row].length != trialIds.size()) {
                throw new IllegalArgumentException("return matrix must be rectangular with one column per trial");
            }
            for (double value : returns[row]) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("return matrix must contain only finite values");
                }
            }
        }
    }

    private long combinationCount(int n, int k) {
        long result = 1;
        for (int i = 1; i <= k; i++) {
            result = Math.multiplyExact(result, n - k + i) / i;
        }
        return result;
    }

    private int[] initialCombination(int size) {
        int[] result = new int[size];
        for (int i = 0; i < size; i++) result[i] = i;
        return result;
    }

    private boolean advanceCombination(int[] combination, int populationSize) {
        int index = combination.length - 1;
        while (index >= 0 && combination[index] == populationSize - combination.length + index) index--;
        if (index < 0) return false;
        combination[index]++;
        for (int i = index + 1; i < combination.length; i++) {
            combination[i] = combination[i - 1] + 1;
        }
        return true;
    }

    private int indexOfMaximum(double[] values) {
        int selected = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[selected]) selected = i;
        }
        return selected;
    }

    private int countEqualTo(double[] values, double selected) {
        int count = 0;
        for (double value : values) if (Double.compare(value, selected) == 0) count++;
        return count;
    }

    private double averageRankFromWorst(double[] scores, double selectedScore) {
        int strictlyWorse = 0;
        int equal = 0;
        for (double score : scores) {
            int comparison = Double.compare(score, selectedScore);
            if (comparison < 0) strictlyWorse++;
            else if (comparison == 0) equal++;
        }
        return strictlyWorse + (equal + 1D) / 2D;
    }

    private double median(double[] values) {
        Arrays.sort(values);
        int middle = values.length / 2;
        return values.length % 2 == 0
                ? (values[middle - 1] + values[middle]) / 2D
                : values[middle];
    }
}
