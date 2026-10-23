# Value from a web address

Reads one number, text or on/off from any address that answers JSON or plain text: a status page,
Pi-hole, a solar inverter, a queue counter. The value is a sensor of the panel: the automations act
on it and MQTT publishes it.

## What you need

An address the panel can reach, and the place of the value in its answer.

## Settings

| Setting | Required | Default | What it is |
| --- | --- | --- | --- |
| Address | yes | | http:// or https://, answering JSON or plain text |
| Where the value is | no | the whole answer | names joined by dots, a number for an item of a list: `data.temperature`, `items.0.name` |
| The value is | no | Number | Number, Text or On or off |
| Unit | no | | for a number, up to 12 characters: `°C`, `W` |
| Header for a key | no | | the header a key goes in: `Authorization`, `X-API-Key` |
| Key | no | | kept encrypted on the panel, never shown or logged |
| Ask every (seconds) | no | 60 | 10 to 86,400 |

Try asks the address once with what is in the boxes, before anything is saved.

## What it gives Muralis

One value, `int_<instance>_value`: a number with its unit, a text (at most 255 characters), or on
and off (`true`, `1`, `on`, `yes`, `up` and their opposites). Rules can wait for it to rise above or
fall below a level, turn on or off, or become a text.

## Limits

- An answer larger than 4 MB, or slower than 30 seconds, is a failure.
- After three failures in a row the value is unavailable and no rule fires on it.
- A 401 or 403 with a key set stops the asking until a new key is saved; without a key it is a
  failure like any other.
- The key is sent only to the address given, never to another host a redirect leads to, and never
  from https down to http.

## Troubleshooting

The integration's line says what went wrong: "nothing at data.x in the answer", "the answer is not
JSON", "the value is not a number", "the address answered HTTP 500".

## Removing it

Remove takes its value out of the automations and MQTT; rules that wait for it stay and do
not run until they are changed.
