# Дымовая проверка интерфейса (клиентский gametest)

Запускает настоящий клиент Minecraft со всеми шестью jar из `~/.minecraft/mods`, создаёт творческий мир и открывает **все окна** модов с подставленными данными (`Smoke*.java` по пакетам модов). Для каждого окна: размеры и накладки виджетов (`OUT`, `OVERLAP`), колесо мыши, Esc, снимок экрана. Три прохода: 320×240 ru, 427×240 ru, 320×240 en (320×240 — наименьший GUI, который допускает ваниль).

```
cd docs/smoke-test
./gradlew runClientGameTest --no-daemon -Dorg.gradle.jvmargs=-Xmx900m > run.log 2>&1
grep -E "SMOKE|OVERLAP|OUT |Exception" run.log     # итог
ls build/run/clientGameTest/screenshots            # снимки: p<проход>_<окно>.png
```

- Перед запуском **собрать все моды и положить jar в `~/.minecraft/mods`** (порядок — `../../CLAUDE.md`), игра должна быть закрыта (память 7,2 ГБ).
- Новое окно — добавить `Case` в `Smoke<Мод>.java` того пакета, где оно лежит (окна package-private, поэтому классы проверки лежат в тех же пакетах).
- Чистый итог: нет строк `OUT`/`OVERLAP`, нет `FAILED`, `DONE` в конце, в логе нет `ERROR` от `levelmod`/`buildmod`… (ожидаемая ошибка `Уровень не загружен: … не отмечены локации` — в новом мире локаций нет).
- Не проверяет игровую логику (уровни, механики, два игрока) — только запуск и окна.
- `SMOKE_SEED=<папка>` (необязательно): содержимое папки копируется в папку игры до создания мира (Gradle очищает папку запуска, подложить заранее нельзя). Так проверяется перенос данных (например, `data/levelmod/worlds/New World/places.json` и `data/levelmod/levels/`) и музыка: если в `data/behaviormod/music/` есть `smoke_test.mp3`, проверка пишет ogg в пак мира и ждёт `SMOKE MUSIC OK` (без файла — `SMOKE MUSIC SKIP`).
- С тем же `SMOKE_SEED`: если там есть `resourcepacks/smoke_pack.zip`, проверяется автовключение ресурспака загрузчиком (`SMOKE PACK OK`, `SMOKE PACK again OK`).
