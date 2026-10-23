/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis.integrations;

import org.spazio17.muralis.Integrations;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Weather: the city lookup, reading the answer, the condition words and each failure. */
public final class WeatherIntegrationTest {

    public static void main(String[] args) throws Exception {
        Integrations.Definition weather = WeatherIntegration.DEFINITION;
        require(Integrations.DEFINITIONS.get("weather") == weather
                && Integrations.WEATHER.equals(weather.capability), "registered as weather");
        Map<String, String> settings = new HashMap<>();
        settings.put("city", "Vienna, AT");
        require(Integrations.check(weather, settings, Collections.emptySet()) != null,
                "a key is needed");
        require(Integrations.check(weather, settings, Collections.singleton("key")) == null,
                "a stored key passes");
        settings.put("interval_s", "60");
        require(Integrations.check(weather, settings, Collections.singleton("key")) != null,
                "asking every minute was taken");

        require(WeatherIntegration.condition(800).equals("clear")
                && WeatherIntegration.condition(804).equals("cloudy")
                && WeatherIntegration.condition(502).equals("rain_heavy")
                && WeatherIntegration.condition(211).equals("thunder")
                && WeatherIntegration.condition(611).equals("sleet")
                && WeatherIntegration.condition(741).equals("fog")
                && WeatherIntegration.condition(999).equals("unknown"), "condition words");

        String now = "{\"weather\":[{\"id\":500,\"description\":\"light rain\",\"icon\":\"10n\"}],"
                + "\"main\":{\"temp\":7.26,\"feels_like\":4.5,\"humidity\":81},"
                + "\"wind\":{\"speed\":3.6}}";
        Map<String, Integrations.Value> read = WeatherIntegration.read(now);
        require(read.get("condition").text.equals("rain_light")
                && read.get("temperature").number == 7.3 && !read.get("is_day").flag
                && read.get("humidity").number == 81
                && read.get("description").text.equals("light rain"), "reading: " + read);

        Integrations.Instance instance = new Integrations.Instance("weather1", "weather",
                "Vienna", true, Collections.singletonMap("city", "Vienna, AT"));
        require(weather.valuesOf(instance).size() == 7
                && weather.valuesOf(instance).get(1).unit.equals("°C")
                && weather.intervalOf(instance) == 600_000L, "values and interval");
        String[] asked = new String[2];
        Integrations.Result ok = weather.source.fetch(weather, instance,
                Collections.singletonMap("key", "k3y"), (url, headers) -> {
                    if (url.contains("/geo/")) {
                        asked[0] = url;
                        return new Integrations.Response(200,
                                "[{\"lat\":48.2,\"lon\":16.37,\"name\":\"Vienna\"}]", 0);
                    }
                    asked[1] = url;
                    return new Integrations.Response(200, now, 0);
                });
        require(ok.problem == null && asked[0].contains("q=Vienna%2CAT")
                && asked[1].contains("lat=48.2000") && asked[1].contains("units=metric"),
                "a good ask: " + ok.problem + " " + asked[0]);
        asked[0] = null;
        weather.source.fetch(weather, instance, Collections.singletonMap("key", "k3y"),
                (url, headers) -> {
                    if (url.contains("/geo/")) {
                        asked[0] = url;
                    }
                    return new Integrations.Response(200, now, 0);
                });
        require(asked[0] == null, "the city was looked up again");
        require(WeatherIntegration.coordinate(0.0001).equals("0.0001"), "a small coordinate");
        Integrations.Instance elsewhere = new Integrations.Instance("weather2", "weather",
                "Nowhere", true, Collections.singletonMap("city", "Nowhere"));
        Integrations.Result nowhere = weather.source.fetch(weather, elsewhere,
                Collections.singletonMap("key", "k3y"),
                (url, headers) -> new Integrations.Response(200, "[]", 0));
        require(nowhere.problem.contains("no city"), "an unknown city");
        Integrations.Result refused = weather.source.fetch(weather, elsewhere,
                Collections.singletonMap("key", "bad"),
                (url, headers) -> new Integrations.Response(401, "", 0));
        require(!refused.credentialsRefused && refused.problem.contains("two hours"),
                "a refused key is asked again: a new key works only after a while");
        Integrations.Result leak = weather.source.fetch(weather, elsewhere,
                Collections.singletonMap("key", "s3cret"), (url, headers) -> {
                    throw new java.io.FileNotFoundException(url);
                });
        require(!leak.problem.contains("s3cret"), "the key leaked into the status line");
        System.out.println("WeatherIntegrationTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
