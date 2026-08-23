/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

public final class OutageLedgerTest {
    private static final long SECOND = 1_000L;
    private static final long MINUTE = 60 * SECOND;

    public static void main(String[] args) {
        testCleanNetworkBlamesTheBroker();
        testDropBeforeDetectionBlamesTheNetwork();
        testOngoingNetworkOutageBlamesTheNetwork();
        testOldBlipsAreNotBlamed();
        testStartingOfflineCounts();
        testDuplicateCallbacksAreIdempotent();
        System.out.println("OutageLedgerTest passed");
    }

    private static void testCleanNetworkBlamesTheBroker() {
        OutageLedger ledger = new OutageLedger(true, 0);
        // A broker restart: MQTT lost at t=10m, back at t=12m, network never moved.
        require(OutageLedger.CAUSE_BROKER.equals(
                        ledger.causeOfMqttOutage(10 * MINUTE, 12 * MINUTE)),
                "a clean network should mean the broker died alone");
    }

    private static void testDropBeforeDetectionBlamesTheNetwork() {
        OutageLedger ledger = new OutageLedger(true, 0);
        // The wire broke at t=10m and was back 20s later; the keep-alive only notices the loss
        // 45s after the break. Without the lookback this reads as a spotless network.
        ledger.networkLost(10 * MINUTE);
        ledger.networkAvailable(10 * MINUTE + 20 * SECOND);
        long reportedLost = 10 * MINUTE + 45 * SECOND;
        require(OutageLedger.CAUSE_NETWORK.equals(
                        ledger.causeOfMqttOutage(reportedLost, 11 * MINUTE)),
                "a drop just before detection was misfiled as a broker problem");
    }

    private static void testOngoingNetworkOutageBlamesTheNetwork() {
        OutageLedger ledger = new OutageLedger(true, 0);
        // The network went down and is still down when MQTT limps back over... this cannot
        // actually happen live (no network, no reconnect), but a reconnect racing the callback
        // can observe it, and the answer must still be "network".
        ledger.networkLost(10 * MINUTE);
        require(OutageLedger.CAUSE_NETWORK.equals(
                        ledger.causeOfMqttOutage(11 * MINUTE, 12 * MINUTE)),
                "an open-ended network outage was not counted");
        require(ledger.networkDownMsWithin(10 * MINUTE, 12 * MINUTE) == 2 * MINUTE,
                "open interval overlap is wrong");
    }

    private static void testOldBlipsAreNotBlamed() {
        OutageLedger ledger = new OutageLedger(true, 0);
        // A blip an hour ago must not taint a fresh outage that starts on a clean network.
        ledger.networkLost(5 * MINUTE);
        ledger.networkAvailable(6 * MINUTE);
        require(OutageLedger.CAUSE_BROKER.equals(
                        ledger.causeOfMqttOutage(65 * MINUTE, 66 * MINUTE)),
                "an hour-old blip was blamed for a fresh outage");
    }

    private static void testStartingOfflineCounts() {
        // The service can start while the network is still coming up; that period is down time.
        OutageLedger ledger = new OutageLedger(false, 10 * MINUTE);
        ledger.networkAvailable(11 * MINUTE);
        require(ledger.networkDownMsWithin(10 * MINUTE, 12 * MINUTE) == MINUTE,
                "the initial offline period was not recorded");
    }

    private static void testDuplicateCallbacksAreIdempotent() {
        OutageLedger ledger = new OutageLedger(true, 0);
        ledger.networkLost(10 * MINUTE);
        ledger.networkLost(11 * MINUTE);   // must not restart the interval
        ledger.networkAvailable(12 * MINUTE);
        ledger.networkAvailable(13 * MINUTE); // must not add a second interval
        require(ledger.networkDownMsWithin(0, 20 * MINUTE) == 2 * MINUTE,
                "duplicate callbacks corrupted the ledger");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
