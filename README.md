# vCompetition
Competiciones semanales para el Server de ValerinSMP

## Compatibilidad
- Paper 1.21.x

## Edición de tipos de competencia
- En `config.yml` puedes editar:
	- `competition-types.mining.materials`
	- `competition-types.woodcutting.materials`
	- `competition-types.fishing.materials`
	- `competition-types.slayer.mobs`
- También puedes excluir mundos completos del conteo de puntos con `excluded-worlds` (ej: `world_minapvp`).
- Usa nombres válidos de `Material` y `EntityType` de Bukkit/Paper.
- Aplica cambios con `/vcompetition admin reload`.

## Optimización y reload seguro
- Operaciones SQLite en hilo dedicado async.
- Seguimiento de tareas pendientes para vaciar cola antes de cerrar.
- `onDisable()` cancela tareas, guarda estado, espera pending async y cierra DB.
- Compatible con reloads tipo PlugMan bajo buenas prácticas (sin hard-kill del proceso).

## Horario semanal automático
- Se configura en `schedule` dentro de `config.yml`.
- Ejemplo incluido: inicio lunes 18:00 y fin domingo 22:00 en `America/Santiago`.
- Campos importantes:
	- `schedule.enabled`
	- `schedule.start-on-bootstrap-if-inside-window` (si está en `true`, al iniciar/reload dentro de la ventana semanal arrancará el torneo automáticamente)
	- `schedule.timezone`
	- `schedule.challenge` (tipo fijo o `RANDOM`)
	- `schedule.start.day|hour|minute`
	- `schedule.end.day|hour|minute`
	- `schedule.check-interval-ticks`
- Si un torneo termina manualmente dentro de la ventana (por ejemplo miércoles), el scheduler volverá a iniciar uno automáticamente hasta el cierre configurado (por ejemplo domingo), y luego continuará con el ciclo normal del lunes.
- Si `schedule.challenge` está en `RANDOM`, cada semana elige un reto aleatorio distinto al de la semana anterior.

## Auto-refresh de skins (FancyNpcs)
- Integración opcional vía `softdepend` (si FancyNpcs no está instalado, se ignora).
- Configuración en `fancy-npcs.skin-refresh` dentro de `config.yml`.
- Solo actualiza 3 NPCs fijos de ranking: top1, top2 y top3.
- Campos principales:
	- `fancy-npcs.skin-refresh.enabled`
	- `fancy-npcs.skin-refresh.initial-delay-seconds`
	- `fancy-npcs.skin-refresh.interval-seconds`
	- `fancy-npcs.skin-refresh.mode` (`UPDATE` o `RESPAWN`)
	- `fancy-npcs.skin-refresh.top-npcs.top1|top2|top3` (nombres de NPC en FancyNpcs)
- Opcional:
	- `fancy-npcs.skin-refresh.fallback-skins.global` (1 sola skin, por nombre o URL, para top1/2/3 cuando no hay ranking)
	- `fancy-npcs.skin-refresh.fallback-skins.top1|top2|top3` (override por puesto)
	- `fancy-npcs.skin-refresh.debug-logs` (logs en consola para verificar ciclo y cambios)
- La skin aplicada se obtiene automáticamente del jugador que esté en cada puesto del top actual.

## Comando principal
- `/vcompetition` (alias: `/competition`)

## Mensajes y prefijo (messages.yml)
- Los mensajes visibles se editan en `messages.yml`.
- El prefijo actual usa smallcaps con gradiente opción B:
	- `messages.prefix: '&8[<gradient:#FFD166:#FF9F1C>ᴛᴏʀɴᴇᴏꜱ</gradient>&8] <reset>'`
- Cada mensaje acepta placeholders (por ejemplo `%player%`, `%challenge%`, `%points%`).
- Puedes usar `%prefix%` dentro de cualquier línea o dejarlo fuera para que se anteponga automáticamente.
- Para espacios en blanco y bloques de anuncio, usa listas de líneas (incluyendo `''`).

## Sonidos de feedback (sounds.yml)
- Se configuran en `sounds.yml`.
- Eventos incluidos:
	- `sounds.tournament-start`
	- `sounds.tournament-end`
	- `sounds.outrank`
- Cada evento permite `enabled`, `sound`, `volume`, `pitch`.

## Administración
- `/vcompetition admin start <MINING|WOODCUTTING|FISHING|SLAYER>`
- `/vcompetition admin startuntilsunday <MINING|WOODCUTTING|FISHING|SLAYER|PLAYTIME>`
- `/vcompetition admin stop`
- `/vcompetition admin stopnorewards`
- `/vcompetition admin edit <jugador> <puntos>`
- `/vcompetition admin addpoints <jugador> <puntos>`
- `/vcompetition admin removepoints <jugador> <puntos>`
- `/vcompetition admin status`
- `/vcompetition admin top`
- `/vcompetition admin setduration <dias>`
- `/vcompetition admin resetplaced`
- `/vcompetition admin refreshskins`
- `/vcompetition admin reload`
- `/vcompetition admin debug`

## Permisos por subcomando
- `vcompetition.admin` (legacy, incluye todos)
- `vcompetition.admin.*`
- `vcompetition.admin.start`
- `vcompetition.admin.startuntilsunday`
- `vcompetition.admin.stop`
- `vcompetition.admin.stopnorewards`
- `vcompetition.admin.edit`
- `vcompetition.admin.addpoints`
- `vcompetition.admin.removepoints`
- `vcompetition.admin.reload`
- `vcompetition.admin.status`
- `vcompetition.admin.top`
- `vcompetition.admin.setduration`
- `vcompetition.admin.resetplaced`
- `vcompetition.admin.refreshskins`
- `vcompetition.admin.debug`

## PlaceholderAPI
Identificador: `%vcompetition_<placeholder>%`

### Estado actual
- `%vcompetition_challenge%`
- `%vcompetition_time_left%`
- `%vcompetition_gap_top12%`
- `%vcompetition_top_1_name%`, `%vcompetition_top_1_points%`
- `%vcompetition_top_2_name%`, `%vcompetition_top_2_points%`
- `%vcompetition_top_3_name%`, `%vcompetition_top_3_points%`

### Datos del jugador
- `%vcompetition_player_position%`
- `%vcompetition_player_points%`
- `%vcompetition_player_gap_up%`
- `%vcompetition_player_gap_down%`
- `%vcompetition_player_wins_total%`
- `%vcompetition_player_wins_mining%`
- `%vcompetition_player_wins_woodcutting%`
- `%vcompetition_player_wins_fishing%`
- `%vcompetition_player_wins_slayer%`

### Históricos globales
- `%vcompetition_wins_global_top_1_name%`, `%vcompetition_wins_global_top_1_wins%`
- `%vcompetition_wins_global_top_2_name%`, `%vcompetition_wins_global_top_2_wins%`
- `%vcompetition_wins_global_top_3_name%`, `%vcompetition_wins_global_top_3_wins%`

### Históricos por reto
- `%vcompetition_wins_mining_top_1_name%`, `%vcompetition_wins_mining_top_1_wins%`
- `%vcompetition_wins_woodcutting_top_1_name%`, `%vcompetition_wins_woodcutting_top_1_wins%`
- `%vcompetition_wins_fishing_top_1_name%`, `%vcompetition_wins_fishing_top_1_wins%`
- `%vcompetition_wins_slayer_top_1_name%`, `%vcompetition_wins_slayer_top_1_wins%`
