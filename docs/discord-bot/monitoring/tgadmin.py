#!/usr/bin/env python3
"""Админ-бот в Telegram (тот же бот, что шлёт тревоги): управление ботом ролей Discord с телефона.

Команды (только владелец): /status, /logs, /restart, /texts (/text — то же).
/texts: список текстов из texts.json (сообщения бота в Discord и описание сервера); выбрать -> ответить новым текстом ->
предпросмотр -> «Применить». Прежний текст сохраняется в text_backups/.
Ключи: из /etc/smeshariki-alert.env — TG_TOKEN, TG_CHAT_ID (личный чат владельца); из /etc/smeshariki-role-bot.env — DISCORD_BOT_TOKEN.
Если TG_CHAT_ID — группа (отрицательное число), обязательно задайте TG_ADMIN_IDS (id пользователей через пробел).
Никаких произвольных команд: только фиксированный набор действий, ввод пользователя в командную строку не попадает.
"""
import asyncio
import html
import json
import logging
import os
import re
import shutil
import sys
import time
from pathlib import Path

import aiohttp

TOKEN = os.environ["TG_TOKEN"]
CHAT = int(os.environ["TG_CHAT_ID"])
DISCORD_TOKEN = os.environ["DISCORD_BOT_TOKEN"]
GUILD_ID = os.environ.get("GUILD_ID", "1571156683621667008")
ADMINS = {int(x) for x in os.environ.get("TG_ADMIN_IDS", "").replace(",", " ").split()}
if CHAT > 0:
    ADMINS.add(CHAT)
if not ADMINS:
    sys.exit("TG_CHAT_ID — группа: задайте TG_ADMIN_IDS")

SERVICE = "smeshariki-role-bot"
BOT_DIR = Path(os.environ.get("BOT_DIR", "/opt/smeshariki-role-bot"))
POST = BOT_DIR / "post.txt"          # текст сообщения с ролями (его же читает bot.py post)
BACKUPS = BOT_DIR / "text_backups"
TEXTS = {t["key"]: t for t in json.loads((BOT_DIR / "texts.json").read_text(encoding="utf-8"))}
INVITE_CHANNEL = os.environ.get("WELCOME_CHANNEL_ID", "1571189898252124333")  # в какой канал ведут ссылки
INVITES_FILE = BOT_DIR / "invites.json"      # метки ссылок: код -> {"label", "created"}
INVITE_KEY = "__invite__"                    # служебный ключ ожидания: следующее сообщение — название новой ссылки
CODE_RE = re.compile(r"^[A-Za-z0-9-]{2,32}$")
API = "https://api.telegram.org/bot%s/" % TOKEN
DISCORD = "https://discord.com/api/v10/"
CONFIRM_TTL = 60  # секунд на подтверждение перезапуска

log = logging.getLogger("tgadmin")
WAIT_TTL = 900                               # секунд, пока бот ждёт новый текст после выбора
waiting: dict = {"key": None, "ts": 0.0}     # какой текст сейчас редактируется (принимаем следующее сообщение)
prompts: dict[int, str] = {}                 # id сообщения Telegram, на которое ждём ответ -> ключ текста
pending: dict[str, tuple[str, str]] = {}     # токен подтверждения -> (ключ, новый текст)


def to_discord_markdown(text: str, entities: list[dict] | None) -> str:
    """Telegram сам превращает «**текст**» в жирный шрифт и убирает звёздочки: восстановить разметку Discord по entities.
    Смещения в Telegram считаются в единицах UTF-16 (эмодзи занимает две)."""
    if not entities:
        return text
    simple = {"bold": ("**", "**"), "italic": ("*", "*"), "underline": ("__", "__"), "strikethrough": ("~~", "~~"),
              "spoiler": ("||", "||"), "code": ("`", "`"), "blockquote": ("> ", ""), "expandable_blockquote": ("> ", "")}
    u = text.encode("utf-16-le")
    n = len(u) // 2
    ws = {0x20, 0x0A, 0x09, 0x0D, 0xA0}

    def unit(i):
        return u[2 * i] | (u[2 * i + 1] << 8)

    events = []
    for e in entities:
        t = e.get("type")
        start, end = e["offset"], e["offset"] + e["length"]
        if t == "pre":
            lang = e.get("language") or ""
            marks = ("```%s\n" % lang, "\n```")
        elif t == "text_link":
            marks = ("[", "](%s)" % e["url"])
        elif t in simple:
            marks = simple[t]
        else:
            continue  # ссылки, упоминания и прочее остаются обычным текстом
        if t != "pre":  # Discord не терпит пробела у границы разметки: «**текст **» не сработает
            while start < end and unit(start) in ws:
                start += 1
            while end > start and unit(end - 1) in ws:
                end -= 1
        if start >= end or end > n:
            continue
        events.append((start, 1, -(end - start), marks[0]))   # открытие: длинные (внешние) раньше
        events.append((end, 0, -start, marks[1]))              # закрытие: внутренние (начавшиеся позже) раньше
    events.sort(key=lambda ev: ev[:3])
    out, pos = [], 0
    for p, _, _, mark in events:
        out.append(u[2 * pos:2 * p].decode("utf-16-le"))
        out.append(mark)
        pos = p
    out.append(u[2 * pos:].decode("utf-16-le"))
    return "".join(out)


