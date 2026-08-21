/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

public final class MemoryBaselineTest {
    /** Roughly what a settled Home Assistant dashboard costs on the test tablet, in KiB. */
    private static final long SETTLED_KB = 1_620_000;

    public static void main(String[] args) {
        testUntrainedSaysNothing();
        testBudgetSitsAboveTheMean();
        testScalesWithTheDevice();
        testDoesNotLearnFromItsOwnTrigger();
        testAdaptsToADashboardThatGrewForReal();
        testStorageRoundTrip();
        System.out.println("MemoryBaselineTest passed");
    }

    /** Nothing observed, nothing claimed. A fresh panel must not act on memory at all. */
    private static void testUntrainedSaysNothing() {
        MemoryBaseline baseline = MemoryBaseline.empty();
        require(!baseline.trusted(), "an empty baseline claimed to be trusted");
        require(baseline.budgetKb() == MemoryBaseline.UNKNOWN, "an empty baseline offered a budget");

        for (int i = 0; i < MemoryBaseline.MIN_GENERATIONS - 1; i++) {
            baseline = baseline.observeNaturalEnd(SETTLED_KB);
            require(baseline.budgetKb() == MemoryBaseline.UNKNOWN,
                    "offered a budget after only " + (i + 1) + " generations");
        }
        baseline = baseline.observeNaturalEnd(SETTLED_KB);
        require(baseline.trusted(), "still untrusted after MIN_GENERATIONS observations");
    }

    /**
     * The regression that motivated MIN_SPREAD_FRACTION: identical observations give a variance of
     * zero, so mean + 3σ was exactly the mean, and one kilobyte over the historical average read as
     * unbounded growth. A leak detector that fires on noise is a periodic reloader.
     */
    private static void testBudgetSitsAboveTheMean() {
        MemoryBaseline flat = trained(SETTLED_KB);
        require(flat.budgetKb() > SETTLED_KB,
                "budget collapsed onto the mean: " + flat.budgetKb() + " vs " + SETTLED_KB);
        long headroom = flat.budgetKb() - SETTLED_KB;
        require(headroom > SETTLED_KB / 100,
                "headroom is implausibly tight: " + headroom + " KiB");
    }

    /**
     * The whole point of the rewrite. The same absolute footprint must be judged differently on
     * different hardware, because the baseline is learned per device rather than written down here.
     */
    private static void testScalesWithTheDevice() {
        long smallPanel = 400_000;
        long largePanel = 6_000_000;
        require(trained(smallPanel).budgetKb() < largePanel,
                "a small panel's budget somehow allowed a large panel's footprint");
        require(trained(largePanel).budgetKb() > trained(smallPanel).budgetKb(),
                "budgets did not scale with the learned footprint");
        // A footprint that is a leak on the small device is unremarkable on the large one.
        long footprint = 1_000_000;
        require(footprint > trained(smallPanel).budgetKb(),
                "1G was not treated as growth on a panel that normally uses 400M");
        require(footprint < trained(largePanel).budgetKb(),
                "1G was treated as growth on a panel that normally uses 6G");
    }

    /**
     * A single growth trip must not move the mean, or the model chases its own trigger upward and
     * ends up measuring nothing.
     */
    private static void testDoesNotLearnFromItsOwnTrigger() {
        MemoryBaseline baseline = trained(SETTLED_KB);
        long before = baseline.budgetKb();
        MemoryBaseline afterOneTrip = baseline.observeGrowthTrip(SETTLED_KB * 3);
        require(afterOneTrip.budgetKb() == before,
                "one growth trip moved the budget: " + afterOneTrip.budgetKb() + " vs " + before);
        require(afterOneTrip.generations == baseline.generations,
                "one growth trip counted as a generation");
    }

    /**
     * ...but a dashboard that genuinely got heavier — a Home Assistant update, more tiles — must
     * become the new normal rather than being reloaded every thirty minutes forever. This is the
     * "adapt over months" half, and the reason the trip counter exists at all.
     */
    private static void testAdaptsToADashboardThatGrewForReal() {
        MemoryBaseline baseline = trained(SETTLED_KB);
        long heavier = SETTLED_KB * 2;
        long before = baseline.budgetKb();

        // Two trips at the same higher level: a leak does not stabilise, so this is the new normal.
        baseline = baseline.observeGrowthTrip(heavier);
        baseline = baseline.observeGrowthTrip(heavier);
        require(baseline.budgetKb() > before, "the budget did not move after a sustained increase");

        // Keep going and it should settle around the new footprint rather than climbing forever.
        for (int i = 0; i < 20; i++) {
            baseline = baseline.observeNaturalEnd(heavier);
        }
        require(baseline.budgetKb() > heavier,
                "the settled budget fell below the footprint it was trained on");
        require(baseline.budgetKb() < heavier * 2,
                "the budget ran away upward: " + baseline.budgetKb());
        require(baseline.consecutiveGrowthTrips == 0, "trips were not reset by a natural end");
    }

    private static void testStorageRoundTrip() {
        MemoryBaseline baseline = trained(SETTLED_KB).observeGrowthTrip(SETTLED_KB * 3);
        MemoryBaseline back = MemoryBaseline.fromStorage(baseline.toStorage());
        require(back.generations == baseline.generations, "generations lost in storage");
        require(back.budgetKb() == baseline.budgetKb(), "budget changed across storage");
        require(back.consecutiveGrowthTrips == baseline.consecutiveGrowthTrips,
                "trip counter lost in storage");

        // Anything unparseable starts over, which costs learning time and nothing else. Cheaper
        // than trusting a half-written value: a wrong budget reloads a working panel.
        for (String rubbish : new String[] {null, "", "nonsense", "1:2:3", "1:2:3:4:5",
            "-1:2:3:4", "1:nan:3:4", "1:Infinity:3:4", "a:b:c:d"}) {
            require(!MemoryBaseline.fromStorage(rubbish).trusted(),
                    "trusted a baseline parsed from \"" + rubbish + "\"");
        }
    }

    private static MemoryBaseline trained(long usedKb) {
        MemoryBaseline baseline = MemoryBaseline.empty();
        for (int i = 0; i < MemoryBaseline.MIN_GENERATIONS + 2; i++) {
            baseline = baseline.observeNaturalEnd(usedKb);
        }
        return baseline;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
