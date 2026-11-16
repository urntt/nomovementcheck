# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Each version carries the targeted Minecraft version as build metadata, for example `1.0.0+26.3`.

## [Unreleased]

## [2.0.0+26.3] - 2026-11-16

### Changed

- Set a player's state with `/nomovementcheck player <targets> default|true|false`. `default` replaces `reset` and makes the players follow the default again, matching the three states on the settings screen.

## [1.0.0+26.3] - 2026-11-15

### Added

- Remove the server's movement checks for the players the mod applies to: speed, deviation and collision checks for players and vehicles, the kicks for floating, for several positions per tick and for invalid movement packets, the setback for moving while asleep, and the reset of vehicles the player does not control.
- Apply single moves that are longer than vanilla would accept as teleports, so they cannot stall the server.
- Add the settings `enabled`, `default`, and per-player overrides. The mod is disabled by default and, once enabled, applies to every player unless set otherwise.
- Add the `/nomovementcheck` command, which requires permission level 4, to show and change the settings. Players are selected by name, including offline players, or by selector.
- Save the settings to `config/nomovementcheck.json` on the server.
- Add an optional client part: a key binding, unbound by default, that opens a settings screen for players with permission level 4.
- Add English and Simplified Chinese translations. Text sent by the server falls back to English on clients without the mod.
