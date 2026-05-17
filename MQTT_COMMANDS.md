# MQTT Command Reference

When the **Home Assistant Discovery** toggle is enabled on an MQTT connection, the app subscribes to `<base-topic>/command/#` and routes incoming messages to either the local BYD HAL or the BYD Cloud API.

`<base-topic>` is whatever you configured as the MQTT topic for the connection (e.g. `overdrive/vehicle/telemetry`). All command topics are appended after `/command/`.

> **Cloud-required commands** (lock, unlock, trunk open, flash) require BYD Cloud credentials to be configured in Settings → BYD Cloud. The trunk OPEN command also performs a cloud unlock first to avoid triggering the alarm.

---

## Windows

| Topic suffix | Payload | Notes |
|---|---|---|
| `window/lf` | `0`–`100` *or* `OPEN` / `CLOSE` / `STOP` | Left front |
| `window/rf` | `0`–`100` *or* `OPEN` / `CLOSE` / `STOP` | Right front |
| `window/lr` | `0`–`100` *or* `OPEN` / `CLOSE` / `STOP` | Left rear |
| `window/rr` | `0`–`100` *or* `OPEN` / `CLOSE` / `STOP` | Right rear |
| `window/sunroof` | `0`–`100` *or* `OPEN` / `CLOSE` / `STOP` | Sunroof |
| `window/sunshade` | `0`–`100` *or* `OPEN` / `CLOSE` / `STOP` | Sunshade |

Numeric payload triggers closed-loop positioning (the daemon drives the motor and auto-stops at the target). Any non-numeric payload that isn't `OPEN` or `CLOSE` is treated as `STOP`.

---

## Trunk

| Topic suffix | Payload | Notes |
|---|---|---|
| `trunk` | `OPEN` | **Cloud-required.** Unlocks the car first, waits 2 s, then opens the tailgate |
| `trunk` | `CLOSE` | Local HAL — closes the tailgate motor |
| `trunk` | *(anything else)* | Stops tailgate motion |

---

## Climate

| Topic suffix | Payload | Notes |
|---|---|---|
| `climate/power` | `ON` / `OFF` | A/C master power |
| `climate/fan` | `1`–`7` | Fan speed level |

---

## Seats

Heat and ventilation, driver + passenger.

| Topic suffix | Payload |
|---|---|
| `seat/driver/heat` | `OFF` / `LOW` / `MEDIUM` / `HIGH` |
| `seat/driver/vent` | `OFF` / `LOW` / `MEDIUM` / `HIGH` |
| `seat/pass/heat` | `OFF` / `LOW` / `MEDIUM` / `HIGH` |
| `seat/pass/vent` | `OFF` / `LOW` / `MEDIUM` / `HIGH` |

Any unrecognised payload maps to `OFF` (level 0).

---

## Charging

| Topic suffix | Payload | Notes |
|---|---|---|
| `charge/stop` | `50`–`100` | Stop-charge percentage. HA picker uses step 5 |

---

## Ambient Light

| Topic suffix | Payload | Notes |
|---|---|---|
| `ambient/power` | `ON` / `OFF` | Master switch |
| `ambient/brightness` | `0`–`100` | Brightness percent |
| `ambient/color` | integer | Raw colour code (HAL-specific) |

---

## Doors & Lights (BYD Cloud only)

| Topic suffix | Payload | Notes |
|---|---|---|
| `lock` | `LOCK` | **Cloud-required.** Locks all doors |
| `lock` | `UNLOCK` | **Cloud-required.** Unlocks all doors |
| `flash` | *(any payload)* | **Cloud-required.** Flashes headlights briefly |

These entities are only published to HA Discovery if BYD Cloud credentials are configured and enabled.

---

## Example

If your MQTT connection's topic is `overdrive/vehicle`, then to open the driver-side window to 50 %:

```
mosquitto_pub -h broker.example.com -t 'overdrive/vehicle/command/window/lf' -m '50'
```

To lock the car:

```
mosquitto_pub -h broker.example.com -t 'overdrive/vehicle/command/lock' -m 'LOCK'
```

---

## Implementation reference

Command routing lives in [`HaIntegration.buildCommandMap()`](app/src/main/java/com/overdrive/app/mqtt/HaIntegration.java). State is reported back on the base telemetry topic (the JSON blob already includes `window_*_pct`, `trunk_open`, `ac_on`, `ac_fan`, etc.); commands without read-back use HA's `optimistic: true` discovery flag.
