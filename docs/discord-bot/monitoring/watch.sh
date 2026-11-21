#!/bin/bash
# Проверка бота ролей и диска; сообщения в Telegram. Запускается таймером smeshariki-watch.timer раз в 5 минут.
# Ключи в /etc/smeshariki-alert.env (права 600):  TG_TOKEN=токен бота от @BotFather   TG_CHAT_ID=id чата или пользователя
# Без ключей ничего не отправляет и состояние не меняет.
ENV=/etc/smeshariki-alert.env
[ -r "$ENV" ] && . "$ENV"
[ -n "$TG_TOKEN" ] && [ -n "$TG_CHAT_ID" ] || exit 0

SERVICE=smeshariki-role-bot
STATE=/var/lib/smeshariki-alert
DISK_ALERT=90   # % занятости корня, с которого пишем
DISK_OK=85      # % занятости, ниже которого считаем, что стало нормально
mkdir -p "$STATE"
HOST=$(hostname)

send() {
    # токен передаётся через stdin, а не в аргументах (чтобы не светился в ps)
    printf 'url = "https://api.telegram.org/bot%s/sendMessage"\n' "$TG_TOKEN" |
        curl -s -m 15 -o /dev/null -K - --data-urlencode "chat_id=$TG_CHAT_ID" --data-urlencode "text=$1"
}

# --- бот ролей ---
now=$(systemctl is-active "$SERVICE")
restarts=$(systemctl show -p NRestarts --value "$SERVICE")
prev_state=$(cat "$STATE/bot_state" 2>/dev/null || echo active)
prev_restarts=$(cat "$STATE/bot_restarts" 2>/dev/null || echo "$restarts")
if [ "$now" != "active" ] && [ "$prev_state" = "active" ]; then
    send "🔴 [$HOST] бот ролей Discord не работает (состояние: $now). Журнал: journalctl -u $SERVICE -n 50"
elif [ "$now" = "active" ] && [ "$prev_state" != "active" ]; then
    send "🟢 [$HOST] бот ролей Discord снова работает."
fi
if [ "$restarts" -gt "$prev_restarts" ] 2>/dev/null; then
    send "⚠️ [$HOST] бот ролей перезапускался сам ($((restarts - prev_restarts)) раз за последние 5 минут). Смотрите журнал."
fi
echo "$now" > "$STATE/bot_state"
echo "$restarts" > "$STATE/bot_restarts"

# --- диск ---
use=$(df --output=pcent / | tail -1 | tr -dc 0-9)
disk_prev=$(cat "$STATE/disk_state" 2>/dev/null || echo ok)
if [ "$use" -ge "$DISK_ALERT" ] && [ "$disk_prev" = "ok" ]; then
    send "🔴 [$HOST] диск заполнен на ${use}% (свободно: $(df -h --output=avail / | tail -1 | tr -d ' '))."
    echo alert > "$STATE/disk_state"
elif [ "$use" -lt "$DISK_OK" ] && [ "$disk_prev" = "alert" ]; then
    send "🟢 [$HOST] диск в норме: ${use}%."
    echo ok > "$STATE/disk_state"
fi
