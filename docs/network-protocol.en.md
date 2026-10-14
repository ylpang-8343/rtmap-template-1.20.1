# RTMap Network Protocol Draft (v1)

Status: draft. Target: Minecraft 1.20.1 / Fabric, using `fabric-networking-api-v1` (custom packets over `PacketByteBuf`).

Chinese version: [network-protocol.md](network-protocol.md)

## 1. Design principles

- **The client speaks first.** The client sends `client_hello` only after detecting that the server has registered this mod's channels. The server never sends packets to a player who has not completed the handshake, so vanilla clients and clients without this mod are unaffected.
- **The server is the sole authority.** Permissions are decided by the server. Capabilities declared by the client are used for negotiation only, never as authorization.
- **Extensible.** The integer protocol version is bumped only for incompatible changes. New functionality is declared with string IDs; both sides ignore unknown feature IDs.
- **Re-authorize every sensitive request.** Having permission at handshake time does not grant lasting access.
- **Safe by default.** The seed is never included in the handshake. It must be fetched with a separate request.

## 2. Channels

Namespace: `rtmap`.

| Channel | Direction | Purpose |
|---|---|---|
| `rtmap:client_hello` | C2S | Client initiates the handshake |
| `rtmap:server_hello` | S2C | Server replies with the negotiated result and permissions |
| `rtmap:permissions` | S2C | Notifies the client when permissions change (op changes, config changes) |
| `rtmap:seed_request` | C2S | Requests the world seed |
| `rtmap:seed_response` | S2C | Returns the seed, or a rejection reason |
| `rtmap:chunk_query` | C2S | Asks about a rectangle of chunks: loading state, counts, or both |
| `rtmap:chunk_data` | S2C | Returns that data, or a rejection reason |

Feature ids so far: `seed`, `chunk_state`, `chunk_load`. Later features (MSPT, real chunk timing, etc.) each get their own channel and are declared in `features`.

## 3. Handshake

```
Client                                   Server
  | JOIN event                             |
  |  canSend(client_hello)? no -> stop, degrade to "no server" mode
  |--- client_hello(protocol, features) -->|
  |                                        | negotiate version, compute grants
  |<-- server_hello(protocol, world_id, ---|
  |        features, grants, status)       |
  | store negotiated result                |
```

`client_hello`:

| Field | Type | Description |
|---|---|---|
| protocol | VarInt | Highest protocol version the client supports |
| min_protocol | VarInt | Lowest protocol version the client accepts |
| mod_version | String | For logging and debugging only; not used in decisions |
| features | List\<String\> | Feature IDs the client can use |

`server_hello`:

| Field | Type | Description |
|---|---|---|
| status | Byte | 0 = OK, 1 = incompatible version |
| protocol | VarInt | Negotiated version (= min of both highest versions) |
| world_id | UUID | Persistent world identifier kept by the server, used for client-side caching |
| features | List\<String\> | Intersection of both sides' features that the server has enabled |
| grants | List\<String\> | Feature IDs granted to this player (a subset of `features`) |

**Version negotiation:** `negotiated = min(client.protocol, server.protocol)`. If `negotiated < max(client.min_protocol, server.min_protocol)`, the server returns `status = 1`. The client then disables all network features and shows a one-time notice (chat or toast; no modal dialog, no kick).

**Degradation:** If any step fails (missing channel, incompatible version, malformed packet), the client stays in client-only mode. Local features such as the minimap, waypoints and measuring are unaffected. Nothing blocks on the handshake, so there is no timeout: until `server_hello` arrives the client simply has no server-provided data.

## 4. Seed and permissions

### 4.1 Authorization rule

```
allowed = isOp(player) || config.seed_sharing
```

- Singleplayer and the player's own LAN world: the client sees `MinecraftClient.getServer() != null` and reads the seed directly from the integrated server. **The network protocol is not used.**
- Multiplayer servers: the client uses `seed_request`.
- `isOp` is `player.hasPermissionLevel(2)`. If `fabric-permissions-api` is installed, the permission node `rtmap.seed` is also accepted (soft dependency; nothing breaks if it is absent).

### 4.2 `seed_request` / `seed_response`

`seed_request`: empty body.

`seed_response`:

| Field | Type | Description |
|---|---|---|
| status | Byte | 0 = OK, 1 = not authorized, 2 = feature disabled, 3 = rate limited |
| seed | Long | Present only when `status = 0` |

Server requirements:

1. Recompute `allowed` on every request.
2. Read the seed from the overworld with `getSeed()` (the world seed is shared by all dimensions).
3. Rate-limit per player, e.g. at most 3 requests per 30 seconds; beyond that return `status = 3`.
4. Write a log entry (player name, result) so administrators can audit requests.

