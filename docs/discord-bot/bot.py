#!/usr/bin/env python3
"""Бот сервера Discord «Romashkovaya Valley»: роли, заявки на роли, команды /версия и /установка.

    python3 bot.py run             работа: слушает кнопки и команды (запускать постоянно, см. README)
    python3 bot.py post            один раз: публикует и закрепляет сообщение с кнопками в #добро-пожаловать
    python3 bot.py edit [id]       заменить текст опубликованного сообщения (кнопки остаются); текст берётся из post.txt,
                                   id — запомненный при `post` (или указанный)
    python3 bot.py check <ник>     проверка GitHub без Discord: Bio, Java-репозитории, коммиты в проекте

Роли через GitHub: участник жмёт «У меня есть GitHub», вводит ник; бот выдаёт код и просит вписать его в Bio профиля GitHub
(так видно, что аккаунт его). Когда код найден, бот выдаёт «Разработчика» (есть собственный Java-репозиторий, не форк) и/или
«Контрибьютора» (есть коммиты в репозиториях владельца проекта).
Остальные роли: кнопка «GitHub нет / другая роль» — выбор роли; «Игрок» выдаётся сразу, остальные — заявкой
в #заявки-на-роли, которую админ одобряет кнопкой.
Токен бота — только из переменной окружения DISCORD_BOT_TOKEN, в файлы он не пишется.
Необязательно GITHUB_TOKEN: без него GitHub пускает 60 запросов в час с одного адреса, с ним — 5000.
"""
import asyncio
import hashlib
import hmac
import logging
import os
import re
import sys
import time
from pathlib import Path

import aiohttp
import discord
from discord import app_commands

GUILD_ID = int(os.environ.get("GUILD_ID", "1571156683621667008"))
WELCOME_CHANNEL_ID = int(os.environ.get("WELCOME_CHANNEL_ID", "1571189898252124333"))
REQUESTS_CHANNEL_ID = int(os.environ.get("REQUESTS_CHANNEL_ID", "1571487120839934062"))
INSTALL_CHANNEL_ID = int(os.environ.get("INSTALL_CHANNEL_ID", "1571454566149394462"))
BUGS_CHANNEL_ID = int(os.environ.get("BUGS_CHANNEL_ID", "1571177339180814346"))
RELEASES_REPO = os.environ.get("RELEASES_REPO", "NoPasaRun/smeshariki-demo")
REPO_OWNER = RELEASES_REPO.split("/")[0]

DEVELOPER_ROLE_ID = int(os.environ.get("DEVELOPER_ROLE_ID", "1571160733478293637"))
CONTRIBUTOR_ROLE_ID = int(os.environ.get("CONTRIBUTOR_ROLE_ID", "1571475457239552015"))
PLAYER_ROLE_ID = int(os.environ.get("PLAYER_ROLE_ID", "1571475453401894912"))
STAFF_ROLE_IDS = {1571157677713653830, 1571468263794278460}  # Админ, Модератор: могут одобрять заявки

# роли, которые можно попросить: (название, id роли, подпись, выдать сразу)
REQUESTABLE = [
    ("Игрок", PLAYER_ROLE_ID, "Играл в демо (выдаётся сразу)", True),
    ("Строитель", 1571160793364434965, "Строю в BuildMod и мире", False),
    ("Сценарист", 1571161060944248952, "Пишу сюжет и диалоги", False),
    ("Постановщик", 1571162670449823877, "Кат-сцены и постановка", False),
    ("Тестировщик", 1571162822371709130, "Проверяю сборки и ищу баги", False),
    ("Дизайнер", 1571162980069023824, "Персонажи, текстуры, интерфейс", False),
]

# голосовые каналы «совместная-игра-N»: вход в канал-кнопку создаёт новый, пустой канал удаляется
HUB_CHANNEL_ID = int(os.environ.get("HUB_CHANNEL_ID", "1571541123716092017"))
GAME_CATEGORY_ID = int(os.environ.get("GAME_CATEGORY_ID", "1571156684275974256"))
GAME_BASE = "совместная-игра"   # сам канал с таким именем остаётся и не удаляется; новые — «совместная-игра-2», «-3»…
GAME_LIMIT = 2                  # мест в новом канале; владелец канала может изменить сам
MAX_TEMP_GAMES = 15
GAME_COOLDOWN = 30              # секунд между созданиями у обычного участника (админы и модераторы без кулдауна)
TEMP_RE = re.compile(r"^%s-(\d+)$" % re.escape(GAME_BASE))


