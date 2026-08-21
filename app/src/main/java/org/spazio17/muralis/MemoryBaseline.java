/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

/**
 * What this dashboard normally costs on this device, learned by watching it.
 *
 * <p>There is deliberately no memory constant in this class or in {@link RecyclePolicy}. The
 * previous design recycled when free memory fell below a fixed 12% of total, which cannot be right
 * for both of the devices this app targets: 4 GB resident is a leak on a 2 GB tablet and entirely
 * healthy on a 16 GB one, and "12% free" cannot tell a dashboard that is simply heavy from one that
 * is growing without bound. The question worth asking is not "how much is it using" but "how much
 * more than it usually uses", and only the device can answer that.
 *
 * <p>So this is a recommendation, not a limit — the same shape as a Kubernetes VPA raising a pod's
 * requests after watching real usage, rather than a number somebody guessed once and that OOMKills
 * the workload two releases later. It keeps an exponentially weighted mean and variance of the
 * footprint observed at the end of each dashboard generation, and {@link #budgetKb()} is that mean
 * plus {@link #SIGMA} standard deviations. Exponential rather than cumulative so that old
 * observations decay: if a dashboard update genuinely makes the page heavier for months, the
 * baseline follows it instead of fighting it forever.
 *
 * <p><b>Guarding against learning from itself.</b> If every generation that ended because
 * {@link #budgetKb()} was exceeded fed straight back into the mean, the mean would chase its own
 * trigger upward and the model would be measuring nothing. So a generation that ended on schedule
 * or under real system pressure is a {@linkplain #observeNaturalEnd natural end} and always counts,
 * while one cut short by growth only counts once it has happened
 * {@link #GROWTH_TRIPS_BEFORE_ACCEPTING} times consecutively. One early trip is a leak; the same
 * early trip twice in a row, at the same level, is the new normal — a leak does not stabilise.
 *
 * <p>Pure logic, no Android imports, every value passed in, so the whole model is covered by host
 * tests instead of by waiting a fortnight next to the tablet.
 */
final class MemoryBaseline {
    /**
     * Generations to observe before {@link #budgetKb()} means anything. Below this the variance is
     * not an estimate of anything, and acting on it would reload a healthy panel on the strength of
     * two samples.
     */
    static final int MIN_GENERATIONS = 4;
    /**
     * Weight of each new observation. At 0.25 the mean has effectively forgotten a value after
     * roughly a dozen generations, so a genuinely heavier dashboard becomes the new normal inside a
     * fortnight of nightly recycles, while one unusual night barely moves it.
     */
    static final double ALPHA = 0.25;
    /**
     * How far above the learned mean counts as growth rather than noise. Three standard deviations
     * of the device's own measured spread — not a memory figure, a confidence level.
     */
    static final double SIGMA = 3.0;
    /** Consecutive growth trips before the higher footprint is accepted as the new normal. */
    static final int GROWTH_TRIPS_BEFORE_ACCEPTING = 2;
    /**
     * Floor on the spread used by {@link #budgetKb()}, as a fraction of the learned mean.
     *
     * <p>Needed because the variance can legitimately be near zero — a panel whose generations all
     * end at almost the same footprint — and the budget would then collapse onto the mean, so any
     * blip a kilobyte above the historical average would read as unbounded growth. That turns a
     * leak detector into a periodic reloader.
     *
     * <p>A fraction of the mean rather than a number of megabytes, deliberately, for the same
     * reason nothing else here is absolute: it scales with whatever this dashboard costs on this
     * device. It is a floor only; once the device's own measured spread exceeds it, the measurement
     * wins.
     */
    static final double MIN_SPREAD_FRACTION = 0.05;

    final int generations;
    final double meanKb;
    final double varianceKb2;
    final int consecutiveGrowthTrips;

