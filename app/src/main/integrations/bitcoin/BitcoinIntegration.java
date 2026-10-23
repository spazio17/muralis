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
 * Bitcoin: a block clock's data from mempool's public API, on mempool.space or a server of the
 * person's own. Three asks: /api/blocks/tip/height (plain text), /api/v1/fees/recommended and
 * /api/v1/prices (JSON). A self-hosted mempool may have its prices switched off, so the prices
 * never fail the ask: without them the price and sats per unit are left out (unavailable), and
 * the height, the fees and the halving countdown still come through.
 */
public final class BitcoinIntegration implements Integrations.Source {
    static final String ID = "bitcoin";
    /** Blocks between two halvings of the block reward. */
    static final long HALVING_INTERVAL = 210_000L;
    /** Satoshis in one bitcoin. */
    private static final double SATS_PER_BITCOIN = 100_000_000d;

    public static final Integrations.Definition DEFINITION = new Integrations.Definition(ID,
            "Bitcoin", "free",
            "A block clock: the Bitcoin block height, price, fees and the halving countdown, from"
                    + " mempool.space or your own mempool server.",
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
                        Integrations.Type.NUMBER, ""));
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
            values.putAll(price(prices(http, base + "/api/v1/prices", json), currency));
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
     * The height, the fees and the halving countdown from the two answers that must come.
     * Throws on an answer of another shape.
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
        return values;
    }

    /**
     * The price in {@code currency} and the sats one unit of it buys, or nothing when the answer
     * is missing, unreadable, or has no positive price for it (mempool writes 0 or -1 for a
     * currency it has no price for). Never throws: the prices never fail the ask.
     */
    static Map<String, Integrations.Value> price(String pricesBody, String currency) {
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
        return values;
    }
}
