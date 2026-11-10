# Константы GameplayMod, подобранные под мир или уровень (2026-11-10)

Всего в GameplayMod около 230 `static final` чисел. Большинство — технические (радиусы поиска, интервалы, лимиты кэшей) и баланс самой механики. Ниже те, что **подбирались под конкретную реку, пещеру, маршрут или внешний вид** и потому не должны жить в коде механики. Правило (решение пользователя 2026-11-10): настройка под мир — в данных мира (маркер, сущность, JSON, `/data`), не в коде.

## Уже вынесено
| Было в коде | Стало | Где |
|---|---|---|
| `LogRiver.CENTERS {-0,7; 0,7; 0}`, `PHASES {0; π/2; 0}`, `SHIFTS {-2; 2; 0}`, `SWAYS {0,3; 0,3; 0}`, `WAVELENGTH 70`, `GAP_MIN 12`, `GAP_MAX 36` (ряды брёвен подобраны под реку «Ромашковой Долины»: «правка рядов по просьбе пользователя 2026-11-04») | поле маршрута `lanes[]` (`center`, `phase`, `shift`, `sway`), `wavelength`, `gap_min`, `gap_max` в `data/gameplaymod/log_routes.dat`; без них мод пускает один ряд по средней линии | `raft/LogRoute.Flow`, `LogRoutes.Entry`; значения реки записаны в мир `SmesharikiWorldMod/tools/log_routes_dat.py` (`DEFAULT_FLOW`) |
| `SUN_BOOST 6,25`, `GUIDE_LIGHT 0,9`, `GUIDE_DARK 0,25`, `AMBIENT_LIGHT 0,9`, `AMBIENT_DARK 0,3` (яркость неба и приглушение света) | в SmesharikiWorldMod («отображение») | `world/mixin/LightmapRenderStateExtractorMixin`, `world/ViewLight` |

## Осталось и подобрано под уровень (кандидаты на вынос в данные)
| Константа | Значение | Файл | Под что подобрана (по CLAUDE.md и комментариям в коде) |
|---|---|---|---|
| `BalloonEntity.WIND_SPEED` | 25/20 блока/тик (было 5 блоков/с) | `raft/BalloonEntity` | «усиление ветра до 25 блоков/с» ради полёта шара к засаде; расчёт маршрута — `SmesharikiWorldMod/tools/balloon_route.py` |
| `BalloonEntity.SINK` | 0,5/20 | там же | «шар сам опускается»: топливо нужно, чтобы держать высоту; баланс полёта уровня |
| `BalloonRopeItem.MAX_FUEL` | 256 (было 128) | `raft/BalloonRopeItem` | хватает на весь маршрут уровня; прочность предмета связана |
| `BalloonEntity.MAX_BLOCKS` / `Rafts.MAX_BLOCKS` | 600 / 300 | `raft/` | размеры построек уровня |
| `BarrageEntity.DEFAULT_DENSITY` | `{0,0,0.2,0.4,0.6,0.6,0.6,0.6,0.6,0.4,0.2,0}` | `barrage/BarrageEntity` | профиль «погони»; у маркера переопределяется через `/data` (`Density`) |
| `Barrages.RISE` 0,6, `MIN_FLIGHT` 2,5, `MAX_ARROWS` 256, `ARROW_TICKS` 100, `SLOW_MARGIN` 10, `SLOW_RECOVERY` 3 | — | `barrage/Barrages` | «обстрел только за торможение», «чуть жёстче» (правки 2026-11-08 по жалобам на сложность) |
| `LavaWaveEntity.LAVA_TOP` 8/9, `RISE_TICKS` 20, `CRASH_TICKS` 12, `PULL` 0,12, `MAX_PULL` 0,25 | — | `lava/LavaWaveEntity` | вид и темп волны в пещере |
| `RaftEntity.LAVA_SUNK` 0,4, `LAVA_HOLD` 40 | — | `raft/RaftEntity` | «плоты тонут при обрушении волны лавы» |
| `RaftEntity.SPEED` 6,66/20; `Currents.SPEED` 10/20; `LogRoute.FALL_WEIGHT` 0,5 | — | `raft/`, `current/` | скорость реки под прыжки по брёвнам |
| `CalmRopeEntity.LIMIT` 2,0; `RopeEntity.LONGEST` 48 | — | `rope/` | «тарзанка без размаха» — для пещеры со сводом над лавой |
| `SlideBlock.ACCEL` 0,18, `MAX_SPEED` 1,65; `SlopeSurface.*` (около 20 коэффициентов) | — | `slide/` | трасса «ледяной горки» уровня; баланс скольжения |

## Баланс механик (решения пользователя, в порядке; при желании — в конфиг)
`CharacterEntity`: `MAX_HEALTH` 20, `BREAK_SPEED_FACTOR` 0,2, `FOOD_HEAL` 4, `MAX_FIRE_TICKS` 60, `SWIM_COST` 1/80, `STAMINA_REGEN` 1/200, `ROLL_*` (клубок Ёжика), `GLIDE_*`, `SPRINT_JUMP_*`, `AIR_CONTROL*`; `Characters.GLASSES_DURABILITY` 30; `RopeEntity.MIN/MAX/DEFAULT_LENGTH` 0,2/24/8, `TAUT`, `GRAVITY`, `SWING_*`, `SLIDE_SPEED`; `WindMarkerEntity.RADIUS` 5, `EDGE_STRENGTH` 0,2; `SpikeBlock.DAMAGE` 6; `DeadzoneScroll.*`.

## Технические (не трогать)
Радиусы поиска и билеты чанков (`LogRiver.ACTIVE_RADIUS` 160, `TICKET_*`, `CurrentField.MAX_RADIUS/MAX_CELLS/REFRESH_TICKS`, `LavaLayer.LIMIT/CELLS_PER_TICK`), интервалы опроса (`Possession.ASK_INTERVAL`, `Singletons.FETCH_TICKS/TRACK_INTERVAL`), форматные (`SlideBlock.STEPS` 16, `ColumnBlock.MAX_HEIGHT` 24), `MAX_STEP`, `MAX_DISTANCE` кликов.

## Константы вне GameplayMod
- **LevelMod:** `AREA_GRACE_TICKS` 200 (10 с на возвращение в область), `TAKE_RADIUS` 8, `START_RADIUS` 4, `SPECTATE_PAUSE` 40, `FADE_HOLD/OUT`, `WorldReset.BUDGET_NANOS` 6 мс, `BATCH` 48, `AreaReset.BUDGET_NANOS` 6 мс, `SYNC_INTERVAL` 10. Всё это — правила прохождения, а не мира.
- **BuildMod:** лимит отмены 10, `MAX_MATERIALS`, `PaintSlots.KINDS`, число состояний крашеных блоков (замер: +0,8 с запуска и +14 МБ кучи на слот ступенек) — **риск роста времени старта**, если добавлять слоты.
- **ManagementMod:** `MAX_FILES` 4096, `MAX_FILE_SIZE` 1 ГиБ, `TIMEOUT` 15 мин, `maxFileSizeMb` 512 (в конфиге).
