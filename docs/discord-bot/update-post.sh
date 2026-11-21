#!/bin/bash
# Обновляет закреплённое сообщение с кнопками в #добро-пожаловать из post.txt (кнопки и закреп не трогает).
set -e
cd /opt/smeshariki-role-bot
set -a; . /etc/smeshariki-role-bot.env; set +a
PYTHONDONTWRITEBYTECODE=1 venv/bin/python bot.py edit 2>&1 | grep -E "изменено|Error|Traceback|длиннее|пустой" || true