    private MemoryBaseline(int generations, double meanKb, double varianceKb2,
            int consecutiveGrowthTrips) {
        this.generations = generations;
        this.meanKb = meanKb;
        this.varianceKb2 = varianceKb2;
        this.consecutiveGrowthTrips = consecutiveGrowthTrips;
    }

    static MemoryBaseline empty() {
        return new MemoryBaseline(0, 0, 0, 0);
    }

    /**
     * A generation that ended on schedule, or under pressure the operating system itself reported.
     * Always folded in: nothing about this app's own thresholds decided when it ended, so it is an
     * honest sample of what a full generation costs here.
     */
    MemoryBaseline observeNaturalEnd(long usedKb) {
        return fold(usedKb, 0);
    }

    /**
     * A generation cut short because {@link #budgetKb()} was exceeded. Counted only once the same
     * thing has happened {@link #GROWTH_TRIPS_BEFORE_ACCEPTING} times running, so the model cannot
     * chase its own trigger, but a dashboard that has legitimately grown still moves the baseline
     * rather than being reloaded forever.
     */
    MemoryBaseline observeGrowthTrip(long usedKb) {
        int trips = consecutiveGrowthTrips + 1;
        if (trips < GROWTH_TRIPS_BEFORE_ACCEPTING) {
            return new MemoryBaseline(generations, meanKb, varianceKb2, trips);
        }
        return fold(usedKb, 0);
    }

    private MemoryBaseline fold(long usedKb, int trips) {
        if (usedKb <= 0) {
            // Nothing was measured; do not teach the model a zero.
            return new MemoryBaseline(generations, meanKb, varianceKb2, trips);
        }
        if (generations == 0) {
            return new MemoryBaseline(1, usedKb, 0, trips);
        }
        double delta = usedKb - meanKb;
        double nextMean = meanKb + ALPHA * delta;
        // EWMA of the squared deviation from the *previous* mean, which is the standard
        // incremental form and stays stable without keeping the samples themselves.
        double nextVariance = (1 - ALPHA) * (varianceKb2 + ALPHA * delta * delta);
        return new MemoryBaseline(generations + 1, nextMean, nextVariance, trips);
    }

    /** Whether enough generations have been seen for {@link #budgetKb()} to be worth acting on. */
    boolean trusted() {
        return generations >= MIN_GENERATIONS;
    }

    /**
     * The footprint above which this generation is growing rather than merely heavy, or
     * {@link #UNKNOWN} while the model is still learning.
     */
    long budgetKb() {
        if (!trusted()) {
            return UNKNOWN;
        }
        double measuredSpread = Math.sqrt(Math.max(0, varianceKb2));
        double spread = Math.max(measuredSpread, meanKb * MIN_SPREAD_FRACTION);
        return Math.round(meanKb + SIGMA * spread);
    }

    static final long UNKNOWN = -1L;

    /**
     * Survives a restart, so a panel does not spend its first fortnight after every reboot with no
     * opinion. Plain text rather than anything structured: five numbers written where the rest of
     * this app's settings live.
     */
    String toStorage() {
        return generations + ":" + meanKb + ":" + varianceKb2 + ":" + consecutiveGrowthTrips;
    }

    /** Anything unparseable starts over, which costs a fortnight of learning and nothing else. */
    static MemoryBaseline fromStorage(String stored) {
        if (stored == null) {
            return empty();
        }
        String[] parts = stored.split(":");
        if (parts.length != 4) {
            return empty();
        }
        try {
            int generations = Integer.parseInt(parts[0]);
            double mean = Double.parseDouble(parts[1]);
            double variance = Double.parseDouble(parts[2]);
            int trips = Integer.parseInt(parts[3]);
            if (generations < 0 || mean < 0 || variance < 0 || trips < 0
                    || Double.isNaN(mean) || Double.isNaN(variance)
                    || Double.isInfinite(mean) || Double.isInfinite(variance)) {
                return empty();
            }
            return new MemoryBaseline(generations, mean, variance, trips);
        } catch (NumberFormatException malformed) {
            return empty();
        }
    }
}
