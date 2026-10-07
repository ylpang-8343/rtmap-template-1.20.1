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

Later features (chunk loading state, entity density, MSPT, etc.) each get their own channel and are declared in `features`.

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

**Degradation:** If any step fails (missing channel, timeout, incompatible version), the client falls back to client-only mode. Local features such as the minimap, waypoints and measuring are unaffected. The handshake timeout is 5 seconds and is only logged.

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
- `seed_sharing` is changed by a command or a config reload.
- A player's op status changes. Vanilla has no corresponding Fabric event, so a Mixin on `PlayerManager` (`addToOperators` / `removeFromOperators`) is needed.

On receipt, if `seed` is no longer in `grants`, the client **immediately stops using the seed and clears it from memory**, no longer uses it for slime chunk calculation, and deletes any server-provided seed from the on-disk cache.

> Limitation: this cannot stop a player from recording the seed themselves or modifying the client. Revocation only guarantees that this mod, behaving normally, stops using it. This must be stated in the documentation.

### 4.4 Client-side cache

- Cache key: `world_id` (the UUID sent by the server), not the server address. This keeps multiple worlds behind one address, port changes and domain changes from mixing up seeds.
- Location: `config/rtmap/seeds.json` (or a data file under the game directory).
- Manually entered seeds are also stored per `world_id`. If the server does not have this mod, there is no `world_id`, so the key falls back to "server address + port".
- When authorization is revoked, **only seeds provided by the server are deleted**; manually entered seeds are kept.

## 5. Server config and commands

`config/rtmap-server.json`:

```json
{
  "seed_sharing": false,
  "seed_request_limit_per_30s": 3
}
```

Commands (permission level 3, configurable):

| Command | Effect |
|---|---|
| `/rtmap seed_sharing <true\|false>` | Changes and saves the switch, and sends `permissions` to online players |
| `/rtmap reload` | Reloads the config file, also sending `permissions` |

`world_id` is stored in a `PersistentState` of the overworld and generated randomly on first load. If a world save is copied to a new server, the `world_id` goes with it. To tell copies apart, an administrator can delete that state so a new one is generated.

## 6. General requirements

- **Packet size:** every packet body has a limit (e.g. 4 KB). Anything larger is dropped and logged. Future channels that carry chunk data need their own limits.
- **Strings and lists:** limit lengths and element counts (e.g. at most 64 entries in `features`, each at most 64 characters) to prevent abuse.
- **Threading:** receive callbacks run on the network thread. Anything touching game state must hop to the main thread (`server.execute` / `client.execute`).
- **Do not trust the client:** the client's `features` is only a declaration. The server uses it to decide whether to send data, but authorization depends solely on the server's own permission checks.
- **Log levels:** INFO for handshake results, DEBUG for permission denials, WARN for protocol errors.

## 7. Adding future features

To add a feature (e.g. `chunk_state`):
1. Add a string ID to `features`, and add a switch and permission check in the server config.
2. Add a dedicated channel whose packets carry their own version byte, so changing only that feature does not require a protocol version bump.
3. Deliver the authorization in `grants`, and notify changes through the `permissions` channel.
4. On the client, the corresponding layer is not selectable without authorization; the UI shows it greyed out with the reason.

## 8. Open questions

- Is the command permission level fixed at 3, or read from config?
- Should the rate-limit parameters be exposed to administrators?
- The Servux compatibility layer (structure data) lives outside this protocol as a separate implementation and does not use the `rtmap:` channels.
