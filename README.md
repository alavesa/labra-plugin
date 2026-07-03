# Labra — lab equipment for Paper servers

Lab gear for facility/SCP-style servers: **hazard zones** (radiation ☢ / toxic ☠ / cryo ❄),
a **hazmat suit** that protects inside them, and a **geiger counter** that clicks faster the
closer you get to radiation. Companion to
[keycard-datapack](https://github.com/alavesa/keycard-datapack) and
[guns-plugin](https://github.com/alavesa/guns-plugin).

## Install

Drop `Labra-x.y.z.jar` into the server's `plugins/` folder and restart. Requires Paper (or
Spigot) 1.21.4+ and Java 21. No datapack needed.

## Quick start (2 minutes)

```
/lab zone add reaktori radiation 10     <- stand where the zone should be
/lab give geiger                        <- hold it and walk toward the zone
/lab give hazmat                        <- put on all 4 pieces, walk in safely
```

## Features

- **Hazard zones** — spheres placed where you stand (`/lab zone add <name> <type> <radius>`,
  radius 1–64). Unprotected players inside get:
  - `radiation`: wither + ☢ warning (and geiger counters react)
  - `toxic`: poison + ☠ warning
  - `cryo`: freezing (powder-snow effect) + slowness + ❄ warning
- **Hazmat suit** (`/lab give hazmat`) — 4-piece yellow suit; wearing ALL FOUR pieces makes
  every zone type harmless (calm green "protected" message instead).
- **Geiger counter** (`/lab give geiger`) — hold it in either hand: it ticks calmly when
  clear, clicks faster and faster as you approach a radiation zone (senses out to 2× the
  zone radius), and shows a µSv/h reading that goes green → yellow → red.

## Commands (`/lab`, alias `/labra`)

| Command | What it does | Permission |
|---|---|---|
| `/lab give hazmat\|geiger [player]` | get lab items | lab.give |
| `/lab zone add <name> <type> <radius>` | create a zone where you stand | lab.admin (op) |
| `/lab zone remove <name>` | delete a zone | lab.admin (op) |
| `/lab zone list` | list zones with locations | lab.admin (op) |
| `/lab reload` | reload lab.yml | lab.admin (op) |

Zones live in `plugins/Labra/lab.yml` — hand-editable, `/lab reload` applies.

## Resource pack models (optional)

Everything works without a pack (yellow leather armor + a clock). For custom models, the
items carry `custom_model_data` string ids, same system as the Guns pack:

| Item | Base item | model id |
|---|---|---|
| Hazmat hood | `leather_helmet` | `hazmat_helmet` |
| Hazmat suit | `leather_chestplate` | `hazmat_chestplate` |
| Hazmat pants | `leather_leggings` | `hazmat_leggings` |
| Hazmat boots | `leather_boots` | `hazmat_boots` |
| Geiger counter | `clock` | `lab_geiger` |

See the [Guns resource pack guide](https://github.com/alavesa/guns-plugin/blob/main/resource-pack/OHJEET.md)
(in Finnish) — the exact same steps apply, just with these ids.

## Building

```
mvn package    # requires JDK 21; jar lands in target/
```

## Ideas for later

Airlock/decontamination showers, radioactive item samples, lab terminals with research
notes, alarm sirens tied to zones. Open an issue or just ask.
