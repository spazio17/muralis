/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis.integrations;

import org.spazio17.muralis.Integrations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bitcoin: reading the height, fees and prices, the three asks, and each failure. */
public final class BitcoinIntegrationTest {

    static final String HEIGHT = "870123";
    static final String FEES = "{\"fastestFee\":12,\"halfHourFee\":8,\"hourFee\":5,"
            + "\"economyFee\":2,\"minimumFee\":1}";
    static final String PRICES = "{\"time\":1729670400,\"USD\":67000,\"EUR\":62000,"
            + "\"GBP\":51000,\"CAD\":92000,\"CHF\":58000,\"AUD\":100000,\"JPY\":-1}";

    public static void main(String[] args) throws Exception {
        Integrations.Definition bitcoin = BitcoinIntegration.DEFINITION;
        require(Integrations.DEFINITIONS.get("bitcoin") == bitcoin, "registered");
        Map<String, String> settings = new HashMap<>();
        settings.put("url", "mempool.space");
        require(Integrations.check(bitcoin, settings, Collections.emptySet()) != null,
                "a server without http:// or https:// was taken");
        settings.put("url", "http://192.0.2.20:8999/");
        settings.put("currency", "EUR");
        require(Integrations.check(bitcoin, settings, Collections.emptySet()) == null,
                "good settings refused");
        require(Integrations.check(bitcoin, Collections.emptyMap(), Collections.emptySet())
                == null, "the defaults refused");

        Map<String, Integrations.Value> read = BitcoinIntegration.read(HEIGHT + "\n", FEES);
        require(read.get("height").number == 870_123 && read.get("fee_fast").number == 12
                && read.get("fee_half_hour").number == 8 && read.get("fee_hour").number == 5
                && read.get("blocks_to_halving").number == 179_877,
                "reading: halving in " + read.get("blocks_to_halving").number);
        // Known supplies: the genesis block alone, the end of the first era, and block 840,000,
        // the fourth halving, after which 19,687,500 BTC had been issued.
        require(BitcoinIntegration.supplySats(0) == 5_000_000_000L
                && BitcoinIntegration.supplySats(209_999) == 1_050_000_000_000_000L
                && BitcoinIntegration.supplySats(839_999) == 1_968_750_000_000_000L,
                "supply: " + BitcoinIntegration.supplySats(839_999));
        require(BitcoinIntegration.supplySats(100_000_000) == 2_099_999_997_690_000L,
                "the supply never passes 21 million: " + BitcoinIntegration.supplySats(100_000_000));
        Map<String, Integrations.Value> euro = BitcoinIntegration.price(PRICES, "EUR",
                19_000_000d);
        require(euro.get("price").number == 62_000 && euro.get("sats_per_unit").number == 1613
                && euro.get("market_cap").number == 62_000d * 19_000_000d,
                "the price in euros: " + euro.get("sats_per_unit").number);
        require(BitcoinIntegration.price(PRICES, "JPY", 19_000_000d).isEmpty(),
                "a price of -1 was taken");

        Integrations.Instance instance = new Integrations.Instance("bitcoin1", "bitcoin",
                "Bitcoin clock", true, settings);
        require(bitcoin.valuesOf(instance).size() == 9
                && bitcoin.valuesOf(instance).get(1).unit.equals("EUR")
                && bitcoin.intervalOf(instance) == 60_000L, "values and interval");
        List<String> asked = new ArrayList<>();
        Integrations.Result ok = bitcoin.source.fetch(bitcoin, instance, Collections.emptyMap(),
                (url, headers) -> {
                    asked.add(url);
                    if (url.endsWith("/height")) {
                        return new Integrations.Response(200, HEIGHT, 0);
                    }
                    return new Integrations.Response(200, url.endsWith("/prices") ? PRICES : FEES,
                            0);
                });
        require(ok.problem == null && asked.size() == 3
                && asked.get(0).equals("http://192.0.2.20:8999/api/blocks/tip/height")
                && asked.get(1).equals("http://192.0.2.20:8999/api/v1/fees/recommended")
                && asked.get(2).equals("http://192.0.2.20:8999/api/v1/prices")
                && ok.values.get("price").number == 62_000, "a good ask: " + asked);

        Integrations.Result noPrices = bitcoin.source.fetch(bitcoin, instance,
                Collections.emptyMap(), (url, headers) -> {
                    if (url.endsWith("/prices")) {
                        return new Integrations.Response(404, "Not Found", 0);
                    }
                    return new Integrations.Response(200, url.endsWith("/height") ? HEIGHT : FEES,
                            0);
                });
        require(noPrices.problem == null && noPrices.values.get("height").number == 870_123
                && noPrices.values.get("fee_fast").number == 12
                && !noPrices.values.containsKey("price")
                && !noPrices.values.containsKey("sats_per_unit"),
                "a server without prices: " + noPrices.problem);

        Integrations.Result down = bitcoin.source.fetch(bitcoin, instance, Collections.emptyMap(),
                (url, headers) -> new Integrations.Response(500, "", 0));
        require(down.problem != null && down.problem.contains("HTTP 500") && down.values.isEmpty(),
                "a height that failed");
        Integrations.Result busy = bitcoin.source.fetch(bitcoin, instance, Collections.emptyMap(),
                (url, headers) -> new Integrations.Response(429, "", 90_000));
        require(busy.problem.contains("HTTP 429") && busy.retryAfterMs == 90_000,
                "Retry-After was lost");
        Integrations.Result html = bitcoin.source.fetch(bitcoin, instance, Collections.emptyMap(),
                (url, headers) -> new Integrations.Response(200, "<html>", 0));
        require(html.problem.contains("could not be read"), "an answer of another shape");
        Integrations.Result silent = bitcoin.source.fetch(bitcoin, instance,
                Collections.emptyMap(), (url, headers) -> {
                    throw new java.io.IOException("refused");
                });
        require(silent.problem.startsWith("mempool did not answer"), "no answer");
        System.out.println("BitcoinIntegrationTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
