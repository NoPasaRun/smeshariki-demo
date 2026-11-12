#!/usr/bin/env bash
# Подключает удалённые репозитории GitHub и отправляет в них код. Репозитории надо создать заранее (пустые, без README и лицензии), см. ниже.
#
#   docs/publish.sh <владелец> [--push]
#
# Без --push только подставляет владельца в README и подключает remote `origin` (ничего не отправляя). С --push отправляет ветку main и теги.
# Имена репозиториев: smeshariki-demo (корень: README, docs, релизы) и smeshariki-uilib, -gameplaymod, -managementmod, -worldmod, -behaviormod, -buildmod, -levelmod.
set -euo pipefail
OWNER="${1:?укажите владельца GitHub: docs/publish.sh <владелец> [--push]}"
PUSH="${2:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# подставить владельца в README (заглушка OWNER)
grep -rl "OWNER" README.md */README.md 2>/dev/null | while read -r f; do sed -i "s#OWNER/#$OWNER/#g; s#github.com/OWNER#github.com/$OWNER#g" "$f"; done
declare -A REPO=([.]=smeshariki-demo [UiLib]=smeshariki-uilib [GameplayMod]=smeshariki-gameplaymod [ManagementMod]=smeshariki-managementmod [WorldMod]=smeshariki-worldmod [BehaviorMod]=smeshariki-behaviormod [BuildMod]=smeshariki-buildmod [LevelMod]=smeshariki-levelmod)
for dir in "${!REPO[@]}"; do
  url="https://github.com/$OWNER/${REPO[$dir]}.git"
  ( cd "$dir"
    if git diff --quiet && git diff --cached --quiet; then :; else git add -A; git commit -q -m "Владелец репозиториев: $OWNER (README)"; fi
    if git remote get-url origin >/dev/null 2>&1; then git remote set-url origin "$url"; else git remote add origin "$url"; fi
    echo "$dir -> $url"
    if [ "$PUSH" = "--push" ]; then git push -u origin main --tags; fi )
done
echo "готово. Включите в smeshariki-demo релиз: ManagementMod/tools/export_world.py, затем ManagementMod/tools/release.py --upload"