def next_game_number(names) -> int:
    """Наименьший свободный номер от 2 среди имён вида «совместная-игра-N»."""
    used = {int(m.group(1)) for n in names if (m := TEMP_RE.match(n))}
    n = 2
    while n in used:
        n += 1
    return n


COOLDOWN = 30          # секунд между проверками GitHub у одного участника
REQUEST_COOLDOWN = 120  # секунд между заявками у одного участника

LOGIN_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
log = logging.getLogger("rolebot")


class GitHubError(Exception):
    """GitHub не ответил или ограничил запросы."""


def parse_login(text: str) -> str | None:
    """Ник из «name», «@name» или ссылки github.com/name; None, если это не похоже на ник."""
    t = text.strip()
    t = re.sub(r"^https?://(www\.)?github\.com/", "", t, flags=re.I).strip("/@ ")
    t = t.split("/")[0]
    return t if LOGIN_RE.fullmatch(t) else None


def make_code(discord_id: int, login: str) -> str:
    """Код для Bio: зависит от участника Discord, ника GitHub и секрета бота (из токена); хранить его не нужно."""
    key = hashlib.sha256(b"smeshariki-role-bot:" + os.environ["DISCORD_BOT_TOKEN"].encode()).digest()
    mac = hmac.new(key, ("%d:%s" % (discord_id, login.lower())).encode(), hashlib.sha256).hexdigest()
    return "smeshariki-" + mac[:10]


async def _get(session: aiohttp.ClientSession, url: str, params: dict | None = None):
    """Ответ GitHub как JSON; None, если 404."""
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "smeshariki-role-bot"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        async with session.get(url, params=params, headers=headers, timeout=aiohttp.ClientTimeout(total=15)) as r:
            if r.status == 404:
                return None
            if r.status in (403, 429):
                raise GitHubError("лимит запросов GitHub")
            if r.status != 200:
                raise GitHubError("GitHub ответил %d" % r.status)
            return await r.json()
    except (aiohttp.ClientError, asyncio.TimeoutError) as e:
        raise GitHubError(str(e)) from e


async def github_info(session: aiohttp.ClientSession, login: str) -> tuple[str, list[str], int] | None:
    """(Bio, имена собственных Java-репозиториев без форков, число коммитов в репозиториях владельца проекта);
    None — такого пользователя нет."""
    user = await _get(session, "https://api.github.com/users/%s" % login)
    if user is None:
        return None
    repos = await _get(session, "https://api.github.com/users/%s/repos" % login,
                       {"per_page": "100", "type": "owner", "sort": "pushed"}) or []
    java = [x["name"] for x in repos if x.get("language") == "Java" and not x.get("fork")]
    commits = 0
    try:  # поиск коммитов ограничен строже; сбой не должен ломать остальное
        found = await _get(session, "https://api.github.com/search/commits",
                           {"q": "author:%s user:%s" % (login, REPO_OWNER), "per_page": "1"})
        commits = int((found or {}).get("total_count", 0))
    except GitHubError as e:
        log.warning("поиск коммитов %s не удался: %s", login, e)
    return user.get("bio") or "", java, commits


_last_try: dict[int, float] = {}
_last_request: dict[int, float] = {}
_last_game: dict[int, float] = {}


def _left(table: dict[int, float], key: int, cooldown: int) -> int:
    """Сколько секунд ещё ждать; 0 — можно; при 0 запоминает попытку."""
    wait = cooldown - (time.monotonic() - table.get(key, -1e9))
    if wait > 0:
        return int(wait) + 1
    table[key] = time.monotonic()
    return 0