async def sh(*args: str, timeout: int = 30) -> tuple[int, str]:
    p = await asyncio.create_subprocess_exec(*args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
    try:
        out, _ = await asyncio.wait_for(p.communicate(), timeout)
    except asyncio.TimeoutError:
        p.kill()
        return 124, "таймаут"
    return p.returncode, out.decode(errors="replace")


async def api(session: aiohttp.ClientSession, method: str, **params):
    try:
        async with session.post(API + method, json=params, timeout=aiohttp.ClientTimeout(total=70)) as r:
            data = await r.json()
    except (aiohttp.ClientError, asyncio.TimeoutError) as e:
        log.warning("%s: %s", method, type(e).__name__)  # текст ошибки aiohttp может содержать адрес с токеном
        return None
    if not data.get("ok"):
        log.warning("%s: %s", method, data.get("description"))
        return None
    return data["result"]


async def discord(session: aiohttp.ClientSession, method: str, path: str, body: dict | None = None) -> tuple[int, object]:
    """Запрос к Discord REST от имени бота; (код, JSON или None)."""
    headers = {"Authorization": "Bot " + DISCORD_TOKEN, "User-Agent": "DiscordBot (tgadmin, 1.0)"}
    try:
        async with session.request(method, DISCORD + path, json=body, headers=headers, timeout=aiohttp.ClientTimeout(total=20)) as r:
            try:
                return r.status, await r.json()
            except aiohttp.ContentTypeError:
                return r.status, None
    except (aiohttp.ClientError, asyncio.TimeoutError) as e:
        log.warning("Discord %s %s: %s", method, path.split("/")[0], type(e).__name__)
        return 0, None


async def send(s, text: str, **extra):
    return await api(s, "sendMessage", chat_id=CHAT, text=text, **extra)


def keyboard(*rows: tuple[tuple[str, str], ...]) -> dict:
    return {"inline_keyboard": [[{"text": t, "callback_data": d} for t, d in row] for row in rows]}


async def status(s):
    _, active = await sh("systemctl", "is-active", SERVICE)
    _, show = await sh("systemctl", "show", SERVICE, "-p", "NRestarts", "-p", "ActiveEnterTimestamp")
    info = dict(line.split("=", 1) for line in show.splitlines() if "=" in line)
    du = shutil.disk_usage("/")
    mem = next((int(l.split()[1]) // 1024 for l in Path("/proc/meminfo").read_text().splitlines() if l.startswith("MemAvailable")), 0)
    icon = "🟢" if active.strip() == "active" else "🔴"
    await send(s, "%s Бот ролей: <b>%s</b>\nЗапущен: %s\nПерезапусков: %s\n\n💾 Диск: %d%% (свободно %.1f ГБ)\n🧠 Память доступна: %d МБ" % (
        icon, html.escape(active.strip()), html.escape(info.get("ActiveEnterTimestamp", "?")), html.escape(info.get("NRestarts", "?")),
        round(du.used * 100 / du.total), du.free / 2**30, mem), parse_mode="HTML")


async def logs(s):
    _, out = await sh("journalctl", "-u", SERVICE, "-n", "25", "--no-pager", "-o", "cat")
    out = "\n".join(l for l in out.splitlines() if "voice will NOT" not in l)
    out = re.sub(r"[A-Za-z0-9_.-]{40,}", "<скрыто>", out)[-3500:]
    await send(s, "<pre>%s</pre>" % html.escape(out or "пусто"), parse_mode="HTML")


async def ask_restart(s):
    await send(s, "Перезапустить бота ролей Discord? Кнопки в Discord будут недоступны несколько секунд.",
               reply_markup=keyboard((("Да, перезапустить", "rs:%d" % time.time()), ("Отмена", "no"))))


async def do_restart(s):
    await send(s, "Перезапускаю…")
    code, out = await sh("systemctl", "restart", SERVICE, timeout=60)
    await asyncio.sleep(8)
    _, active = await sh("systemctl", "is-active", SERVICE)
    await send(s, ("🟢 Готово, бот работает." if active.strip() == "active" else "🔴 После перезапуска состояние: %s" % active.strip()) +
               ("" if code == 0 else "\nsystemctl: код %d" % code))


# --- ссылки-приглашения по источникам ---

def load_labels() -> dict:
    try:
        return json.loads(INVITES_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_labels(d: dict):
    INVITES_FILE.write_text(json.dumps(d, ensure_ascii=False, indent=1), encoding="utf-8")


async def list_invites(s):
    code, data = await discord(s, "GET", "guilds/%s/invites" % GUILD_ID)
    if code != 200 or not isinstance(data, list):
        await send(s, "Не получилось получить список ссылок (код %d)." % code)
        return
    labels = load_labels()
    rows = [(("➕ Новая ссылка", "iv:new"),)]
    lines = []
    for inv in sorted(data, key=lambda i: -i.get("uses", 0)):
        lab = labels.get(inv["code"], {}).get("label")
        if lab:
            lines.append("📌 %s: %d вх. https://discord.gg/%s" % (lab, inv.get("uses", 0), inv["code"]))
            rows.append((("🗑 " + lab[:30], "iv:del:" + inv["code"]),))
        else:
            who = (inv.get("inviter") or {}).get("username", "?")
            lines.append("• без метки (создал %s): %d вх. https://discord.gg/%s" % (who, inv.get("uses", 0), inv["code"]))
    body = "\n".join(lines) if lines else "Ссылок пока нет."
    await send(s, ("Ссылки-приглашения (входов по каждой, считает Discord):\n\n%s\n\nСоздать новую: /invite название" % body)[:4000],
               reply_markup=keyboard(*rows), link_preview_options={"is_disabled": True})


async def create_invite(s, label: str):
    label = " ".join(label.split())[:30]
    if not label:
        await send(s, "Название не должно быть пустым. Пример: /invite modrinth")
        return
    labels = load_labels()
    taken = {v["label"].lower() for v in labels.values()}
    base, n = label, 2
    while label.lower() in taken:  # одинаковые названия различаем номером
        label = "%s %d" % (base, n)
        n += 1
    code, data = await discord(s, "POST", "channels/%s/invites" % INVITE_CHANNEL,
                               {"max_age": 0, "max_uses": 0, "temporary": False, "unique": True})
    if code not in (200, 201) or not isinstance(data, dict) or "code" not in data:
        await send(s, "❌ Discord не создал ссылку (код %d)." % code)
        return
    labels[data["code"]] = {"label": label, "created": int(time.time())}
    save_labels(labels)
    await send(s, "✅ Ссылка «%s» (без срока и лимита):\nhttps://discord.gg/%s\n\nСколько пришло по каждой: /invites" % (label, data["code"]),
               link_preview_options={"is_disabled": True})


async def ask_delete_invite(s, code: str):
    lab = load_labels().get(code, {}).get("label")
    if not lab or not CODE_RE.match(code):
        await send(s, "Такой ссылки с меткой нет.")
        return
    await send(s, "Удалить ссылку «%s»? Новые люди по ней больше не смогут зайти (уже вошедшие остаются)." % lab,
               reply_markup=keyboard((("Да, удалить", "iv:yes:" + code), ("Отмена", "no"))))


async def delete_invite(s, code: str):
    labels = load_labels()
    if code not in labels or not CODE_RE.match(code):  # удаляем только созданные через бота, с меткой
        await send(s, "Такой ссылки с меткой нет.")
        return
    status, _ = await discord(s, "DELETE", "invites/" + code)
    if status in (200, 204, 404):
        lab = labels.pop(code)["label"]
        save_labels(labels)
        await send(s, "🗑 Ссылка «%s» удалена." % lab)
    else:
        await send(s, "❌ Discord не удалил ссылку (код %d)." % status)


# --- тексты ---

async def current_text(s, t: dict) -> str | None:
    """Текст сейчас: для сообщений и описания сервера читается из Discord, для ролей — из post.txt."""
    if t["kind"] == "roles":
        return POST.read_text(encoding="utf-8").strip() if POST.exists() else None
    if t["kind"] == "guild":
        code, data = await discord(s, "GET", "guilds/" + GUILD_ID)
        return (data.get("description") or "") if code == 200 and data else None
    code, data = await discord(s, "GET", "channels/%s/messages/%s" % (t["channel"], t["message"]))
    return data.get("content") if code == 200 and data else None


async def list_texts(s):
    rows = [((t["title"], "tx:" + k),) for k, t in TEXTS.items()]
    await send(s, "Какой текст изменить?", reply_markup=keyboard(*rows))


async def show_text(s, key: str):
    t = TEXTS.get(key)
    if not t:
        return
    cur = await current_text(s, t)
    if cur is None:
        await send(s, "Не получилось прочитать текст «%s» (бот не видит сообщение или нет доступа)." % t["title"])
        return
    fmt = "разметка Discord: **жирный**, [текст](ссылка)" if t["kind"] != "guild" else "обычный текст"
    waiting.update(key=key, ts=time.time())
    msg = await send(s, "«%s»: сейчас %d из %d знаков. Чтобы заменить, отправьте новый текст следующим сообщением (%s). "
                        "Отмена: любая команда.\n\n%s" % (t["title"], len(cur.strip()), t["limit"], fmt, cur))
    if msg:
        prompts[msg["message_id"]] = key
        while len(prompts) > 20:
            prompts.pop(next(iter(prompts)))


async def propose(s, key: str, new: str):
    t = TEXTS[key]
    new = new.strip()
    if not new or len(new) > t["limit"]:
        await send(s, "Текст «%s» должен быть от 1 до %d знаков, сейчас %d. Ответьте на сообщение ещё раз." % (t["title"], t["limit"], len(new)))
        return
    token = "%x" % int(time.time() * 1000)
    pending[token] = (key, new)
    while len(pending) > 5:
        pending.pop(next(iter(pending)))
    await send(s, "«%s»: новый текст (%d знаков):\n\n%s\n\nПрименить?" % (t["title"], len(new), new),
               reply_markup=keyboard((("Применить", "ap:" + token), ("Отмена", "no"))))


async def apply_text(s, token: str):
    item = pending.pop(token, None)
    if item is None:
        await send(s, "Это предложение устарело. Начните заново: /texts")
        return
    key, new = item
    t = TEXTS[key]
    old = await current_text(s, t)
    if old is not None:  # копия прежнего текста на случай, если надо вернуть
        BACKUPS.mkdir(exist_ok=True)
        (BACKUPS / (key + ".txt")).write_text(old, encoding="utf-8")
    if t["kind"] == "guild":
        code, data = await discord(s, "PATCH", "guilds/" + GUILD_ID, {"description": new})
    else:
        code, data = await discord(s, "PATCH", "channels/%s/messages/%s" % (t["channel"], t["message"]),
                                   {"content": new, "allowed_mentions": {"parse": []}})
    if code != 200:
        msg = (data or {}).get("message", "") if isinstance(data, dict) else ""
        await send(s, "❌ Discord не принял текст (код %d) %s. Ничего не изменено." % (code, msg))
        return
    if t["kind"] == "roles":
        POST.write_text(new + "\n", encoding="utf-8")  # чтобы следующий `bot.py post/edit` не откатил текст
    await send(s, "✅ Обновлено: «%s». Прежний текст сохранён в text_backups/%s.txt." % (t["title"], key))


HELP = ("Команды:\n/status — состояние бота, диск, память\n/logs — последние строки журнала\n"
        "/restart — перезапуск бота ролей\n/texts — изменить тексты (роли, правила, установка, баг-репорт, описание сервера)\n"
        "/invites — ссылки-приглашения и сколько пришло по каждой\n/invite название — новая ссылка для источника (modrinth, github…)")


async def handle(s, u: dict):
    cb = u.get("callback_query")
    if cb:
        msg = cb.get("message") or {}
        if cb["from"]["id"] not in ADMINS or msg.get("chat", {}).get("id") != CHAT:
            return
        await api(s, "answerCallbackQuery", callback_query_id=cb["id"])
        await api(s, "editMessageReplyMarkup", chat_id=CHAT, message_id=msg["message_id"], reply_markup={"inline_keyboard": []})
        data = cb.get("data", "")
        if data.startswith("rs:"):
            if time.time() - int(data[3:] or 0) > CONFIRM_TTL:
                await send(s, "Подтверждение устарело (больше %d с). Повторите /restart." % CONFIRM_TTL)
            else:
                await do_restart(s)
        elif data.startswith("ap:"):
            await apply_text(s, data[3:])
        elif data.startswith("tx:"):
            await show_text(s, data[3:])
        elif data == "iv:new":
            waiting.update(key=INVITE_KEY, ts=time.time())
            await send(s, "Как назвать ссылку (источник)? Например: modrinth, github, youtube. Отправьте название следующим сообщением.")
        elif data.startswith("iv:del:"):
            await ask_delete_invite(s, data[7:])
        elif data.startswith("iv:yes:"):
            await delete_invite(s, data[7:])
        else:
            await send(s, "Отменено.")
        return
    m = u.get("message")
    if not m or m.get("chat", {}).get("id") != CHAT or m.get("from", {}).get("id") not in ADMINS:
        if m:
            log.warning("чужое сообщение от %s в чате %s проигнорировано", m.get("from", {}).get("id"), m.get("chat", {}).get("id"))
        return
    text = m.get("text") or ""
    reply_to = (m.get("reply_to_message") or {}).get("message_id")
    if text and not text.startswith("/"):
        key = prompts.get(reply_to)  # ответ на сообщение с текстом: берём его ключ
        if key is None and waiting["key"] and time.time() - waiting["ts"] < WAIT_TTL:
            key = waiting["key"]     # иначе — текст, выбранный последним
        if key == INVITE_KEY:
            waiting["key"] = None
            await create_invite(s, text)
            return
        if key:
            waiting["key"] = None
            if TEXTS[key]["kind"] != "guild":  # описание сервера — обычный текст без разметки
                text = to_discord_markdown(text, m.get("entities"))
            await propose(s, key, text)
            return
        log.info("сообщение без выбранного текста (reply_to=%s): подсказка", reply_to)
        await send(s, "Не знаю, к какому тексту это относится. Сначала выберите его: /texts\n\n" + HELP)
        return
    waiting["key"] = None  # любая команда отменяет ожидание текста
    cmd = text.split()[0].split("@")[0].lower() if text.startswith("/") else ""
    if cmd == "/invite":  # «/invite modrinth» создаёт ссылку сразу, без названия — спрашивает его
        parts = text.split(None, 1)
        if len(parts) > 1:
            await create_invite(s, parts[1])
        else:
            waiting.update(key=INVITE_KEY, ts=time.time())
            await send(s, "Как назвать ссылку (источник)? Например: modrinth, github, youtube. Отправьте название следующим сообщением.")
        return
    actions = {"/status": status, "/logs": logs, "/restart": ask_restart, "/texts": list_texts, "/text": list_texts, "/invites": list_invites}
    if cmd in actions:
        await actions[cmd](s)
    else:
        await send(s, HELP)


async def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    async with aiohttp.ClientSession() as s:
        await api(s, "setMyCommands", commands=[
            {"command": "status", "description": "Состояние бота, диск, память"},
            {"command": "logs", "description": "Последние строки журнала"},
            {"command": "restart", "description": "Перезапустить бота ролей"},
            {"command": "texts", "description": "Изменить тексты (роли, правила, описание сервера…)"},
            {"command": "invites", "description": "Ссылки-приглашения и число входов"},
            {"command": "invite", "description": "Новая ссылка: /invite modrinth"}])
        backlog = await api(s, "getUpdates", offset=-1, timeout=0)  # накопившееся за время простоя не выполняем
        offset = backlog[-1]["update_id"] + 1 if backlog else None
        log.info("админ-бот запущен, администраторов: %d, текстов: %d", len(ADMINS), len(TEXTS))
        while True:
            params = {"timeout": 30, "allowed_updates": ["message", "callback_query"]}
            if offset:
                params["offset"] = offset
            updates = await api(s, "getUpdates", **params)
            if updates is None:
                await asyncio.sleep(5)
                continue
            for u in updates:
                offset = u["update_id"] + 1
                try:
                    await handle(s, u)
                except Exception:
                    log.exception("сбой обработки")


if __name__ == "__main__":
    asyncio.run(main())
