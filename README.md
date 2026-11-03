# ExyliaLink

The Minecraft side of [Exylia Link](https://link.exylia.net): links Minecraft accounts to Discord
accounts for a whole network, reports who is online (and in which LuckPerms groups) and runs the
rewards Link grants. Private for now.

Paper 1.21.1+ and Folia 1.21.4+. Requires [ExyliaLib](https://github.com/DiGround-s/ExyliaLib).
Optional: LuckPerms (groups), PlaceholderAPI (placeholders), DiscordSRV (import).

The contract with the gateway is Exylia-Link's `docs/protocol.md` (protocol 1).

## Setup

1. Put `ExyliaLink.jar` (and ExyliaLib) in `plugins/` on every server of the network and point
   them all at the same database in `plugins/ExyliaLink/database.yml` (the network secret and
   the reward grants live there, shared by every server).
2. Give each server a stable id: `server-id` in `config.yml` (empty uses ExyliaLib's `server-id`).
3. On any one server run `/linkadmin connect`, open the link it shows, sign in with Discord, pick
   the network and approve. Every server picks the link up within 30 seconds, registers and
   connects.
4. Optional: `/linkadmin import discordsrv` brings over existing DiscordSRV links.

Without a network the plugin stays idle and says "not linked" in the console; nothing else breaks.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/link` | `exylialink.link` (everyone) | A 6-character code to run as `/link <code>` in Discord or enter on the network's link page (click to copy). |
| `/link <code>` | `exylialink.link` | Redeems a code issued by `/link` in Discord. |
| `/link status` | `exylialink.link` | Whether the account is linked, and to whom. |
| `/unlink` | `exylialink.link` | Unlinks after a confirmation (the network may disable this). |
| `/linkadmin connect` | `exylialink.admin` (op) | Links the network (device code + approval URL). Running it again moves the network to a new secret. |
| `/linkadmin status` | `exylialink.admin` | Connection, network, server id, latency, rewards in progress, last error. |
| `/linkadmin disconnect` | `exylialink.admin` | Forgets the network secret on every server. |
| `/linkadmin unlink <player>` | `exylialink.admin` | Unlinks someone else (named as the actor in Link's activity log). |
| `/linkadmin import discordsrv` | `exylialink.admin` | Imports DiscordSRV links: through its API when it runs here, else `plugins/DiscordSRV/accounts.aof`, else the SQL table its config names. Batches of 1 000, progress and a final summary with conflicts. Safe to run again. |
| `/linkadmin reload` | `exylialink.admin` | Reloads `config.yml` and the messages. |
| `/linkadmin settings` | `exylialink.admin` | Edits `config.yml` in game. |

## Placeholders

From the online player's cached status (never a network call); identifier `exylialink`.

| Placeholder | Value |
|---|---|
| `%exylialink_linked%` | `true` / `false` |
| `%exylialink_discord_name%` | Discord username, empty when not linked or unknown |
| `%exylialink_discord_id%` | Discord id, empty when not linked |

## API

```java
ExyliaLinkApi link = ExyliaLinkApi.get();   // null while ExyliaLink is not enabled
if (link != null) {
    link.discordId(player.getUniqueId()).ifPresent(id -> ...);
}
```

`isLinked`, `discordId`, `discordName` (online players of this server) and `isConnected`.
Registered in Bukkit's ServicesManager; add `ExyliaLink` to your `softdepend`.

## Rewards

Link sends `reward.deliver` frames; the plugin runs the commands as console on the global thread,
replacing `{player}` with the player's current name, records the grant id in
`exylialink_grants` (shared, kept 90 days) and acknowledges. A grant already recorded is
acknowledged again without running, so a resend never pays twice. A grant that needs the player
online is refused with `player_offline` and comes back on their next join.

## Files

- `config.yml`: language, `server-id`, `gateway-url`.
- `lang/<code>/messages.yml`: every message (English and Spanish shipped).
- `database.yml`: where the shared tables live (`exylialink_network`, `exylialink_grants`).

## Building

`./gradlew build` builds against the newest ExyliaLib and runs the tests; the jar is
`build/libs/ExyliaLink-<version>.jar`.