class GithubModal(discord.ui.Modal, title="Проверка GitHub"):
    nick = discord.ui.TextInput(label="Ваш ник на GitHub", placeholder="например, NoPasaRun", min_length=1, max_length=80)

    async def on_submit(self, interaction: discord.Interaction):
        await interaction.response.defer(ephemeral=True)
        member = interaction.user
        guild = interaction.guild
        dev = guild.get_role(DEVELOPER_ROLE_ID) if guild else None
        contrib = guild.get_role(CONTRIBUTOR_ROLE_ID) if guild else None
        if not isinstance(member, discord.Member) or dev is None or contrib is None:
            await interaction.followup.send("Не получилось: роль не найдена. Напишите админу.", ephemeral=True)
            return
        wait = _left(_last_try, member.id, COOLDOWN)
        if wait:
            await interaction.followup.send("Подождите %d с и попробуйте снова." % wait, ephemeral=True)
            return

        login = parse_login(self.nick.value)
        if login is None:
            await interaction.followup.send("Это не похоже на ник GitHub. Нужен только ник, например `NoPasaRun`.", ephemeral=True)
            return
        try:
            async with aiohttp.ClientSession() as session:
                info = await github_info(session, login)
        except GitHubError as e:
            log.warning("GitHub недоступен для %s: %s", login, e)
            await interaction.followup.send("GitHub сейчас не отвечает, попробуйте через пару минут.", ephemeral=True)
            return
        if info is None:
            await interaction.followup.send("Пользователя `%s` на GitHub нет. Проверьте ник." % login, ephemeral=True)
            return
        bio, repos, commits = info
        code = make_code(member.id, login)
        if code not in bio:
            await interaction.followup.send(
                "Чтобы убедиться, что `%s` ваш аккаунт, впишите этот код в поле **Bio** профиля GitHub "
                "(github.com → Settings → Public profile → Bio):\n`%s`\n"
                "Сохраните, подождите около минуты (GitHub кэширует профиль), нажмите кнопку в добро-пожаловать "
                "и введите тот же ник. Код после проверки можно удалить." % (login, code), ephemeral=True)
            return

        earned = []
        if repos:
            earned.append(dev)
        if commits > 0:
            earned.append(contrib)
        if not earned:
            await interaction.followup.send(
                "У `%s` нет собственных публичных репозиториев на Java (форки не считаются) и коммитов в проекте. "
                "Нужна другая роль? Нажмите «GitHub нет / другая роль»." % login, ephemeral=True)
            return
        new = [r for r in earned if r not in member.roles]
        if not new:
            await interaction.followup.send("У вас уже есть все роли, которые вам положены: %s. 🙂" % ", ".join(r.name for r in earned), ephemeral=True)
            return
        try:
            await member.add_roles(*new, reason="GitHub %s: Java-репозиториев %d, коммитов %d" % (login, len(repos), commits))
        except discord.HTTPException as e:
            log.error("не выдались роли %s: %s", member.id, e)
            await interaction.followup.send("Проверка прошла, но выдать роль не вышло. Напишите админу.", ephemeral=True)
            return
        log.info("роли выданы: discord=%s github=%s роли=%s java=%d коммитов=%d", member.id, login, [r.name for r in new], len(repos), commits)
        await interaction.followup.send("Готово! Вам выдано: %s 🎉" % ", ".join("«%s»" % r.name for r in new), ephemeral=True)


class RoleSelect(discord.ui.Select):
    def __init__(self):
        super().__init__(placeholder="Выберите роль", min_values=1, max_values=1,
                         options=[discord.SelectOption(label=n, value=str(rid), description=d) for n, rid, d, _ in REQUESTABLE])

    async def callback(self, interaction: discord.Interaction):
        rid = int(self.values[0])
        name, _, _, auto = next(x for x in REQUESTABLE if x[1] == rid)
        member, guild = interaction.user, interaction.guild
        role = guild.get_role(rid) if guild else None
        if not isinstance(member, discord.Member) or role is None:
            await interaction.response.send_message("Не получилось: роль не найдена. Напишите админу.", ephemeral=True)
            return
        if role in member.roles:
            await interaction.response.send_message("У вас уже есть роль «%s». 🙂" % name, ephemeral=True)
            return
        if auto:
            try:
                await member.add_roles(role, reason="самостоятельный выбор")
            except discord.HTTPException as e:
                log.error("не выдалась роль %s: %s", member.id, e)
                await interaction.response.send_message("Выдать роль не вышло. Напишите админу.", ephemeral=True)
                return
            await interaction.response.send_message("Вам выдана роль «%s». 🎉" % name, ephemeral=True)
            return
        wait = _left(_last_request, member.id, REQUEST_COOLDOWN)
        if wait:
            await interaction.response.send_message("Подождите %d с перед новой заявкой." % wait, ephemeral=True)
            return
        channel = guild.get_channel(REQUESTS_CHANNEL_ID) or await guild.fetch_channel(REQUESTS_CHANNEL_ID)
        view = discord.ui.View(timeout=None)
        view.add_item(RequestButton("ok", member.id, rid))
        view.add_item(RequestButton("no", member.id, rid))
        await channel.send("📝 Заявка: %s просит роль %s." % (member.mention, role.mention), view=view,
                           allowed_mentions=discord.AllowedMentions.none())
        log.info("заявка: discord=%s роль=%s", member.id, name)
        await interaction.response.send_message("Заявка на роль «%s» отправлена админам. Решение придёт сюда или в личные сообщения." % name, ephemeral=True)


