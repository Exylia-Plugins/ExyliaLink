# ExyliaLink

The Minecraft side of [Exylia Link](https://link.exylia.net): links Minecraft accounts to Discord
accounts for a whole network, reports who is online (and in which LuckPerms groups) and runs the
rewards Link grants. Private for now.

One jar for every platform, Java 17+:

| Platform | Role | Reads |
|---|---|---|
| Paper, Spigot 1.17+ and Folia | game server (`kind: backend`) | `plugin.yml` |
| Velocity 3 | proxy (`kind: proxy`) | `velocity-plugin.json` |
| BungeeCord, Waterfall | proxy (`kind: proxy`) | `bungee.yml` |

Optional: LuckPerms (groups, on servers and proxies), PlaceholderAPI and DiscordSRV (game servers).
A network may run it on its proxy only, its game servers only, or both.

The contract with the gateway is Exylia-Link's `docs/protocol.md` (protocol 1, including the
additive proxy fields: `hello.kind`, `server` in presence, `player.server`, `network.state`).

**No ExyliaLib.** ExyliaLink deliberately does not depend on ExyliaLib, for the same reason as
ExyliaAnalytics: it must run on proxies, on Spigot and on servers older than ExyliaLib supports.
Its few plumbing pieces (scheduler, config and messages, the sealed secret file, the grant journal)
are small classes in `common`. A Paper server that has ExyliaLib needs nothing extra.

**No data migration.** The previous 1.0.0 kept the network secret and the executed grant ids in
ExyliaLib's database. That build was never deployed to any server, so nothing is migrated: the
secret now lives in `network.dat` and the grants in `grants.log`, both in the plugin folder.

## Setup

1. Put `ExyliaLink-<version>.jar` in `plugins/` on the proxy and/or every game server.
2. Give each one a stable, unique id: `server-id` in `config.yml` (left empty, a random one is
   written there on the first start).
3. On each server and proxy run `/linkadmin connect`, open the link it shows, sign in with Discord,
   pick the network and approve. Every install keeps its own secret; linking one never disconnects
   the others.
4. Optional, on the game server that has DiscordSRV: `/linkadmin import discordsrv`.

Without a network the plugin stays idle and logs "Not connected to Exylia Link"; nothing else breaks.

## Proxies and game servers together

While a proxy of the network is connected, the gateway tells every connection (`network.state`)
and the game servers stay silent toward players: no link or unlink messages, no requirement
warnings or reminders, no command blocking; they still report presence and groups, run rewards and
answer `/link` if a player reaches it. The proxy warns, reminds and blocks restricted commands in
its command event, before they are forwarded. Without a proxy (or while the state is unknown) game
servers behave as they always did. The proxy reports the backend each player is on (shown on the
dashboard as "on survival via velocity").

