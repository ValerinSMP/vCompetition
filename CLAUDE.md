# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
mvn clean package
```

Produces shaded JAR in `target/`. SQLite JDBC is shaded to `com.valerinsmp.vcompetition.libs.sqlite` to avoid server conflicts. Java 21, Paper 1.21.x target.

## Architecture

Bukkit/Paper plugin for weekly farming competitions on ValerinSMP. Spanish-language UI. Service-oriented layers:

- **`VCompetitionPlugin`** — entry point, bootstraps all services, proxy for competition state queries
- **`CompetitionService`** — core logic: scoring, rankings, anti-exploit, tournament lifecycle
- **`WeeklyScheduleService`** — cron-style auto start/stop (Monday 18:00 → Sunday 22:00, America/Santiago)
- **`SQLiteManager`** — single-threaded executor for all async DB ops; 7s drain on shutdown
- **`VCompetitionPlaceholderExpansion`** — 27 PlaceholderAPI placeholders
- **`FancyNpcSkinRefreshService`** — optional FancyNpcs soft-depend for top-3 NPC skins
- **`MessageService`** / **`SoundService`** — rendering and audio feedback

## Challenge Types

`ChallengeType` enum: `MINING`, `WOODCUTTING`, `FISHING`, `SLAYER`, `PLAYTIME`.

## Anti-Exploit

- Placed blocks tracked in `placed_blocks` SQLite table; player-placed blocks don't count
- Natural entity detection: spawner/placed mobs excluded from SLAYER scoring
- World exclusion list in `config.yml`

## Database

SQLite (`competition.db`), WAL mode. Tables: `challenge_state`, `player_progress`, `placed_blocks`, `challenge_results`, `player_wins_total`, `player_wins_challenge`.

All queries are async via `SQLiteManager`'s single-threaded executor. Never call DB methods from the main thread directly.

## Configuration

- `config.yml` — challenge lists, scheduling, rewards (commands run as console for top 1/2/3), excluded worlds/blocks
- `messages.yml` — all player-facing strings in Spanish, gradient color support
- `sounds.yml` — sound events for start/end/outrank

Hot-reloadable via `admin reload` command.

## Key Patterns

- Score state lives in `ConcurrentHashMap` in `CompetitionService` during active tournament
- On shutdown, state is persisted to SQLite and reloaded on next `onEnable` if tournament was mid-run
- Cooldown maps throttle notification spam (outrank events)
- Commands wired in `plugin.yml`; tab completion in `VCompetitionAdminCommand`