class RoleChoiceView(discord.ui.View):
    def __init__(self):
        super().__init__(timeout=300)
        self.add_item(RoleSelect())


class RequestButton(discord.ui.DynamicItem[discord.ui.Button], template=r"req:(?P<action>ok|no):(?P<uid>\d+):(?P<rid>\d+)"):
    """Кнопка под заявкой; всё нужное зашито в custom_id, поэтому работает и после перезапуска бота."""

    def __init__(self, action: str, uid: int, rid: int):
        ok = action == "ok"
        super().__init__(discord.ui.Button(label="Одобрить" if ok else "Отклонить",
                                           style=discord.ButtonStyle.success if ok else discord.ButtonStyle.danger,
                                           custom_id="req:%s:%d:%d" % (action, uid, rid)))
        self.action, self.uid, self.rid = action, uid, rid

    @classmethod
    async def from_custom_id(cls, interaction: discord.Interaction, item: discord.ui.Button, match: re.Match, /):
        return cls(match["action"], int(match["uid"]), int(match["rid"]))

    async def callback(self, interaction: discord.Interaction):
        staff = interaction.user
        if not isinstance(staff, discord.Member) or not any(r.id in STAFF_ROLE_IDS for r in staff.roles):
            await interaction.response.send_message("Заявки решают только админы и модераторы.", ephemeral=True)
            return
        guild = interaction.guild
        role = guild.get_role(self.rid)
        if role is None or self.rid not in {x[1] for x in REQUESTABLE}:
            await interaction.response.send_message("Эта роль недоступна для заявок.", ephemeral=True)
            return
        member = guild.get_member(self.uid)
        if member is None:
            try:
                member = await guild.fetch_member(self.uid)
            except discord.NotFound:
                await interaction.response.edit_message(content="⚠️ Участник вышел с сервера.", view=None)
                return
        if self.action == "ok":
            try:
                await member.add_roles(role, reason="заявка одобрена: %s" % staff)
            except discord.HTTPException as e:
                log.error("не выдалась роль по заявке: %s", e)
                await interaction.response.send_message("Выдать роль не вышло: %s" % e, ephemeral=True)
                return
            result, dm = "✅ Одобрено (%s)" % staff.display_name, "Ваша заявка на роль «%s» одобрена! 🎉" % role.name
        else:
            result, dm = "❌ Отклонено (%s)" % staff.display_name, "Заявка на роль «%s» отклонена. Если это ошибка, напишите админу." % role.name
        log.info("заявка discord=%s роль=%s: %s (%s)", self.uid, role.name, self.action, staff.id)
        await interaction.response.edit_message(content="📝 %s → %s. %s" % (member.mention, role.mention, result), view=None)
        try:
            await member.send(dm)
        except discord.HTTPException:
            pass  # личные сообщения закрыты


class RoleView(discord.ui.View):
    def __init__(self):
        super().__init__(timeout=None)  # постоянные кнопки: custom_id фиксированы, переживают перезапуск

    @discord.ui.button(label="У меня есть GitHub", style=discord.ButtonStyle.success, custom_id="smeshariki:github_yes")
    async def yes(self, interaction: discord.Interaction, button: discord.ui.Button):
        await interaction.response.send_modal(GithubModal())

    @discord.ui.button(label="GitHub нет / другая роль", style=discord.ButtonStyle.secondary, custom_id="smeshariki:github_no")
    async def no(self, interaction: discord.Interaction, button: discord.ui.Button):
        await interaction.response.send_message("Какая роль вам нужна? «Игрок» выдаётся сразу, остальные одобряют админы.",
                                                view=RoleChoiceView(), ephemeral=True)


