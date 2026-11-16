# nomovementcheck

A simple server-side Fabric mod for Minecraft: Java Edition that makes the server completely trust the client on movement and position.

For the players it applies to, the server accepts every position and movement the client reports. It never sets them back or kicks them because of how they moved. Players who are not covered keep the vanilla checks unchanged.

Clients do not need the mod. Installing it on a client as well adds a settings screen for server operators.

## Warning

A player this mod applies to can fly, pass through blocks, move at any speed, and teleport with a modified client, and the server will not object. Enable it only for players you trust.

## What it removes

For the players it applies to, the server no longer:

- sets back players or vehicles that moved too quickly;
- sets back players or vehicles whose reported position differs from the server's own simulation ("moved wrongly"), or that moved into blocks;
- kicks players for floating, on foot or in a vehicle;
- kicks players for sending more than one position per tick;
- kicks players for sending positions that are not numbers, or teleport confirmations it was not waiting for. These packets are ignored instead;
- sets back players who move while asleep. The server adopts their position;
- resets the vehicle of a passenger who moves it without controlling it. The server moves the vehicle.

A single move that is longer than vanilla would ever accept is applied as a teleport: blocks along the way, such as pressure plates, do not take effect.

These parts of vanilla stay as they are for everyone:

- The coordinate range. Positions beyond X/Z ±30,000,000 or Y ±20,000,000 are clamped to it.
- Teleports started by the server, such as `/tp`, ender pearls, portals, and respawning. The server ignores movement until the client has confirmed the teleport.
- Everything that is not movement, such as reach and interaction checks.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api) on the server.
2. Download the jar from this repository's [Releases](https://github.com/urntt/nomovementcheck/releases) page. Each release supports a single Minecraft version, shown after the `+` in its version number. For example, `1.0.0+26.3` is for Minecraft 26.3.
3. Put the jar into the server's `mods` folder.

To use the settings screen, also put the jar and Fabric API into the `mods` folder of your client. The mod works the same way in singleplayer and on LAN worlds.

## Usage

The mod does nothing until an operator enables it. Once enabled, it applies to every player by default.

Whether the mod applies to a player is decided by three settings:

| Setting | Meaning | Default |
| --- | --- | --- |
| `enabled` | Turns the whole mod on or off. While it is off, every player keeps the vanilla checks. | `false` |
| `default` | Whether the mod applies to players who have no setting of their own. | `true` |
| Player settings | Each player is `default` (follows `default` above), `true`, or `false`. `true` and `false` override the default for that player. | `default` |

For example, to trust only a few players, set `default` to `false` and set those players to `true`. To trust everyone except a few, leave `default` at `true` and set those players to `false`.

### Command

The command requires permission level 4 (server owners and the server console).

| Command | Effect |
| --- | --- |
| `/nomovementcheck` | Shows the current settings. |
| `/nomovementcheck enabled [true\|false]` | Shows or sets `enabled`. |
| `/nomovementcheck default [true\|false]` | Shows or sets `default`. |
| `/nomovementcheck player <targets>` | Shows what applies to the players. |
| `/nomovementcheck player <targets> default` | Makes the players follow `default`, removing their own setting. |
| `/nomovementcheck player <targets> true\|false` | Gives the players their own setting. |
| `/nomovementcheck player list` | Lists the players that have their own setting. |
| `/nomovementcheck player clear` | Removes every player's own setting. |

`<targets>` accepts player names, including players who are offline, and selectors such as `@a`.

### Settings screen

On a client that has the mod, bind the **Open Settings** key under **nomovementcheck** in **Options → Controls → Key Binds**. It is unbound by default.

Pressing the key opens the settings screen if you have permission level 4 on the server. The screen changes the same settings as the command, shows every online player, and lets you add offline players by name. Without the permission, or on a server that does not run the mod, you get a message instead.

### Configuration file

The settings are saved to `config/nomovementcheck.json` on the server whenever they change:

```json
{
  "enabled": true,
  "default": false,
  "players": [
    {
      "uuid": "a8edbef7-b3bd-46a5-89c2-9da6c276ca72",
      "name": "urntt",
      "value": true
    }
  ]
}
```

The file is read when the server starts. Players are identified by `uuid`; `name` is only used for display.

## Development

Building requires the JDK version set by `java_version` in `gradle.properties`.

Build the mod:

```bash
./gradlew build
```

The jar is written to `build/libs/`.

Run the client game tests:

```bash
./gradlew runClientGameTest
```

They start Minecraft together with a dedicated server and connect to it. For each movement check, the client cheats the way a modified client would, and the tests verify that the server reacts as vanilla does while the mod does not apply to the player and accepts the movement while it does. They also cover the command, the permission level, the key binding, the settings screen, and the saved configuration.

The game tests need a display. On a headless Linux machine, run them under Xvfb. Xvfb offers no sRGB-capable OpenGL visuals, so install Mesa's Vulkan driver (`mesa-vulkan-drivers` on Ubuntu) for the game to fall back to:

```bash
xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew runClientGameTest
```

Screenshots taken by the tests are saved to `build/run/clientGameTest/screenshots/`.

## License

[MIT](LICENSE)
