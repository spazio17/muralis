/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis.integrations;

import org.spazio17.muralis.Integrations;
import org.spazio17.muralis.TinyJson;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Bitcoin clock: the network's state from mempool's public API, on mempool.space or a server
 * of the person's own, for its own full-screen page (BitcoinClock) and as values for the
 * automations and MQTT. Three asks: /api/blocks/tip/height (plain text),
 * /api/v1/fees/recommended and /api/v1/prices (JSON). A self-hosted mempool may have its prices
 * switched off, so the prices never fail the ask: without them the price, sats per unit and
 * market cap are left out (unavailable), and the height, the fees, the supply and the halving
 * still come through. The supply and the halving are worked out from the height.
 */
public final class BitcoinIntegration implements Integrations.Source {
    static final String ID = "bitcoin";
    /** Blocks between two halvings of the block reward. */
    static final long HALVING_INTERVAL = 210_000L;
    /** Satoshis in one bitcoin. */
    private static final double SATS_PER_BITCOIN = 100_000_000d;
    /** The first block reward, in satoshis; it halves every {@link #HALVING_INTERVAL} blocks. */
    private static final long FIRST_REWARD_SATS = 50L * 100_000_000L;

    public static final Integrations.Definition DEFINITION = new Integrations.Definition(ID,
            "Bitcoin clock", "free",
            "A clock of the Bitcoin network, as a dashboard.",
            Arrays.asList(
                    new Integrations.Field("url", "Server", Integrations.Scope.SHARED, false, true,
                            Integrations.Kind.URL, Collections.emptyList(),
                            "https://mempool.space", 0, 0,
                            "mempool.space, or the address of your own mempool server."),
                    new Integrations.Field("currency", "Currency", Integrations.Scope.SHARED,
                            false, false, Integrations.Kind.CHOICE,
                            Arrays.asList("USD", "EUR", "GBP", "CAD", "CHF", "AUD", "JPY"), "USD",
                            0, 0, ""),
                    new Integrations.Field("interval_s", "Ask every (seconds)",
                            Integrations.Scope.SHARED, false, false, Integrations.Kind.NUMBER,
                            Collections.emptyList(), "60", 60, 86_400, "")),
            Collections.emptyList(),
            // A block comes about every ten minutes and the fees move with it: asking more often
            // than a minute brings nothing new and only loads a shared public server.
            60_000L, 60_000L, new BitcoinIntegration());

    @Override
    public List<Integrations.ValueSpec> values(Integrations.Definition definition,
            Integrations.Instance instance) {
        return Arrays.asList(
                new Integrations.ValueSpec("height", "Block height", Integrations.Type.NUMBER, ""),
                new Integrations.ValueSpec("price", "Price", Integrations.Type.NUMBER,
                        instance.setting(definition, "currency")),
                new Integrations.ValueSpec("sats_per_unit", "Sats per unit",
                        Integrations.Type.NUMBER, "sat"),
                new Integrations.ValueSpec("fee_fast", "Fast fee", Integrations.Type.NUMBER,
                        "sat/vB"),
                new Integrations.ValueSpec("fee_half_hour", "Half-hour fee",
                        Integrations.Type.NUMBER, "sat/vB"),
                new Integrations.ValueSpec("fee_hour", "Hour fee", Integrations.Type.NUMBER,
                        "sat/vB"),
                new Integrations.ValueSpec("blocks_to_halving", "Blocks to halving",
                        Integrations.Type.NUMBER, ""),
                new Integrations.ValueSpec("supply", "Supply", Integrations.Type.NUMBER, "BTC"),
                new Integrations.ValueSpec("market_cap", "Market cap", Integrations.Type.NUMBER,
                        instance.setting(definition, "currency")));
    }

    @Override
    public Integrations.Result fetch(Integrations.Definition definition,
            Integrations.Instance instance, Map<String, String> secrets, Integrations.Http http) {
        String base = instance.setting(definition, "url").replaceAll("/+$", "");
        String currency = instance.setting(definition, "currency");
        Map<String, String> json = Collections.singletonMap("Accept", "application/json");
        try {
            Integrations.Response height = http.get(base + "/api/blocks/tip/height",
                    Collections.emptyMap());
            Integrations.Result refused = refusal(height);
            if (refused != null) {
                return refused;
            }
            Integrations.Response fees = http.get(base + "/api/v1/fees/recommended", json);
            refused = refusal(fees);
            if (refused != null) {
                return refused;
            }
            Map<String, Integrations.Value> values = read(height.body, fees.body);
            values.putAll(price(prices(http, base + "/api/v1/prices", json), currency,
                    values.get("supply").number));
            return Integrations.Result.of(values);
        } catch (java.io.IOException unreachable) {
            return Integrations.Result.failed("mempool did not answer"
                    + Integrations.why(unreachable), 0);
        } catch (IllegalArgumentException unreadable) {
            return Integrations.Result.failed("mempool's answer could not be read", 0);
        }
    }

