# Bitcoin

A block clock's data from mempool.space or your own mempool server: the block height, the price,
how many sats one unit of your currency buys, the recommended fees and how many blocks are left
until the next halving. The values are sensors of the panel: the automations act on them ("when
the fast fee falls below 5 sat/vB, show a pop-up"), and MQTT publishes them.

## What you need

Nothing: mempool.space is public and needs no key. For privacy, point it at your own mempool
server instead, so no outside service sees the panel asking.

## Settings

| Setting | Required | Default | What it is |
| --- | --- | --- | --- |
| Server | yes | `https://mempool.space` | http:// or https://, the address of a mempool server: `http://192.168.1.20:8999` |
| Currency | no | USD | USD, EUR, GBP, CAD, CHF, AUD or JPY |
| Ask every (seconds) | no | 60 | 60 to 86,400 |

## What it gives Muralis

| Value | Type |
| --- | --- |
| Block height | number |
| Price | number, in the chosen currency |
| Sats per unit | number, sat: how many sats one unit of the currency buys |
| Fast fee, Half-hour fee, Hour fee | number, sat/vB: mempool's recommended fees |
| Blocks to halving | number |

## Limits

- mempool.space publishes no rate limits, and its maintainers suggest your own server for heavy
  use. One ask a minute per panel, or per fleet when the fleet shares it, is light.
- Each ask is three calls: the height, the fees and the prices.
- A self-hosted server with prices switched off gives no price or sats per unit; the height, the
  fees and the halving countdown still come.
- After three failures in a row the values are unavailable and no rule fires on them.

## Removing it

Remove takes its values out of the automations and MQTT; rules that wait for them stay
and do not run until they are changed.