### 4.3 Revocation and changes

`rtmap:permissions` (S2C): `grants` (same as in `server_hello`).

Triggers:
- `seed_sharing` or `chunk_data_sharing` is changed by a command or a config reload.
- A player's op status changes. Vanilla has no corresponding Fabric event, so a Mixin on `PlayerManager` (`addToOperators` / `removeFromOperators`) is needed.

On receipt, if `chunk_state` or `chunk_load` is no longer in `grants`, the client stops asking and hides the chunk layers. If `seed` is no longer in `grants`, the client **immediately stops using the seed and clears it from memory**, no longer uses it for slime chunk calculation, and deletes any server-provided seed from the on-disk cache.

> Limitation: this cannot stop a player from recording the seed themselves or modifying the client. Revocation only guarantees that this mod, behaving normally, stops using it. This must be stated in the documentation.

### 4.4 Client-side cache

- **A seed provided by a server is kept in memory only and is never written to disk.** It is only usable while the server still grants it, so a disk copy would add nothing except a way to outlive revocation. The client simply asks again on the next join (subject to the rate limit).
- `world_id` (the UUID sent by the server) identifies the world, not the server address. This keeps multiple worlds behind one address, port changes and domain changes from mixing up data.
- Manually entered seeds are stored on disk per world, next to that world's map (`seed.json`). If the server does not have this mod there is no `world_id`, so the world is identified by "server address + port".
- When authorization is revoked, **only the seed provided by the server is cleared**; manually entered seeds are kept.

## 5. Chunk loading and load data

Two features share one request / response pair: `chunk_state` (how each chunk is loaded and what holds it) and `chunk_load` (entity, block entity and hopper counts per chunk).

### 5.1 Authorization rule

```
allowed = isOp(player) || config.chunk_data_sharing
```

- Singleplayer and the player's own LAN world: the client reads the integrated server directly. **The network protocol is not used.**
- Multiplayer servers: both features are delivered in `grants` like the seed, under the same rule, and the rule is re-checked on every query. `chunk_data_sharing` defaults to `false`.

### 5.2 `chunk_query` (C2S)

Stateless: every query is answered on its own, and the server keeps nothing between queries except the rate-limit counter.

| Field | Type | Description |
|---|---|---|
| dimension | String (at most 128) | Must equal the player's current dimension id; this guards against a query that races a dimension change |
| min_chunk_x | Int | West edge of the rectangle, in chunks |
| min_chunk_z | Int | North edge of the rectangle, in chunks |
| width | VarInt | 1 to 256 |
| height | VarInt | 1 to 256; `width * height` is at most 65536 |
| what | Byte | Bit 0 = loading state (`chunk_state`), bit 1 = counts (`chunk_load`) |

### 5.3 `chunk_data` (S2C)

| Field | Type | Description |
|---|---|---|
| status | Byte | 0 = OK, 1 = not authorized, 2 = rate limited, 3 = bad request, 4 = wrong dimension |

When `status = 0`, a snapshot follows:

| Field | Type | Description |
|---|---|---|
| dimension | String | The dimension the data is for; the client drops it if that is no longer its dimension |
| min_chunk_x, min_chunk_z | Int, Int | As asked |
| width, height | VarInt, VarInt | As asked |
| have | Byte | Bit 0 = `state` present, bit 1 = `load` present |
| state | Byte array | `width * height` bytes, row by row from `min_chunk_z`, west to east. Present if bit 0 of `have` is set |
| load | see below | Present if bit 1 of `have` is set |

A state byte: bits 0 to 1 are the level (0 = not loaded, 1 = border, 2 = block ticking, 3 = entity ticking). Bits 2 to 7 are the kinds of ticket holding the chunk: player, portal, force-load, spawn area, ender dragon, other.

`load`: a VarInt count of non-empty chunks, then for each one a VarInt index gap (its index minus the previous index minus 1, starting from -1), then VarInt entities, VarInt block entities and VarInt hoppers. Counts are clamped to 65535.

Server requirements:

1. Read on the server thread only, and read-only. Two accessor mixins are used: `ThreadedAnvilChunkStorage.getChunkHolder` and `ChunkTicketManager.ticketsByPosition`.
2. Rate limit: 10 queries per 5 seconds per player. These two numbers are fixed in the code and are not settings yet.
3. Validate before reading anything: body size, `what` bits, size limits, coordinate range (at most 1,900,000 chunks from the origin), negotiated features, permission, and dimension.
4. A holder at the entity-ticking level whose chunk is not within a player's simulation distance (`ChunkTicketManager.shouldTickEntities` is false) is reported as block ticking.