    /** Null for an answer to read, else why not. */
    private static Integrations.Result refusal(Integrations.Response response) {
        if (response.status < 200 || response.status > 299) {
            return Integrations.Result.failed("mempool answered HTTP " + response.status,
                    response.retryAfterMs);
        }
        return null;
    }

    /**
     * The prices' answer, or null when there is none to read: a server with prices switched off
     * answers 404, and a failed price ask must not cost the height and the fees that came.
     */
    private static String prices(Integrations.Http http, String url, Map<String, String> headers) {
        try {
            Integrations.Response prices = http.get(url, headers);
            return prices.status >= 200 && prices.status <= 299 ? prices.body : null;
        } catch (java.io.IOException unreachable) {
            return null;
        }
    }

    /**
     * The height, the fees, the halving countdown and the supply from the two answers that must
     * come. Throws on an answer of another shape.
     */
    static Map<String, Integrations.Value> read(String heightBody, String feesBody) {
        String text = heightBody == null ? "" : heightBody.trim();
        if (!text.matches("\\d{1,9}")) {
            throw new IllegalArgumentException("not a block height");
        }
        long height = Long.parseLong(text);
        Map<String, Object> fees = TinyJson.object(TinyJson.parse(feesBody));
        Object fast = fees.get("fastestFee");
        Object halfHour = fees.get("halfHourFee");
        Object hour = fees.get("hourFee");
        if (!(fast instanceof Double) || !(halfHour instanceof Double)
                || !(hour instanceof Double)) {
            throw new IllegalArgumentException("not the recommended fees");
        }
        Map<String, Integrations.Value> values = new LinkedHashMap<>();
        values.put("height", Integrations.Value.of(height));
        values.put("fee_fast", Integrations.Value.of((Double) fast));
        values.put("fee_half_hour", Integrations.Value.of((Double) halfHour));
        values.put("fee_hour", Integrations.Value.of((Double) hour));
        values.put("blocks_to_halving",
                Integrations.Value.of(HALVING_INTERVAL - height % HALVING_INTERVAL));
        values.put("supply", Integrations.Value.of(supplySats(height) / SATS_PER_BITCOIN));
        return values;
    }

    /**
     * The satoshis issued up to and with the block at {@code height}: every block's reward, the
     * genesis block's included, each era's reward half the last one's, in whole satoshis as the
     * protocol rounds it down.
     */
    static long supplySats(long height) {
        long blocks = height + 1;
        long sats = 0;
        for (int era = 0; blocks > 0 && era < 64; era++) {
            long inEra = Math.min(blocks, HALVING_INTERVAL);
            sats += inEra * (FIRST_REWARD_SATS >> era);
            blocks -= inEra;
        }
        return sats;
    }

    /**
     * The price in {@code currency}, the sats one unit of it buys and the market cap of
     * {@code supply} bitcoins, or nothing when the answer is missing, unreadable, or has no
     * positive price for it (mempool writes 0 or -1 for a currency it has no price for). Never
     * throws: the prices never fail the ask.
     */
    static Map<String, Integrations.Value> price(String pricesBody, String currency,
            double supply) {
        if (pricesBody == null) {
            return Collections.emptyMap();
        }
        Object found;
        try {
            found = TinyJson.object(TinyJson.parse(pricesBody)).get(currency);
        } catch (IllegalArgumentException unreadable) {
            return Collections.emptyMap();
        }
        if (!(found instanceof Double) || (Double) found <= 0) {
            return Collections.emptyMap();
        }
        double price = (Double) found;
        Map<String, Integrations.Value> values = new LinkedHashMap<>();
        values.put("price", Integrations.Value.of(price));
        values.put("sats_per_unit", Integrations.Value.of(Math.round(SATS_PER_BITCOIN / price)));
        values.put("market_cap", Integrations.Value.of(Math.round(price * supply)));
        return values;
    }
}
