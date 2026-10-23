/*
 * Copyright 2026 Muralis contributors
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
 * Weather: the current weather of a city from OpenWeatherMap, under the person's own key
 * (decided 2026-10-15: Open-Meteo's free tier is non-commercial, MET Norway asks apps not to call
 * it from every device). The values are sensors for the automations and MQTT, and the
 * weather set Ambient's information layer reads (capability {@link Integrations#WEATHER}).
 *
 * <p>The condition is one vocabulary of Muralis's own ({@link #condition}), mapped from
 * OpenWeatherMap's codes, so a later provider maps into the same words and a rule written for
 * "rain" keeps working.
 */
public final class WeatherIntegration implements Integrations.Source {
    static final String ID = "weather";
    static final String METRIC = "Celsius, m/s";
    static final String IMPERIAL = "Fahrenheit, mph";
    private static final String API = "https://api.openweathermap.org";

    public static final Integrations.Definition DEFINITION = new Integrations.Definition(ID, "Weather",
            "free",
            "The current weather of a city from OpenWeatherMap, with your own free key.",
            Arrays.asList(
                    new Integrations.Field("city", "City", Integrations.Scope.SHARED, false, true,
                            Integrations.Kind.TEXT, Collections.emptyList(), "", 0, 0,
                            "A city, or a city and its country: Vienna, AT."),
                    new Integrations.Field("key", "OpenWeatherMap key", Integrations.Scope.SHARED,
                            true, true, Integrations.Kind.TEXT, Collections.emptyList(), "", 0, 0,
                            "Free at openweathermap.org, under API keys."),
                    new Integrations.Field("units", "Units", Integrations.Scope.SHARED, false,
                            false, Integrations.Kind.CHOICE, Arrays.asList(METRIC, IMPERIAL),
                            METRIC, 0, 0, ""),
                    new Integrations.Field("interval_s", "Ask every (seconds)",
                            Integrations.Scope.SHARED, false, false, Integrations.Kind.NUMBER,
                            Collections.emptyList(), "600", 300, 86_400, "")),
            Collections.emptyList(), 600_000L, 300_000L, new WeatherIntegration(),
            Integrations.WEATHER);

    @Override
    public List<Integrations.ValueSpec> values(Integrations.Definition definition,
            Integrations.Instance instance) {
        boolean imperial = IMPERIAL.equals(instance.setting(definition, "units"));
        String degrees = imperial ? "°F" : "°C";
        return Arrays.asList(
                new Integrations.ValueSpec("condition", "Condition", Integrations.Type.TEXT, ""),
                new Integrations.ValueSpec("temperature", "Temperature", Integrations.Type.NUMBER,
                        degrees),
                new Integrations.ValueSpec("feels_like", "Feels like", Integrations.Type.NUMBER,
                        degrees),
                new Integrations.ValueSpec("humidity", "Humidity", Integrations.Type.NUMBER, "%"),
                new Integrations.ValueSpec("wind", "Wind", Integrations.Type.NUMBER,
                        imperial ? "mph" : "m/s"),
                new Integrations.ValueSpec("is_day", "Daylight", Integrations.Type.FLAG, ""),
                new Integrations.ValueSpec("description", "Description", Integrations.Type.TEXT,
                        ""));
    }

    @Override
    public String check(Integrations.Definition definition, Map<String, String> settings) {
        String city = settings.getOrDefault("city", "");
        return city.length() > 80 ? "the city is longer than 80 characters" : null;
    }

    @Override
    public Integrations.Result fetch(Integrations.Definition definition,
            Integrations.Instance instance, Map<String, String> secrets, Integrations.Http http) {
        String key = secrets.getOrDefault("key", "");
        if (key.isEmpty()) {
            return Integrations.Result.refused("no OpenWeatherMap key is saved");
        }
        boolean imperial = IMPERIAL.equals(instance.setting(definition, "units"));
        // "Vienna, AT" as OpenWeatherMap writes it: "Vienna,AT".
        String city = instance.setting(definition, "city").trim().replaceAll("\\s*,\\s*", ",");
        try {
            String place = PLACES.get(city);
            if (place == null) {
                // The city to a place once, as OpenWeatherMap asks (its by-name lookup is
                // retired); kept while the panel runs, so each later ask is one call.
                Integrations.Response found = http.get(API + "/geo/1.0/direct?limit=1&q="
                        + encode(city) + "&appid=" + encode(key), Collections.emptyMap());
                Integrations.Result refused = refusal(found);
                if (refused != null) {
                    return refused;
                }
                List<Object> places = TinyJson.array(TinyJson.parse(found.body));
                Map<String, Object> first = places == null || places.isEmpty() ? null
                        : TinyJson.object(places.get(0));
                if (first == null || !(first.get("lat") instanceof Double)
                        || !(first.get("lon") instanceof Double)) {
                    return Integrations.Result.failed("OpenWeatherMap knows no city called "
                            + city, 0);
                }
                place = "lat=" + coordinate((Double) first.get("lat")) + "&lon="
                        + coordinate((Double) first.get("lon"));
                synchronized (PLACES) {
                    if (PLACES.size() > 32) {
                        PLACES.clear();
                    }
                    PLACES.put(city, place);
                }
            }
            Integrations.Response now = http.get(API + "/data/2.5/weather?" + place
                    + "&units=" + (imperial ? "imperial" : "metric")
                    + "&appid=" + encode(key), Collections.emptyMap());
            Integrations.Result refused = refusal(now);
            if (refused != null) {
                return refused;
            }
            return Integrations.Result.of(read(now.body));
        } catch (java.io.IOException unreachable) {
            return Integrations.Result.failed("OpenWeatherMap did not answer"
                    + Integrations.why(unreachable), 0);
        } catch (IllegalArgumentException unreadable) {
            return Integrations.Result.failed("OpenWeatherMap's answer could not be read", 0);
        }
    }