Rewards have a target on the dashboard: game servers (console commands there) or the proxy (proxy
console commands such as `lpv ...`). The plugin runs whatever the gateway routes to it.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/link` | `exylialink.link` (everyone) | A 6-character code to run as `/link <code>` in Discord or enter on the network's link page (click to copy). |
| `/link <code>` | `exylialink.link` | Redeems a code issued by `/link` in Discord. |
| `/link status` | `exylialink.link` | Whether the account is linked, and to whom. |
| `/unlink` | `exylialink.link` | Asks first; `/unlink confirm` (or the button) within 30 s unlinks. The network may disable it. |
| `/linkadmin connect` | `exylialink.admin` (op) | Links this server or proxy (device code + approval URL). |
| `/linkadmin status` | `exylialink.admin` | Connection, role, network, server id, latency, rewards in progress, last error. |
| `/linkadmin disconnect` | `exylialink.admin` | Asks first; `/linkadmin disconnect confirm` deletes this install's secret. |
| `/linkadmin unlink <player>` | `exylialink.admin` | Unlinks someone else (named as the actor in Link's activity log). Online players, a UUID, or (game servers) anyone who played there. |
| `/linkadmin import discordsrv` | `exylialink.admin` | Game servers: imports DiscordSRV links through its API when it runs here, else `plugins/DiscordSRV/accounts.aof`, else the SQL table its config names. Batches of 1 000, progress and a final summary with conflicts. Safe to run again. |
| `/linkadmin reload` | `exylialink.admin` | Reloads `config.yml` and the messages. |

On proxies `exylialink.link` is granted to everyone unless a permission plugin denies it
explicitly; `exylialink.admin` and `exylialink.requirements.bypass` need a grant (LuckPerms).

## Placeholders

PlaceholderAPI on game servers, from the online player's cached status (never a network call).

| Placeholder | Value |
|---|---|
| `%exylialink_linked%` | `true` / `false` |
| `%exylialink_discord_name%` | Discord username, empty when not linked or unknown |
| `%exylialink_discord_id%` | Discord id, empty when not linked |

## API

```java
ExyliaLinkApi link = ExyliaLinkApi.get();   // net.exylia.link.api; null while ExyliaLink is not enabled
if (link != null) {
    link.discordId(player.getUniqueId()).ifPresent(id -> ...);
}
```

`isLinked`, `discordId`, `discordName` (players online here) and `isConnected`, on every platform.
On Bukkit it is also registered in the ServicesManager. Add ExyliaLink as a soft dependency.

## Rewards

Link sends `reward.deliver` frames; the plugin first looks the grant id up in `grants.log` and acks
a recorded grant `DELIVERED` without running it. Otherwise it runs the commands as console (main
thread, Folia's global region, or the proxy console), replacing `{player}` with the player's current
name, appends the grant id to `grants.log` (fsync before the ack; compacted on start and daily, kept
90 days; a line torn by a crash is dropped) and acknowledges. A grant that needs the player online
is refused with `player_offline` and comes back on their next join. The gateway resends an
unacknowledged grant only to the install it was sent to, so the per-install journal is enough.

## Requirements

Groups that must have a linked account are set on the web (Settings › Requirements) and reach
every connection in `welcome` and `requirements.updated`. A player in one of those LuckPerms groups
whose link status says unlinked gets a warning with a clickable `/link` (after `link.status` on
join, and again on a group change or an unlink), then a reminder every
`requirements-reminder-minutes` (default 10, 0 for none) until they link. In restrict mode the
listed command roots, plain or `plugin:root`, are refused until then; `/link` never is.
`exylialink.requirements.bypass` (not granted by default) skips all of it. While the gateway is
unreachable the plugin keeps the last requirements and statuses it knew and blocks nothing. Who
enforces them follows [Proxies and game servers together](#proxies-and-game-servers-together).

## Files

- `config.yml`: `language` (en, es), `server-id`, `gateway-url`, `requirements-reminder-minutes`, `debug`.
- `lang/<code>/messages.yml`: every message, copied once and never overwritten; a key missing
  from the file reads from the jar. Colours are `<#rrggbb>` (the Exylia palette by default),
  `&` codes or palette names such as `{primary}`; `<click:…>` and `<hover:show_text:'…'>` work
  on every platform; a line may start with `[sound:<key>|<volume>|<pitch>]` (game servers) and `[center]`.
- `network.dat`: this install's network secret, sealed with AES-GCM, owner-only. Never share it.
- `grants.log`: reward grants already run here.

## Modules

| Module | Holds |
|---|---|
| `common` | Everything platform-independent: protocol frames, the gateway connection (`java.net.http`), backoff, requests, `network.dat`, `grants.log`, requirement decisions, the DiscordSRV `accounts.aof` parser, config, messages and the text format. Tested without a server. |
| `paper` | `LinkPaper`: Paper, Spigot 1.17+ and Folia. Compiled against paper-api 1.17.1 and checked against spigot-api 1.17.1; Folia's schedulers are reached reflectively. |
| `velocity` | `LinkVelocity`: Velocity 3, Adventure text. |
| `bungee` | `LinkBungee`: BungeeCord and Waterfall, BungeeCord chat components. |

## Building

`./gradlew build` runs the tests and builds `ExyliaLink-<version>.jar` (into `build/libs`, or
`<exyliaOutputDir>/plugins` when that property is set). The same file goes into a server or a proxy.