DEFAULT_POST_TEXT = """**Выберите роль** 🎭

**Есть GitHub?** Нажмите **«У меня есть GitHub»** и введите ник. Бот выдаст короткий код: впишите его в поле Bio профиля GitHub, \
чтобы подтвердить, что аккаунт ваш, и нажмите кнопку снова. Если у вас есть свой проект на Java (форки не считаются), \
вы получите роль **Разработчик** и откроются рабочие каналы. Если у вас есть коммиты в репозиториях проекта, получите **Контрибьютор**.

**Нет GitHub или нужна другая роль?** Нажмите **«GitHub нет / другая роль»**: «Игрок» выдаётся сразу, \
строитель, сценарист, постановщик, тестировщик и дизайнер одобряют админы."""

HERE = Path(__file__).resolve().parent
POST_FILE = Path(os.environ.get("POST_TEXT_FILE", HERE / "post.txt"))     # текст сообщения; правится руками
POST_ID_FILE = HERE / "post_message_id.txt"                              # id опубликованного сообщения (пишет `post`)


def post_text() -> str:
    """Текст сообщения с кнопками: из post.txt, если файл есть, иначе встроенный; лимит Discord — 2000 знаков."""
    text = POST_FILE.read_text(encoding="utf-8").strip() if POST_FILE.exists() else DEFAULT_POST_TEXT
    if not text or len(text) > 2000:
        sys.exit("текст пустой или длиннее 2000 знаков (%d): %s" % (len(text), POST_FILE))
    return text


_release_cache: dict = {"t": 0.0, "data": None}


async def latest_release():
    """Последний релиз репозитория демо (кэш на минуту, чтобы не тратить лимит GitHub)."""
    if time.monotonic() - _release_cache["t"] < 60 and _release_cache["data"] is not None:
        return _release_cache["data"]
    async with aiohttp.ClientSession() as session:
        data = await _get(session, "https://api.github.com/repos/%s/releases/latest" % RELEASES_REPO)
    _release_cache.update(t=time.monotonic(), data=data)
    return data


