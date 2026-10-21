# RTMap

A map mod for technical Minecraft players (people who build farms, machines and redstone). Full-screen map, minimap, and a set of tools for slime chunks, spawn ranges, portals, chunk loading and server load, in one Fabric mod.

[中文说明](README.zh-CN.md)

> **Status: early development.** One person is working on it. There is no release on Modrinth or CurseForge yet, and it has only been tested by its author, mostly in singleplayer. Targets Minecraft 1.20.1 first; newer versions come later.

## What it does

**Map**
- A full-screen map (press `M`) and a minimap. The map draws itself as you explore, with vanilla map colours, and is saved per world and per dimension, so worlds and servers never mix.
- Zoom from very close down to 0.01x without the map turning to mush. Overview levels are built when tiles are saved, so zooming far out stays cheap.
- Minimap: square, top right, north up by default; corner, size, rotation and coordinates can be changed.

**Layers you can switch on the map**
- Slime chunks, mob spawn ranges (24 and 128 blocks), nether portal link areas, waypoints, and the player.
- Chunk loading: which chunks are loaded and how (entity ticking, block ticking, border), and what holds them (force-load, portal, spawn area, player).
- Chunk load heat map: entities, block entities and hoppers per chunk, weighted by how much of the chunk is really running. It is a count-based estimate, not a measured time.
- Chunk time: start a 5 to 60 second profiling run and see how much server time each chunk used, split into entities, block entities, scheduled ticks, random ticks, mob spawning and block events.
- A measure tool: ruler, rectangle and circle, with lengths, areas, chunk counts and region files.

**Dimensions and layers**
- The Nether has terrain: the map shows the floor of the highest open space, as if the roof were taken off, and lava is bright orange. A switch shows the roof and what is built on it instead.
- A strip at the left chooses which 16 block layer of a dimension to look at, in the Overworld, the Nether and the End. The map can also follow you: the layer you are in underground, the surface above ground.
- Underground layers draw **caves only** (open space and fluids). Solid rock is left blank on purpose, so the map cannot be used as an X-ray.

**Waypoints**
- `B` creates one, `N` opens the manager. Groups (switched on and off as a whole), death waypoints that remove themselves when you come back, beams and name plates in the world, and distance labels.
- Share one in chat (you decide whether to send it; received ones are never added automatically), export and import files, and teleport with a configurable command.
- Coordinates are converted between the Overworld and the Nether (the cursor, the minimap and other-dimension waypoints).

**Server information (optional server part)**
- Server MSPT and TPS in the status line, the world seed for slime chunks, chunk loading and load data, and chunk timing.
- Ops get these automatically. Everyone else only if the server admin switches it on. See [Playing on a server](#playing-on-a-server).

## Requirements

- Minecraft 1.20.1
- Fabric Loader 0.19.5 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api) for 1.20.1 (developed against 0.92.12)
- Java 17 or newer

The same jar goes on the client and on a dedicated server. The server part is optional: without it every client-side feature still works, and the ones that need server information are simply not offered.

## Installing

There is no release yet. To try it, build it yourself (see [Building](#building)) and put `build/libs/rtmap-1.0.0.jar` in your `mods` folder, together with Fabric API.

## Controls

| Key | Action |
|---|---|
| `M` | Open or close the full-screen map |
| `B` | New waypoint (at the cursor on the map, otherwise where you are) |
| `N` | Waypoint manager |
| `=` / `-` | Minimap zoom in / out |
| Unbound | Show or hide the minimap |

All of them can be changed in Controls, under "RTMap".

On the full-screen map: drag to pan, scroll to zoom around the cursor, double-click empty map to create a waypoint, click a waypoint to edit it, right-click for a menu (new waypoint, copy coordinates, teleport, and for a waypoint: edit, share, delete). The toolbar along the top holds the layer switches, the measure tool, zoom, the waypoint manager and the settings; hover a button for its name. Settings can also be searched.

## Playing on a server

Client-side features work on any server, with or without the server part. What needs the server part:

| What | Who gets it |
|---|---|
| World seed (slime chunks) | Ops; others only if `seed_sharing` is on. In singleplayer and your own LAN world the seed is read directly. A seed can also be typed in as a fallback |
| Chunk loading, load heat map, MSPT | Ops; others only if `chunk_data_sharing` is on |
| Chunk timing | Ops only, and only if `chunk_profiling` is on (it is by default) |

Server settings live in `config/rtmap-server.json`. A client can never read or change them.

| Key | Default | Meaning |
|---|---|---|
| `seed_sharing` | `false` | Non-ops may get the seed |
| `chunk_data_sharing` | `false` | Non-ops may see chunk loading, load data and MSPT |
| `chunk_profiling` | `true` | Ops may start a chunk timing run |
| `command_permission_level` | `3` | Permission level needed for `/rtmap` (2 to 4) |
| `seed_request_limit`, `seed_request_window_seconds` | `3`, `30` | Seed request rate limit |

Commands (need the permission level above): `/rtmap seed_sharing [true|false]`, `/rtmap chunk_data_sharing [true|false]`, `/rtmap chunk_profiling [true|false]`, `/rtmap reload`.

A seed received from a server is only kept in memory and is never written to disk. The network protocol is described in [docs/network-protocol.en.md](docs/network-protocol.en.md).

## Files

| Where | What |
|---|---|
| `config/rtmap-client.json` | Your settings: minimap, layers, waypoint display, teleport command, and so on |
| `config/rtmap-server.json` | Server settings (see above) |
| `rtmap/maps/<world>/<dimension>/` | Saved map tiles. Other views of a dimension (the roof, each layer) are in `layer-*` folders inside it |
| `rtmap/maps/<world>/` | `waypoints.json`, `portals.json`, and `seed.json` if you typed a seed |
| `rtmap/exports`, `rtmap/imports` | Waypoint files you export or import |

Files that cannot be read are left as they are and never overwritten.

## Building

```
./gradlew build
```

The mod is compiled for Java 17. Gradle itself (Fabric Loom 1.18) has to run on a newer JDK; we use 25. If your default Java is older, point Gradle at a newer one with `org.gradle.java.home` in `~/.gradle/gradle.properties`. `./gradlew runClient` starts a development client.

## More documentation

- [Project proposal](docs/RTMap-Proposal.en.docx): what exists, what is planned, how it works, and the questions we would like feedback on. The Chinese edition is [docs/RTMap-Proposal.docx](docs/RTMap-Proposal.docx).
- [Network protocol](docs/network-protocol.en.md)

## Relation to other mods

RTMap takes ideas from Xaero's Minimap and World Map, JourneyMap and MiniHUD, and from public descriptions of how chunk loading and server load can be shown. It is not affiliated with any of them, and it contains none of their code, assets or file formats. Compatibility with other mods has not been tested yet.

## License

Not decided yet. For now the repository still carries the CC0 text from the project template (see [LICENSE](LICENSE)).
