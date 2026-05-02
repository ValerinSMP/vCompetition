# AGENTS

This file helps AI coding agents become productive quickly in this repository.

## Scope
- This is a Java Paper plugin for weekly farming competitions.
- UI and player-facing text are Spanish-first.
- Prefer minimal, targeted changes that preserve existing behavior.

## First Read
- Project overview and server-facing behavior: [README.md](README.md)
- Architecture and operational constraints: [CLAUDE.md](CLAUDE.md)

## Build And Verify
- Build command: mvn clean package
- Expected output: shaded plugin jar in target/
- Target stack: Java 21, Paper 1.21.x

## Core Boundaries
- Entry point wiring and lifecycle: [src/main/java/com/valerinsmp/vcompetition/VCompetitionPlugin.java](src/main/java/com/valerinsmp/vcompetition/VCompetitionPlugin.java)
- Tournament logic and scoring: [src/main/java/com/valerinsmp/vcompetition/service/CompetitionService.java](src/main/java/com/valerinsmp/vcompetition/service/CompetitionService.java)
- Database access layer: [src/main/java/com/valerinsmp/vcompetition/storage/SQLiteManager.java](src/main/java/com/valerinsmp/vcompetition/storage/SQLiteManager.java)
- Scheduling: [src/main/java/com/valerinsmp/vcompetition/service/DailyScheduleService.java](src/main/java/com/valerinsmp/vcompetition/service/DailyScheduleService.java)
- Commands and tab completion: [src/main/java/com/valerinsmp/vcompetition/command/VCompetitionAdminCommand.java](src/main/java/com/valerinsmp/vcompetition/command/VCompetitionAdminCommand.java)
- Event scoring and anti-exploit hooks: [src/main/java/com/valerinsmp/vcompetition/listener/CompetitionListener.java](src/main/java/com/valerinsmp/vcompetition/listener/CompetitionListener.java)

## Non-Negotiable Rules
- Never do direct SQLite work on the main server thread.
- Route DB operations through SQLiteManager async APIs.
- Preserve anti-exploit behavior:
  - Placed blocks must not score as natural blocks.
  - Spawner or player-origin mobs must not score for slayer.
  - Respect excluded worlds from config.
- Keep admin permission checks aligned with plugin.yml permission nodes.
- Keep PlaceholderAPI and FancyNpcs integrations optional and safe when absent.
- Preserve Spanish player-facing message conventions unless explicitly requested.

## Change Checklist For Agents
- If you add or rename an admin subcommand, update both:
  - Command handling and tab completion in VCompetitionAdminCommand
  - Permissions and command metadata in src/main/resources/plugin.yml
- If you add config keys, ensure reload flow still picks them up via admin reload.
- If you change scoring or lifecycle logic, verify state save/restore behavior on disable/enable.
- Run mvn clean package after code changes.

## Documentation Strategy
- Do not duplicate large docs in instruction files.
- Keep AGENTS.md short and link to README.md and CLAUDE.md for deeper details.