    /**
     * Null for an answer to read, else why not. A refused key is a failure, asked again, not a
     * stop: a new OpenWeatherMap key is refused for up to two hours before it works, and the
     * person should not have to save the same key twice.
     */
    private static Integrations.Result refusal(Integrations.Response response) {
        if (response.status == 401) {
            return Integrations.Result.failed("OpenWeatherMap refused the key (HTTP 401); a new"
                    + " key can take up to two hours to work", 0);
        }
        if (response.status < 200 || response.status > 299) {
            return Integrations.Result.failed("OpenWeatherMap answered HTTP " + response.status,
                    response.retryAfterMs);
        }
        return null;
    }

    /** The values from OpenWeatherMap's current weather. Throws on an answer of another shape. */
    static Map<String, Integrations.Value> read(String body) {
        Map<String, Object> root = TinyJson.object(TinyJson.parse(body));
        if (root == null) {
            throw new IllegalArgumentException("not an object");
        }
        Map<String, Object> main = TinyJson.object(root.get("main"));
        Map<String, Object> wind = TinyJson.object(root.get("wind"));
        List<Object> weather = TinyJson.array(root.get("weather"));
        Map<String, Object> first = weather == null || weather.isEmpty() ? null
                : TinyJson.object(weather.get(0));
        if (main == null || first == null || !(first.get("id") instanceof Double)) {
            throw new IllegalArgumentException("no current weather");
        }
        Map<String, Integrations.Value> values = new LinkedHashMap<>();
        values.put("condition", Integrations.Value.of(
                condition((int) (double) (Double) first.get("id"))));
        values.put("temperature", number(main.get("temp")));
        values.put("feels_like", number(main.get("feels_like")));
        values.put("humidity", number(main.get("humidity")));
        values.put("wind", wind == null ? Integrations.Value.unavailable(Integrations.Type.NUMBER)
                : number(wind.get("speed")));
        String icon = TinyJson.string(first, "icon", "");
        values.put("is_day", icon.isEmpty() ? Integrations.Value.unavailable(Integrations.Type.FLAG)
                : Integrations.Value.of(icon.endsWith("d")));
        values.put("description", Integrations.Value.of(TinyJson.string(first, "description",
                "")));
        return values;
    }

    private static Integrations.Value number(Object found) {
        return found instanceof Double ? Integrations.Value.of(Math.round((Double) found * 10) / 10.0)
                : Integrations.Value.unavailable(Integrations.Type.NUMBER);
    }

    /**
     * Muralis's weather words from an OpenWeatherMap condition code
     * (openweathermap.org/weather-conditions): clear, partly_cloudy, cloudy, fog, haze, drizzle,
     * rain_light, rain, rain_heavy, freezing_rain, snow_light, snow, snow_heavy, sleet, thunder,
     * wind. A code it does not know is "unknown", never a guess.
     */
    static String condition(int code) {
        if (code >= 200 && code < 300) {
            return "thunder";
        }
        if (code >= 300 && code < 400) {
            return "drizzle";
        }
        switch (code) {
            case 500: case 520: return "rain_light";
            case 501: case 521: case 531: return "rain";
            case 502: case 503: case 504: case 522: return "rain_heavy";
            case 511: return "freezing_rain";
            case 600: case 620: return "snow_light";
            case 601: case 621: return "snow";
            case 602: case 622: return "snow_heavy";
            case 611: case 612: case 613: case 615: case 616: return "sleet";
            case 701: case 741: return "fog";
            case 711: case 721: case 731: case 751: case 761: case 762: return "haze";
            case 771: case 781: return "wind";
            case 800: return "clear";
            case 801: case 802: return "partly_cloudy";
            case 803: case 804: return "cloudy";
            default: return "unknown";
        }
    }

    /** Each city's place as OpenWeatherMap found it, so it is looked up once. */
    private static final Map<String, String> PLACES =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /** A coordinate as plain digits: never "1.0E-4", which a query may not take. */
    static String coordinate(double degrees) {
        return new java.math.BigDecimal(degrees).setScale(4, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String encode(String text) {
        try {
            return java.net.URLEncoder.encode(text, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