Client behaviour: it asks for the visible area plus 3 chunks around it, at most every 1.5 seconds and never twice within 0.6 seconds, backs off for 5 seconds after a "rate limited" or "not authorized" answer, and discards an answer for another dimension. The client accepts a `chunk_data` packet of up to 1 MiB.

These two channels do not carry their own version byte yet, so an incompatible change to them needs a protocol version bump.

## 6. Server config and commands

`config/rtmap-server.json`:

```json
{
  "seed_sharing": false,
  "chunk_data_sharing": false,
  "command_permission_level": 3,
  "seed_request_limit": 3,
  "seed_request_window_seconds": 30
}
```

| Key | Default | Allowed range |
|---|---|---|
| `seed_sharing` | `false` | boolean |
| `chunk_data_sharing` | `false` | boolean |
| `command_permission_level` | 3 | 2 to 4 |
| `seed_request_limit` | 3 | 1 to 60 |
| `seed_request_window_seconds` | 30 | 10 to 3600 |

Commands (the required permission level is `command_permission_level`):

| Command | Effect |
|---|---|
| `/rtmap seed_sharing` | Shows the current value |
| `/rtmap seed_sharing <true\|false>` | Changes and saves the switch, and sends `permissions` to online players |
| `/rtmap chunk_data_sharing` | Shows the current value |
| `/rtmap chunk_data_sharing <true\|false>` | Changes and saves the switch, and sends `permissions` to online players |
| `/rtmap reload` | Reloads the config file, sends `permissions`, and refreshes players' command trees |

### Tamper resistance

Admins can tune these values, and these properties keep anyone else from changing them:

- **Server-only.** The values are read from the server's config directory. No packet reads or writes them, so a client cannot influence them.
- **Server-side state.** Rate-limit counters live in server memory, keyed by player UUID. Nothing the client sends affects them.
- **Hard limits.** Out-of-range values are clamped on load. In particular `command_permission_level` can never go below 2, so a bad config cannot let ordinary players run `/rtmap`.
- **Fail closed.** If the file cannot be parsed, the previous valid settings stay in effect (defaults on first load, where `seed_sharing` is off). Wrong-typed values fall back to their defaults.
- **Safe writes.** Saving writes a temporary file and then atomically replaces the real one, so a crash cannot leave a half-written file.

Not covered: anyone with access to the server's files or console is an administrator and can change the config. That is by design and cannot be prevented in code.

`world_id` is stored in a `PersistentState` of the overworld and generated randomly on first load. If a world save is copied to a new server, the `world_id` goes with it. To tell copies apart, an administrator can delete that state so a new one is generated.

## 7. General requirements

- **Packet size:** every packet body has a limit (e.g. 4 KB). Anything larger is dropped and logged. The one exception is `chunk_data`, which the client accepts up to 1 MiB, with every size inside it checked against the query limits.
- **Strings and lists:** limit lengths and element counts (e.g. at most 64 entries in `features`, each at most 64 characters) to prevent abuse.
- **Threading:** receive callbacks run on the network thread. Anything touching game state must hop to the main thread (`server.execute` / `client.execute`).
- **Do not trust the client:** the client's `features` is only a declaration. The server uses it to decide whether to send data, but authorization depends solely on the server's own permission checks.
- **Log levels:** INFO for handshake results, DEBUG for permission denials, WARN for protocol errors.

## 8. Adding future features

To add a feature (e.g. `mspt`):
1. Add a string ID to `features`, and add a switch and permission check in the server config.
2. Add a dedicated channel whose packets carry their own version byte, so changing only that feature does not require a protocol version bump.
3. Deliver the authorization in `grants`, and notify changes through the `permissions` channel.
4. On the client, the corresponding layer is hidden while the feature is not available (it is left out of the toolbar and the settings), and the full-screen map says why if the server refuses a query.

## 9. Decisions

- The command permission level is read from config (clamped to 2 to 4, default 3).
- The rate-limit parameters are configurable by administrators, within hard limits (see section 6).
- The Servux compatibility layer (structure data) lives outside this protocol as a separate implementation and does not use the `rtmap:` channels.

## 10. Not implemented yet

- Configurable chunk query limits (size and rate are fixed in the code), and a version byte for the chunk channels.
- A channel for real per-chunk timing and MSPT.
- Soft integration with `fabric-permissions-api` (node `rtmap.seed`).
- Packet-level automated tests. The `chunk_query` / `chunk_data` exchange on a dedicated server with permissions has not been tested yet.
