# Weather

The current weather of a city from OpenWeatherMap: the condition, the temperature, how it feels,
the humidity, the wind and whether it is daylight. The values are sensors of the panel: the
automations act on them ("when the condition becomes rain, show a pop-up"), MQTT publishes
them, and Ambient's information layer will read them.

## What you need

A free OpenWeatherMap key: sign up at openweathermap.org and copy it from "API keys". A new key
takes up to a couple of hours to start working. The free plan allows 60 calls a minute and a
million a month; this integration makes one call every ten minutes by default.

## Settings

| Setting | Required | Default | What it is |
| --- | --- | --- | --- |
| City | yes | | a city, or a city and its country: `Vienna, AT` |
| OpenWeatherMap key | yes | | kept encrypted on the panel, never shown or logged |
| Units | no | Celsius, m/s | or Fahrenheit, mph |
| Ask every (seconds) | no | 600 | 300 to 86,400 |

## What it gives Muralis

| Value | Type |
| --- | --- |
| Condition | text: `clear`, `partly_cloudy`, `cloudy`, `fog`, `haze`, `drizzle`, `rain_light`, `rain`, `rain_heavy`, `freezing_rain`, `snow_light`, `snow`, `snow_heavy`, `sleet`, `thunder`, `wind`, `unknown` |
| Temperature, Feels like | number, °C or °F |
| Humidity | number, % |
| Wind | number, m/s or mph |
| Daylight | on or off |
| Description | OpenWeatherMap's own words: "light rain" |

## Limits

- The city goes to OpenWeatherMap's geocoding first, then the weather at that place is asked.
- After three failures in a row the values are unavailable and no rule fires on them.
- A refused key (HTTP 401) is asked again at the next turn, since a new key works only after up
  to two hours; after three refusals in a row the values are unavailable until it works.
- The city is looked up once while the panel runs; each later ask is one call.

## Removing it

Remove takes its values out of the automations and MQTT; rules that wait for them stay
and do not run until they are changed.