class Bot(discord.Client):
    def __init__(self, mode: str, arg: str | None = None):
        super().__init__(intents=discord.Intents.default())  # привилегированные намерения не нужны
        self.mode, self.arg = mode, arg
        self._game_lock = asyncio.Lock()
        self.tree = app_commands.CommandTree(self)
        guild = discord.Object(GUILD_ID)

        @self.tree.command(name="версия", description="Последняя версия демо и ссылка на установку", guild=guild)
        async def version(interaction: discord.Interaction):
            await interaction.response.defer()
            try:
                rel = await latest_release()
            except GitHubError as e:
                log.warning("релиз не получен: %s", e)
                rel = None
            if not rel:
                await interaction.followup.send("Не получилось узнать версию, попробуйте позже: https://github.com/%s/releases/latest" % RELEASES_REPO)
                return
            body = (rel.get("body") or "").strip()
            emb = discord.Embed(title="Последний релиз: %s" % rel["tag_name"], url=rel["html_url"],
                                description=body[:500] + ("…" if len(body) > 500 else ""), colour=discord.Colour.green())
            emb.add_field(name="Файлов", value=str(len(rel.get("assets", []))))
            emb.add_field(name="Как установить", value="<#%d>" % INSTALL_CHANNEL_ID)
            await interaction.followup.send(embed=emb)

        @self.tree.command(name="установка", description="Как установить демо", guild=guild)
        async def install(interaction: discord.Interaction):
            await interaction.response.send_message(
                "Нужны Minecraft 26.2, Java 25, Fabric и Fabric API. Положите в `mods` Fabric API и `smesharikimod-1.0.0.jar` "
                "из [последнего релиза](https://github.com/%s/releases/latest), запустите игру и нажмите «Скачать и установить». "
                "Подробно: <#%d>. Баги: <#%d>." % (RELEASES_REPO, INSTALL_CHANNEL_ID, BUGS_CHANNEL_ID), ephemeral=True)

    async def setup_hook(self):
        self.add_view(RoleView())
        self.add_dynamic_items(RequestButton)
        try:
            synced = await self.tree.sync(guild=discord.Object(GUILD_ID))
            log.info("команды: %s", ", ".join("/" + c.name for c in synced))
        except discord.HTTPException as e:
            log.warning("команды не зарегистрированы (нужен scope applications.commands у приглашения бота?): %s", e)

    @staticmethod
    def _is_temp_game(ch) -> bool:
        return isinstance(ch, discord.VoiceChannel) and ch.category_id == GAME_CATEGORY_ID and bool(TEMP_RE.match(ch.name))

    async def _create_game(self, member: discord.Member, guild: discord.Guild):
        async with self._game_lock:
            temps = [c for c in guild.voice_channels if self._is_temp_game(c)]
            mine = next((c for c in temps if member in c.overwrites and not c.members), None)
            if mine:  # прежний канал участника ещё не удалён (пустой): вернуть в него, а не плодить новый
                try:
                    await member.move_to(mine)
                    log.info("участник %s возвращён в %s", member.id, mine.name)
                    return
                except discord.HTTPException:
                    pass
            staff = member.guild_permissions.administrator or any(r.id in STAFF_ROLE_IDS for r in member.roles)
            wait = 0 if staff else _left(_last_game, member.id, GAME_COOLDOWN)  # админы и модераторы без кулдауна
            if wait or len(temps) >= MAX_TEMP_GAMES:
                log.info("новая игра не создана для %s (ждать %d с, временных каналов %d)", member.id, wait, len(temps))
                await member.move_to(None, reason="лимит создания каналов")
                return
            name = "%s-%d" % (GAME_BASE, next_game_number(c.name for c in temps))
            ch = await guild.create_voice_channel(
                name, category=guild.get_channel(GAME_CATEGORY_ID), user_limit=GAME_LIMIT,
                overwrites={member: discord.PermissionOverwrite(view_channel=True, connect=True, manage_channels=True)},
                reason="совместная игра: %s" % member)
            try:
                await member.move_to(ch)
            except discord.HTTPException:  # участник успел выйти: канал никому не нужен
                await ch.delete(reason="создатель не подключился")
                return
            log.info("создан %s для %s", name, member.id)

    async def on_voice_state_update(self, member: discord.Member, before: discord.VoiceState, after: discord.VoiceState):
        if self.mode != "run":
            return
        try:
            if after.channel and after.channel.id == HUB_CHANNEL_ID and before.channel != after.channel:
                await self._create_game(member, member.guild)
            ch = before.channel
            if ch and ch != after.channel and self._is_temp_game(ch) and not ch.members:
                await asyncio.sleep(2)  # участник мог тут же вернуться
                ch = member.guild.get_channel(ch.id)
                if ch and not ch.members:
                    await ch.delete(reason="временная игра опустела")
                    log.info("удалён пустой канал %s", ch.name)
        except discord.HTTPException as e:
            log.error("голосовые каналы: %s", e)

    async def on_ready(self):
        log.info("Бот запущен: %s", self.user)
        if self.mode == "run":  # после перезапуска: убрать временные каналы, которые опустели, пока бота не было
            for guild in self.guilds:
                for ch in guild.voice_channels:
                    if self._is_temp_game(ch) and not ch.members:
                        try:
                            await ch.delete(reason="пустой временный канал после перезапуска бота")
                            log.info("удалён пустой канал %s", ch.name)
                        except discord.HTTPException as e:
                            log.warning("не удалился %s: %s", ch.name, e)
        if self.mode in ("post", "edit"):
            channel = self.get_channel(WELCOME_CHANNEL_ID) or await self.fetch_channel(WELCOME_CHANNEL_ID)
            if self.mode == "post":
                msg = await channel.send(post_text(), view=RoleView(), allowed_mentions=discord.AllowedMentions.none())
                POST_ID_FILE.write_text(str(msg.id))
                try:
                    await msg.pin()
                except discord.HTTPException as e:
                    log.warning("не закрепилось: %s", e)
                log.info("сообщение опубликовано: %s", msg.jump_url)
            else:
                msg = await channel.fetch_message(int(self.arg or POST_ID_FILE.read_text().strip()))
                await msg.edit(content=post_text())
                log.info("сообщение изменено: %s", msg.jump_url)
            await self.close()


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "check" and len(sys.argv) > 2:
        login = parse_login(sys.argv[2])
        if not login:
            sys.exit("не ник GitHub")

        async def run():
            async with aiohttp.ClientSession() as s:
                print(await github_info(s, login))
        asyncio.run(run())
        return
    if mode not in ("run", "post", "edit"):
        sys.exit(__doc__)
    token = os.environ.get("DISCORD_BOT_TOKEN")
    if not token:
        sys.exit("задайте DISCORD_BOT_TOKEN")
    Bot(mode, sys.argv[2] if len(sys.argv) > 2 else None).run(token, log_handler=None)


if __name__ == "__main__":
    main()
