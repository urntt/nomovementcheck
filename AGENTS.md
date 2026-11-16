# AGENTS.md

Instructions for AI coding agents working in this repository. `CLAUDE.md` imports this file, so keep all agent guidance here.

## Project

`nomovementcheck` is a server-side [Fabric](https://fabricmc.net/) mod for Minecraft: Java Edition that removes the server's movement checks for selected players. For those players the server fully trusts the position and movement the client reports: it never kicks them or sets them back because of how they moved. Installing the mod on the client as well is optional and adds a settings screen for operators.

## Project decisions

These decisions are settled. Do not deviate from them without the user's explicit approval.

### Minecraft and toolchain

- Development started on Minecraft 26.3 with Java 25. `gradle.properties` is the single source of truth for the mod, Minecraft, Fabric Loader, Loom, Fabric API, and Java versions. `build.gradle`, `fabric.mod.json`, the mixin config, and the CI workflows read them from there; do not restate them elsewhere.
- Follow the latest official Fabric template ([FabricMC/fabric-example-mod](https://github.com/FabricMC/fabric-example-mod), also available from the [template generator](https://fabricmc.net/develop/template/)): the `net.fabricmc.fabric-loom` Gradle plugin, Mojang's official names with no `mappings` dependency, and `implementation` (not `modImplementation`) for dependencies. Do not use Yarn.
- Pin `loom_version` to a release version instead of the template's `-SNAPSHOT`, so builds are reproducible.
- Target only the latest stable (release) Minecraft version. Updates, fixes, and new features are always developed against it. Snapshots, pre-releases, and release candidates are not supported targets.
- Do not maintain older Minecraft versions and do not set up multi-version builds (no per-version branches, no Stonecutter or other preprocessors). When a new stable version is released, port the mod to it and drop the previous one.

### Identity

| Item | Value |
| --- | --- |
| Mod ID | `nomovementcheck` |
| Display name | `nomovementcheck` |
| Maven group | `com.urntt` |
| Base package | `com.urntt.nomovementcheck` |
| Version format | `<SemVer>+<Minecraft version>`, for example `1.0.0+26.3` |
| License | MIT |

The mod version itself follows [Semantic Versioning](https://semver.org/); the `+<Minecraft version>` suffix is build metadata naming the Minecraft version the build targets. Version numbers start at `1.0.0`.

The name is always written `nomovementcheck`, in one lowercase word: the mod ID, the display name, the command, the config file, the asset namespace, the mixin config, the Gradle project, the jar, titles in documentation and in the game, and the repository links (`github.com/urntt/nomovementcheck`). Do not introduce hyphenated or spaced spellings.

### Distribution

- Releases are published only as GitHub Releases. Do not publish to Modrinth, CurseForge, or any other mod platform, and do not add publishing tooling for them.
- `README.md` must clearly warn that an exempt player's client can fly, pass through blocks, and move at any speed without the server objecting, so the mod should only be enabled for players the server owner trusts.

### Scope and behavior

- Server-side with an optional client part: `fabric.mod.json` declares `"environment": "*"`. The mod must work on a dedicated server with unmodified clients, and also in singleplayer and on LAN worlds. Only the `main` and `client` entrypoints are used.
- A player is *exempt* when `enabled && (the player has an override ? that override : default)`. Only exempt players are affected; everyone else keeps vanilla behavior exactly.
- For exempt players the mod removes every check in `ServerGamePacketListenerImpl` that rejects, corrects, or punishes client-reported movement:
  - The speed checks for players and vehicles ("moved too quickly").
  - The deviation and collision checks for players and vehicles ("moved wrongly").
  - The floating kicks for players and vehicles.
  - The kick for sending a second position packet within one client tick.
  - The kicks for non-finite coordinates and unexpected teleport confirmations. Such a packet is dropped instead, because it cannot be applied.
  - The setback for moving while asleep. The server adopts the reported position. Vanilla announces the bed position with a regular teleport when a player falls asleep, so the server only sees positions the client sent after it knew about the bed.
  - The vehicle reset for a passenger that does not control its vehicle. The server moves the vehicle to the reported position.
- A single packet that moves an exempt player or vehicle further than the vanilla speed limit is applied as a teleport: the server skips the path simulation (`move`), whose collision sweep covers the whole displacement and would stall the server for long distances. Movement within the vanilla limit is simulated exactly as in vanilla.
- These stay vanilla for everyone: the coordinate range (X/Z ±30,000,000 and Y ±20,000,000; values beyond are clamped, not punished), ignoring movement until a server-initiated teleport is confirmed, ignoring movement until the client has finished loading, and ignoring a passenger's own position packets.
- Only movement validation is in scope. Reach checks, interaction checks, and other anti-cheat topics must not be added here.
- Defaults on a fresh installation: `enabled` is `false`, `default` is `true`, and there are no player overrides. Installing the mod changes nothing until an operator enables it.
- Settings are saved to `config/nomovementcheck.json` on the server, keyed by player UUID with the name stored for display. Every change is written immediately. The file is read when the server starts.
- The `/nomovementcheck` command requires permission level 4 (owners):
  - `/nomovementcheck` shows the current state.
  - `/nomovementcheck enabled [true|false]` and `/nomovementcheck default [true|false]` query or set the two switches.
  - `/nomovementcheck player <targets> [default|true|false]` queries a player's state or sets it to one of three values: `default` follows the default and removes the override, `true` and `false` set an override. `<targets>` is a game profile argument: player names, including offline players, and selectors such as `@a`.
  - `/nomovementcheck player list` and `/nomovementcheck player clear` list or remove all overrides.
- Client part: a key binding, unbound by default, asks the server for the settings. The server answers only if the player has permission level 4, and the client then opens the settings screen. Every change made on the screen is sent to the server, which checks the permission again. Without the mod on the server, or without permission, the player only gets an action bar message. Whenever the settings change, the server sends the new state to every online player who has the mod and the permission.
- The mod has no client-side settings and no client config file.

### Localization

- All user-facing text, including command feedback, key binding names, the key binding category, action bar messages, and the settings screen, uses translation keys. Never hard-code display strings.
- Text the server sends must be readable on clients without the mod. Build it with `Component.translatableWithFallback`, taking the fallback from the bundled `en_us.json`, so the English text has a single source.
- Provide translations for `en_us` and `zh_cn`, and keep both complete whenever a translation key is added or changed.

### Dependencies

- Required: Fabric Loader and Fabric API.
- Configuration is hand-written without a config library: a JSON file in the Fabric config directory, serialized with Gson (bundled with Minecraft). The settings screen uses vanilla widgets.
- There is no Mod Menu integration: the settings belong to the server, and the key binding is the way to reach them.
- Do not add other dependencies without the user's explicit approval.

### Implementation

- Language: Java only.
- Source sets: `src/main` holds everything the server needs (the mod initializer, configuration, command, networking, mixins, and the language files). `src/client` holds the key binding and the settings screen. The client game tests live in `src/gametest`.
- The command and the network handlers are thin adapters. Both change settings only through the configuration class.
- Mixins: prefer the MixinExtras injectors bundled with Fabric Loader (for example `@ModifyExpressionValue` and `@WrapOperation`) over `@Redirect` and `@Overwrite`, to stay compatible with other mods and keep porting work small.

### Testing

- The client game tests in `src/gametest` start Minecraft, connect to a dedicated server running in the same process, and check that each removed movement check stops affecting exempt players while still affecting everyone else. They also cover the command, the permission rules, the key binding, the settings screen, and the saved configuration. Keep them passing and extend them when behavior changes.
- After porting to a new Minecraft version, run the client game tests. A successful build does not prove that the mixins still have the intended effect.
- `README.md` describes how to run them, including on a headless machine.

### CI, releases, and changelog

- GitHub Actions (`.github/workflows/build.yml`) builds the project and runs the client game tests on every push and pull request.
- Maintain `CHANGELOG.md` following [Keep a Changelog](https://keepachangelog.com/). Record every user-visible change under `Unreleased` in the same change that introduces it.
- `.github/workflows/release.yml` builds the mod and publishes a GitHub Release for the project version, with the jar attached and the matching `CHANGELOG.md` section as release notes. It runs when a tag `v<version>` (for example `v1.0.0+26.3`) is pushed, or when started manually on a branch, in which case it creates that tag on the branch's latest commit. It fails if a pushed tag does not match the project version, if the changelog has no section for the version, or if the release already exists.
- Release only when the user asks. To release, set `mod_version` in `gradle.properties`, rename `Unreleased` in `CHANGELOG.md` to `[<version>] - <YYYY-MM-DD>` above a new empty `Unreleased` section, commit, and push. Then start the release workflow on `main`. Claude Code cloud sessions cannot push tags, so start the workflow through the GitHub Actions API instead.

## Engineering principles

- Fix root causes, not symptoms. Diagnose the underlying cause before implementing a permanent fix. If an immediate mitigation is necessary, treat it as temporary and follow through with a root-cause fix.
- Prefer configuration-driven design for values that are expected to vary by environment, deployment, or product requirements. Avoid unexplained or duplicated magic values, but do not introduce configuration where a well-named constant is the clearer source of truth.
- Preserve a single source of truth and clear ownership for data, state, configuration, business logic, and authoritative documentation. Avoid duplicating canonical information across multiple locations.
- Do not maintain parallel legacy and replacement implementations without an explicit migration and removal plan.

## Documentation

- Keep documentation aligned with the code. When a code change affects documented behavior, APIs, architecture, configuration, workflows, or usage, update the relevant documentation in the same change.
- Keep each document's responsibility clear. For example, use `README.md` for project overview and usage, and `VISION.md` for product direction, architectural principles, or long-term decisions.
- Always specify a language identifier for fenced code blocks in Markdown.

## Language

- Communicate with the user in Chinese, including explanations, progress updates, and user-facing planning.
- Use English for development artifacts, including source code, comments, docstrings, documentation, READMEs, Git branch names, commit messages, and other deliverables intended to live in the repository.

## Git

- Do not change or override the Git author or committer identity. When an identity must be configured for commits created during the task, use:
  - Name: `urntt`
  - Email: `urntts@gmail.com`
- Do all actions on the user's behalf. Do not rewrite existing commit authorship unless explicitly requested. Do not add `Co-Authored-By` trailers or session links to commit messages or pull request descriptions.
- Develop on `main` and push directly to it. Branches and pull requests are not required.
- Because changes land on `main` without review, make sure `./gradlew build` and the client game tests pass locally before pushing.
- If a branch is used, give it a category-based prefix that reflects the purpose of the change, such as `feat/`, `fix/`, `refactor/`, `docs/`, `test/`, or `chore/`.
- Follow the [Conventional Commits](https://www.conventionalcommits.org/) specification for commit messages.
