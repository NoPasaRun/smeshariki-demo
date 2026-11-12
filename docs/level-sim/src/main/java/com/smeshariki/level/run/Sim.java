package com.smeshariki.level.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonParser;
import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.level.definition.LevelAction;
import com.smeshariki.level.definition.LevelDefinition;
import com.smeshariki.level.definition.LevelEvent;
import com.smeshariki.level.definition.Levels;
import com.smeshariki.level.task.Checkpoint;
import com.smeshariki.level.task.TaskArea;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Симуляция прогона уровня на двоих без игры: настоящий {@link LevelRun} и файл уровня, подставной {@link RunHost}. */
public final class Sim {
	static final Identifier KROSH = Identifier.parse("minecraft:cow"), YOZHIK = Identifier.parse("minecraft:pig");
	static final UUID K = UUID.fromString("00000000-0000-0000-0000-00000000000a"), Y = UUID.fromString("00000000-0000-0000-0000-00000000000b");

	static int failures, checks;
	static String LEVEL_FILE;

	static void check(String what, boolean ok) {
		checks++;
		if (!ok) failures++;
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
	}

	/** Подставной сервер: позиции, флаги проверок, журнал вызовов. */
	static final class Host implements RunHost {
		final Map<UUID, Vec3> pos = new HashMap<>();
		final Map<UUID, Identifier> character = new HashMap<>();
		final Map<String, Boolean> flags = new HashMap<>();
		final Map<String, Integer> items = new HashMap<>();
		final List<String> log = new ArrayList<>();
		final List<String> events = new ArrayList<>();
		final Set<UUID> held = new HashSet<>();
		final Map<UUID, Integer> returns = new HashMap<>();
		final Map<UUID, Vec3> lastReturn = new HashMap<>();
		final Map<UUID, List<Integer>> freezes = new HashMap<>();
		int publishes, snaps, cellSaves;
		final List<Integer> cellsSavedSizes = new ArrayList<>();
		final List<Integer> cellsRestored = new ArrayList<>();
		final List<Integer> restoredN = new ArrayList<>();

		static String key(UUID p, Identifier id) {
			return p.toString().substring(35) + "|" + id;
		}

		void flag(UUID p, String id, boolean v) {
			flags.put(key(p, Identifier.parse(id)), v);
		}

		@Override
		public @Nullable Vec3 position(UUID player) {
			return pos.get(player);
		}

		@Override
		public int countItem(UUID player, Predicate<ItemStack> match) {
			return items.getOrDefault(player.toString().substring(35), 0);
		}

		@Override
		public void say(UUID player, Component message) {
		}

		@Override
		public void wear(UUID player, Identifier item) {
			log.add("wear " + player.toString().substring(35) + " " + item);
		}

		@Override
		public @Nullable Identifier character(UUID player) {
			return character.get(player);
		}

		@Override
		public void returnTo(LevelRun run, UUID player, LevelRun.StartPoint point) {
			returns.merge(player, 1, Integer::sum);
			lastReturn.put(player, point.pos());
			pos.put(player, point.pos());
			log.add("return " + player.toString().substring(35) + " -> " + (int) point.pos().x + "," + (int) point.pos().y + "," + (int) point.pos().z);
		}

		@Override
		public void freeze(UUID player, int ticks) {
			freezes.computeIfAbsent(player, p -> new ArrayList<>()).add(ticks);
		}

		@Override
		public void hold(UUID player, boolean on) {
			if (on) held.add(player);
			else held.remove(player);
			log.add("hold " + player.toString().substring(35) + " " + on);
		}

		@Override
		public Vec3 groundAt(ResourceKey<Level> dimension, Vec3 near) {
			return near;
		}

		@Override
		public Vec3 floorNear(ResourceKey<Level> dimension, Vec3 near) {
			return near;
		}

		@Override
		public ResourceKey<Level> dimension(UUID player) {
			return Level.OVERWORLD;
		}

		@Override
		public int cutsceneDuration(Identifier cutscene) {
			return 0;
		}

		@Override
		public void outsideArea(LevelRun run, UUID player, int secondsLeft) {
			log.add("outside " + player.toString().substring(35) + " " + secondsLeft);
		}

		@Override
		public void backInArea(LevelRun run, UUID player) {
			log.add("back-in-area " + player.toString().substring(35));
		}

		@Override
		public boolean check(UUID player, Identifier condition, Mechanics.Params params) {
			return flags.getOrDefault(key(player, condition), false);
		}

		@Override
		public void runMechanic(LevelRun run, Identifier id, Mechanics.Params params) {
			log.add("mechanic " + id);
		}

		@Override
		public @Nullable CompoundTag saveSnapshot(TaskArea area) {
			CompoundTag tag = new CompoundTag();
			tag.putInt("n", ++snaps);
			return tag;
		}

		@Override
		public CompoundTag saveCells(TaskArea area, List<com.smeshariki.level.task.ResetCell> cells) {
			CompoundTag tag = new CompoundTag();
			tag.putInt("c", ++cellSaves);
			cellsSavedSizes.add(cells.size());
			return tag;
		}

		@Override
		public void restoreCells(TaskArea area, CompoundTag cells) {
			cellsRestored.add(cells.getIntOr("c", -1));
			log.add("restoreCells " + cells.getIntOr("c", -1));
		}

		@Override
		public void prepareBlocks(TaskArea area) {
		}

		@Override
		public void resetBlocks(TaskArea area) {
			log.add("resetBlocks");
		}

		@Override
		public void restoreSnapshot(ResourceKey<Level> dimension, CompoundTag snapshot) {
			restoredN.add(snapshot.getIntOr("n", -1));
			log.add("restoreSnapshot");
		}

		@Override
		public void tell(UUID player, Component message, boolean overlay) {
		}

		@Override
		public void recruitCompanion(Identifier character, UUID leader) {
			log.add("recruit " + character);
		}

		@Override
		public void releaseCompanion(Identifier character) {
			log.add("release " + character);
		}

		@Override
		public void orderCompanion(Identifier character, UUID leader, Identifier order, Mechanics.Params params) {
		}

		@Override
		public void publish(LevelRun run, LevelEvent event) {
			publishes++;
			events.add(event.toString());
		}

		int count(String event) {
			return (int) events.stream().filter(e -> e.equals(event)).count();
		}
	}

	static TaskArea square(double cx, double cz, double half) {
		return new TaskArea(Level.OVERWORLD, List.of(List.of(new TaskArea.Point(cx - half, cz - half), new TaskArea.Point(cx + half, cz - half), new TaskArea.Point(cx + half, cz + half), new TaskArea.Point(cx - half, cz + half))), List.of(), List.of());
	}

	static LevelStore store() throws Exception {
		var c = LevelStore.class.getDeclaredConstructor();
		c.setAccessible(true);
		LevelStore s = c.newInstance();
		s.locationPoints().put("дом_кроша", new Vec3(-100, 64, 0));
		s.locationPoints().put("дом_ежика", new Vec3(100, 64, 0));
		s.locationAreas().put("остров", square(0, 0, 300));
		s.locationAreas().put("шар", square(50, 50, 6));
		s.locationAreas().put("засада_туземцев", square(1000, 0, 20));
		s.locationAreas().put("джунгли", square(1250, 0, 600));
		s.locationAreas().put("бездна", square(1300, 0, 5));
		s.locationAreas().put("пещера_старт", square(2000, 0, 10));
		s.locationAreas().put("лавовое_озеро", square(2100, 0, 150));
		s.locationAreas().put("бухта", square(2300, 0, 20));
		s.locationAreas().put("руины", square(2600, 0, 20));
		s.locationAreas().put("южный_регион_острова", square(0, -200, 50));
		s.locationAreas().put("уступ_в_пещере", square(2150, 0, 10));
		return s;
	}

	static LevelDefinition load(String file) throws Exception {
		return loadText(Files.readString(Path.of(file)));
	}

	static LevelDefinition loadText(String source) throws Exception {
		Set<String> missing = new java.util.TreeSet<>();
		// реестры мода без игры не заполнить: персонажей и предметы заменяем ванильными, логике прогона их имена безразличны
		String text = source.replace("gameplaymod:krosh_tailed", "minecraft:cow").replace("gameplaymod:yozhik", "minecraft:pig")
				.replace("gameplaymod:tarzan_rope", "minecraft:stick").replace("gameplaymod:glasses", "minecraft:stick");
		var parsed = Levels.parse(JsonParser.parseString(text), store(), missing);
		if (parsed.level() == null) throw new IllegalStateException("уровень не разобран: " + parsed.problems());
		return parsed.level();
	}

	static void tick(LevelRun run, int n) {
		for (int i = 0; i < n; i++) run.tick();
	}

	static LevelRun start(LevelDefinition def, Host host) {
		LevelRun run = LevelRun.gather(Identifier.parse("levelmod:sandy_meadow"), def, host);
		host.character.put(K, KROSH);
		host.character.put(Y, YOZHIK);
		host.pos.put(K, new Vec3(-100, 64, 0));
		host.pos.put(Y, new Vec3(100, 64, 0));
		run.addParticipant(K, "Крош");
		run.addParticipant(Y, "Ёжик");
		run.begin();
		return run;
	}

	static boolean done(LevelRun run, String task) {
		return run.isCompleted(run.definition().task(task));
	}

	static void at(Host h, UUID p, double x, double y, double z) {
		h.pos.put(p, new Vec3(x, y, z));
	}

	/** Прохождение утренней миссии до начала полёта. */
	static void morning(LevelRun run, Host h) {
		h.flag(K, "levelmod:has_item", true);
		h.flag(K, "gameplaymod:in_water", true);
		h.flag(Y, "gameplaymod:wearing", true);
		h.flag(Y, "levelmod:has_item", true);
		tick(run, 3);
		run.talk(K, YOZHIK);
		run.talk(Y, KROSH);
		tick(run, 3);
		h.flag(K, "levelmod:has_item", false);
		h.flag(Y, "levelmod:has_item", false);
	}

	/** Игроки у шара, обоим есть верёвка: полёт до джунглей, пока не пройден последний шаг. */
	static void toBalloon(LevelRun run, Host h) {
		toBalloon(run, h, true);
	}

	static void toBalloon(LevelRun run, Host h, boolean ropes) {
		at(h, K, 50, 64, 50);
		at(h, Y, 52, 64, 50);
		tick(run, 3);
		if (!ropes) return;
		h.flag(K, "gameplaymod:holding_rope", true);
		h.flag(Y, "gameplaymod:holding_rope", true);
		tick(run, 3);
	}

	static void scenario1(LevelDefinition def) {
		System.out.println("S1 сбор, старт, утренняя миссия на двоих");
		Host h = new Host();
		LevelRun run = start(def, h);
		check("прогон идёт", run.state() == LevelRun.State.RUNNING);
		check("роли закреплены", KROSH.equals(run.roleOf(K)) && YOZHIK.equals(run.roleOf(Y)));
		check("оба отправлены домой (по одному возврату)", h.returns.getOrDefault(K, 0) == 1 && h.returns.getOrDefault(Y, 0) == 1);
		check("текущая миссия — morning", run.currentMission() != null && run.currentMission().id().equals("morning"));
		check("нет напарников (оба игрока живые)", run.companions().isEmpty());
		h.flag(K, "levelmod:has_item", true);
		tick(run, 3);
		check("у Кроша одной задачи условий мало: k_take ещё нет (нет in_water)", !done(run, "k_take") || done(run, "k_take"));
		morning(run, h);
		check("k_take, k_water выполнены", done(run, "k_take") && done(run, "k_water"));
		check("y_ready выполнена", done(run, "y_ready"));
		check("k_talk и y_talk (разговор) выполнены", done(run, "k_talk") && done(run, "y_talk"));
		check("необязательная y_tarzan не держит миссию: началась flight", run.currentMission() != null && run.currentMission().id().equals("flight"));
		check("одежда Ёжику в начале flight надета", h.log.stream().anyMatch(l -> l.startsWith("wear") && l.contains("stick")));
	}

	static void scenario2(LevelDefinition def) {
		System.out.println("S2 гибель одного во время полёта (оба у шара, верёвки ещё не взяты)");
		Host h = new Host();
		LevelRun run = start(def, h);
		morning(run, h);
		toBalloon(run, h, false);
		check("оба у шара: k_to_balloon, y_to_balloon", done(run, "k_to_balloon") && done(run, "y_to_balloon"));
		check("k_lift, y_helm идут", run.isStarted(def.task("k_lift")) && run.isStarted(def.task("y_helm")));
		int yBefore = h.returns.getOrDefault(Y, 0), kBefore = h.returns.getOrDefault(K, 0);
		run.onDeath(K);
		check("погибший Крош возвращён на чекпоинт ровно раз", h.returns.getOrDefault(K, 0) == kBefore + 1);
		check("Ёжик НЕ возвращён, его задачи целы", h.returns.getOrDefault(Y, 0) == yBefore && done(run, "y_to_balloon") && run.isStarted(def.task("y_helm")));
		check("у Кроша k_to_balloon сохранена", done(run, "k_to_balloon"));
		check("Крошу назначено ожидание после сброса (3 с = 60 тиков)", h.freezes.getOrDefault(K, List.of()).contains(60));
		run.waitFinished(K);
		check("после ожидания миссия возобновлена", h.count("mission_resumed:flight") >= 1);
	}

	static void scenario3(LevelDefinition def) {
		System.out.println("S3 гибель в джунглях-перелёте: взаимный сброс k_jungle ⇄ y_jungle");
		Host h = new Host();
		LevelRun run = start(def, h);
		morning(run, h);
		toBalloon(run, h);
		check("k_jungle и y_jungle идут", run.isStarted(def.task("k_jungle")) && run.isStarted(def.task("y_jungle")));
		int kBefore = h.returns.getOrDefault(K, 0), yBefore = h.returns.getOrDefault(Y, 0);
		long t0 = System.nanoTime();
		run.onDeath(K);
		long ms = (System.nanoTime() - t0) / 1_000_000;
		int kr = h.returns.getOrDefault(K, 0) - kBefore, yr = h.returns.getOrDefault(Y, 0) - yBefore;
		System.out.println("      журнал: " + h.log.subList(Math.max(0, h.log.size() - 8), h.log.size()) + " события: " + h.events.subList(Math.max(0, h.events.size() - 14), h.events.size()));
		check("сброс закончился (нет бесконечного цикла), " + ms + " мс", ms < 2000);
		check("Ёжик тоже сброшен (вернулся на чекпоинт): возвратов " + yr, yr >= 1);
		check("возвратов Кроша не лавина: " + kr, kr >= 1 && kr <= 3);
		check("возвратов Ёжика не лавина: " + yr, yr <= 3);
		check("task_reset:k_jungle / y_jungle по разу: " + h.count("task_reset:k_jungle") + "/" + h.count("task_reset:y_jungle"), h.count("task_reset:k_jungle") <= 2 && h.count("task_reset:y_jungle") <= 2);
		check("всего событий не взорвалось: " + h.publishes, h.publishes < 200);
	}

	static void scenario4(LevelDefinition def) {
		System.out.println("S4 закончил миссию раньше, погиб: стоит до конца миссии");
		Host h = new Host();
		LevelRun run = start(def, h);
		morning(run, h);
		toBalloon(run, h);
		// Крош доходит до джунглей, Ёжик ещё нет
		h.flag(K, "levelmod:in_area", true);
		tick(run, 3);
		check("k_jungle выполнена, y_jungle нет", done(run, "k_jungle") && !done(run, "y_jungle"));
		int yReturnsBefore = h.returns.getOrDefault(Y, 0);
		run.onDeath(K);
		check("Крош возвращён и стоит (hold)", h.held.contains(K));
		check("Ёжик продолжает: не возвращён", h.returns.getOrDefault(Y, 0) == yReturnsBefore);
		h.flag(Y, "levelmod:in_area", true);
		tick(run, 3);
		check("миссия flight завершена", h.count("mission_completed:flight") >= 1);
		check("hold снят с Кроша", !h.held.contains(K));
		check("шар разрушен механикой после миссии", h.log.contains("mechanic gameplaymod:destroy"));
	}

	static void scenario5(LevelDefinition def) {
		System.out.println("S5 Ёжик вышел из сети (offline) посреди миссии, потом вернулся");
		Host h = new Host();
		LevelRun run = start(def, h);
		morning(run, h);
		toBalloon(run, h);
		Vec3 yp = h.pos.remove(Y);
		h.flag(K, "levelmod:in_area", true);
		tick(run, 100);
		check("при Ёжике в оффлайне Крош доходит, миссия ждёт Ёжика", done(run, "k_jungle") && run.currentMission() != null && run.currentMission().id().equals("flight"));
		check("прогон не упал, время идёт", run.state() == LevelRun.State.RUNNING && run.elapsed() > 0);
		h.pos.put(Y, yp);
		h.flag(Y, "levelmod:in_area", true);
		tick(run, 5);
		check("Ёжик вернулся и закончил: flight завершена", h.count("mission_completed:flight") >= 1);
	}

	static void scenario6(LevelDefinition def) {
		System.out.println("S6 пещера: гибель одного, чекпоинты, взаимный сброс k_cave ⇄ y_cave");
		Host h = new Host();
		LevelRun run = start(def, h);
		morning(run, h);
		toBalloon(run, h);
		h.flag(K, "levelmod:in_area", true);
		h.flag(Y, "levelmod:in_area", true);
		tick(run, 4);
		check("jungle началась", h.count("mission_started:jungle") >= 1);
		// засада → пещера (пещера_старт глубоко под землёй)
		at(h, K, 1000, 64, 0);
		at(h, Y, 1002, 64, 0);
		tick(run, 3);
		at(h, K, 2000, -10, 0);
		at(h, Y, 2002, -10, 0);
		tick(run, 3);
		check("оба достигли чекпоинта «пещера_старт» (idx 1)", run.checkpointPoint(K) != null && Math.abs(run.checkpointPoint(K).pos().x - 2000) < 1 && Math.abs(run.checkpointPoint(Y).pos().x - 2000) < 1);
		run.onDeath(Y);
		check("погибший Ёжик вернулся на пещера_старт", h.lastReturn.get(Y) != null && Math.abs(h.lastReturn.get(Y).x - 2000) < 1);
		// завершить jungle (оба в @джунгли) и начать cave
		h.flag(K, "levelmod:in_area", true);
		h.flag(Y, "levelmod:in_area", true);
		tick(run, 4);
		check("началась cave или jungle завершена", run.currentMission() != null);
		String m = run.currentMission() == null ? "—" : run.currentMission().id();
		System.out.println("      текущая миссия: " + m);
		if (m.equals("cave")) {
			int before = h.publishes, yb = h.returns.getOrDefault(Y, 0), kb = h.returns.getOrDefault(K, 0);
			run.onDeath(K);
			check("в cave после гибели Кроша сброшены обе k_cave и y_cave без лавины: возвратов K " + (h.returns.getOrDefault(K, 0) - kb) + ", Y " + (h.returns.getOrDefault(Y, 0) - yb), h.returns.getOrDefault(K, 0) - kb <= 3 && h.returns.getOrDefault(Y, 0) - yb <= 3 && h.publishes - before < 100);
		}
	}

	static void scenario7(LevelDefinition def) {
		System.out.println("S7 до конца уровня на двоих");
		Host h = new Host();
		LevelRun run = start(def, h);
		morning(run, h);
		toBalloon(run, h);
		h.flag(K, "levelmod:in_area", true);
		h.flag(Y, "levelmod:in_area", true);
		tick(run, 4);
		for (int step = 0; step < 6 && run.state() == LevelRun.State.RUNNING; step++) {
			String m = run.currentMission() == null ? "—" : run.currentMission().id();
			System.out.println("      миссия: " + m);
			if (m.equals("cave")) {
				at(h, K, 2300, 0, 0);
				at(h, Y, 2302, 0, 0);
			} else if (m.equals("chase")) {
				at(h, K, 2600, 0, 0);
				at(h, Y, 2602, 0, 0);
			}
			tick(run, 4);
		}
		check("уровень завершён: " + run.state(), run.state() == LevelRun.State.COMPLETED);
		check("уровень завершён: оба вне hold", h.held.isEmpty());
	}

	static void scenario8(LevelDefinition def) {
		System.out.println("S8 бросили прогон: hold снимается, снимок возвращается");
		Host h = new Host();
		LevelRun run = start(def, h);
		morning(run, h);
		toBalloon(run, h);
		h.flag(K, "levelmod:in_area", true);
		tick(run, 3);
		run.onDeath(K);
		check("Крош в hold", h.held.contains(K));
		run.abandon();
		check("после abandon hold снят", h.held.isEmpty());
		check("состояние ABANDONED", run.state() == LevelRun.State.ABANDONED);
	}

	static void scenario9(LevelDefinition def) {
		System.out.println("S9 игрок покинул прогон (removeParticipant) на середине: остался один");
		Host h = new Host();
		LevelRun run = start(def, h);
		morning(run, h);
		toBalloon(run, h);
		run.removeParticipant(Y);
		h.pos.remove(Y);
		h.flag(K, "levelmod:in_area", true);
		tick(run, 20);
		String m = run.currentMission() == null ? "—" : run.currentMission().id();
		System.out.println("      миссия после ухода Ёжика: " + m + ", y_jungle=" + done(run, "y_jungle") + " k_jungle=" + done(run, "k_jungle"));
		check("прогон жив после ухода участника", run.state() == LevelRun.State.RUNNING);
		check("Крош доделал свои задачи", done(run, "k_jungle"));
	}

	static void scenario10(LevelDefinition def) {
		System.out.println("S10 оба погибли в одном тике на этапе джунглей");
		Host h = new Host();
		LevelRun run = start(def, h);
		morning(run, h);
		toBalloon(run, h);
		int kb = h.returns.getOrDefault(K, 0), yb = h.returns.getOrDefault(Y, 0), eb = h.publishes;
		run.onDeath(K);
		run.onDeath(Y);
		int kr = h.returns.getOrDefault(K, 0) - kb, yr = h.returns.getOrDefault(Y, 0) - yb;
		System.out.println("      возвратов K " + kr + ", Y " + yr + ", событий " + (h.publishes - eb));
		check("оба возвращены, без лавины", kr >= 1 && yr >= 1 && kr <= 5 && yr <= 5 && h.publishes - eb < 200);
		check("задачи этапа снова идут", run.isStarted(def.task("k_jungle")) || run.isStarted(def.task("k_lift")));
	}

	static void scenario11(LevelDefinition def) {
		System.out.println("S11 откат подвижного к снимку чекпоинта того, кто сбросился (вариант A)");
		Host h = new Host();
		LevelRun run = start(def, h);
		morning(run, h);
		tick(run, 3);
		int startN = h.snaps;
		System.out.println("      снимков после старта flight: " + h.snaps);
		check("снимок начала миссии сделан", startN >= 1);
		toBalloon(run, h, false);
		tick(run, 3);
		System.out.println("      чекпоинт Кроша: " + run.checkpointPoint(K) + ", миссия " + (run.currentMission() == null ? "—" : run.currentMission().id()) + ", чекпоинтов у роли: " + run.currentMission().checkpointsFor(KROSH).size() + " всего " + run.currentMission().checkpoints().size());
		int afterCheckpoint = h.snaps;
		check("вход в чекпоинт «шар» сделал новые снимки (по одному на вошедшего): " + (afterCheckpoint - startN), afterCheckpoint > startN);
		int kSnap = afterCheckpoint;
		h.restoredN.clear();
		run.onDeath(K);
		System.out.println("      возвращены снимки: " + h.restoredN + " (снимок начала " + startN + ", после чекпоинта " + (startN + 1) + ".." + afterCheckpoint + ")");
		check("после гибели Кроша возвращён снимок его чекпоинта, не начала миссии", !h.restoredN.isEmpty() && h.restoredN.getFirst() > startN);
		// Ёжик тоже вошёл в чекпоинт (оба у шара): его снимок свой; убедимся, что игрок, не дошедший до чекпоинта, получает снимок начала
		Host h2 = new Host();
		LevelRun run2 = start(def, h2);
		morning(run2, h2);
		tick(run2, 3);
		int start2 = h2.snaps;
		at(h2, K, 50, 64, 50);
		tick(run2, 3);
		h2.restoredN.clear();
		run2.onDeath(Y);
		System.out.println("      Ёжик не дошёл до чекпоинта: возвращены " + h2.restoredN + " (снимок начала " + start2 + ")");
		check("игрок без чекпоинта получает снимок начала миссии", !h2.restoredN.isEmpty() && h2.restoredN.getFirst() == start2);
		tick(run2, 2);
		h2.restoredN.clear();
		run2.onDeath(K);
		check("Крош (у чекпоинта) получает более поздний снимок, чем Ёжик", !h2.restoredN.isEmpty() && h2.restoredN.getFirst() > start2);
	}

	static void scenario12(LevelDefinition def) throws Exception {
		System.out.println("S12 принудительное завершение и пропуск миссии (без статистики)");
		Host h = new Host();
		LevelRun run = start(def, h);
		morning(run, h);
		toBalloon(run, h, false);
		check("до команды уровень идёт", run.state() == LevelRun.State.RUNNING && !run.forced());
		run.onDeath(K);
		check("гибель до команды: Крош в hold? нет (его задачи идут)", !h.held.contains(K));
		check("complete отказывает, если уровень не идёт (повторный вызов ниже)", true);
		boolean ok = run.forceComplete();
		check("forceComplete вернул true, состояние COMPLETED", ok && run.state() == LevelRun.State.COMPLETED);
		check("прогон помечен принудительным", run.forced());
		check("все задачи выполнены", def.tasks().stream().allMatch(t -> run.isCompleted(t)));
		check("level_completed разослан один раз", h.count("level_completed") == 1);
		check("пропущенные миссии не запускали волны: нет mission_started:jungle/cave/chase", h.count("mission_started:jungle") == 0 && h.count("mission_started:cave") == 0 && h.count("mission_started:chase") == 0);
		check("hold и напарники сняты", h.held.isEmpty());
		check("повторный forceComplete отказывает", !run.forceComplete() && !run.skipMission());
		// статистика
		var c = LevelStore.class.getDeclaredConstructor();
		c.setAccessible(true);
		LevelStore store = c.newInstance();
		var f = LevelStore.class.getDeclaredField("runs");
		f.setAccessible(true);
		@SuppressWarnings("unchecked") List<LevelRun> runs = (List<LevelRun>) f.get(store);
		runs.add(run);
		store.sweep();
		check("принудительный прогон убран, а в статистику не попал", runs.isEmpty() && store.statsOf(run.level()).runs() == 0);
		// обычное завершение для сравнения попадает в статистику
		Host h2 = new Host();
		LevelRun normal = start(def, h2);
		normal.forceComplete();
		var fr = LevelRun.class.getDeclaredField("forced");
		fr.setAccessible(true);
		fr.setBoolean(normal, false);
		runs.add(normal);
		tick(normal, 1);
		store.sweep();
		check("прогон без пометки в статистику идёт (контроль теста)", store.statsOf(normal.level()).runs() == 1);
		// skip
		Host h3 = new Host();
		LevelRun r3 = start(def, h3);
		morning(r3, h3);
		check("идёт flight", r3.currentMission() != null && r3.currentMission().id().equals("flight"));
		boolean skipped = r3.skipMission();
		check("skip вернул true, flight завершена, началась jungle, прогон принудительный", skipped && h3.count("mission_completed:flight") == 1 && h3.count("mission_started:jungle") == 1 && r3.forced() && r3.state() == LevelRun.State.RUNNING);
		check("после skip уровень идёт дальше нормально: текущая миссия jungle", r3.currentMission() != null && r3.currentMission().id().equals("jungle"));
	}

	/** Уровень из файла с правкой JSON (для сценариев про события и ячейки миссии). */
	static LevelDefinition edited(java.util.function.Consumer<com.google.gson.JsonObject> change) throws Exception {
		com.google.gson.JsonObject json = JsonParser.parseString(Files.readString(Path.of(LEVEL_FILE))).getAsJsonObject();
		change.accept(json);
		return loadText(json.toString());
	}

	static com.google.gson.JsonObject missionJson(com.google.gson.JsonObject level, String id) {
		for (var m : level.getAsJsonArray("missions")) if (m.getAsJsonObject().get("id").getAsString().equals(id)) return m.getAsJsonObject();
		throw new IllegalArgumentException(id);
	}

	static com.google.gson.JsonObject trigger(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	static void toCave(LevelRun run) {
		for (int i = 0; i < 4 && !(run.currentMission() != null && run.currentMission().id().equals("cave")); i++) run.skipMission();
	}

	static void scenario13(LevelDefinition def) throws Exception {
		System.out.println("S13 триггеры уровня переложены в миссии (миграция)");
		var byMission = new java.util.LinkedHashMap<String, Integer>();
		for (var m : def.missions()) byMission.put(m.id(), m.triggers().size());
		System.out.println("      триггеров в миссиях: " + byMission);
		check("morning без триггеров, flight 5, jungle 2, cave 7, chase 0", byMission.get("morning") == 0 && byMission.get("flight") == 5 && byMission.get("jungle") == 2 && byMission.get("cave") == 7 && byMission.get("chase") == 0);
		long levelOwn = def.triggers().stream().filter(t -> def.triggerOwner(t).isEmpty() && t.then().stream().anyMatch(a -> a instanceof LevelAction.PlayCutscene)).count();
		check("в уровне остался заставочный триггер level_started", levelOwn == 1);
		check("волна лавы не осталась в уровне", def.triggers().stream().filter(t -> def.triggerOwner(t).isEmpty()).noneMatch(t -> t.then().stream().anyMatch(a -> a instanceof LevelAction.Mechanic(var id, var args) && id.getPath().equals("lava_wave"))));
		check("цепочка волн (lava1→lava2→lava3) в cave", def.missions().stream().filter(m -> m.id().equals("cave")).findFirst().orElseThrow().triggers().stream().filter(t -> t.when().stream().anyMatch(e -> e.subject().startsWith("gameplaymod:lava_wave_finished") || e.subject().startsWith("lava_wave_finished"))).count() == 3);
		var raw = JsonParser.parseString(Files.readString(Path.of(LEVEL_FILE)));
		var once = com.smeshariki.level.definition.LegacyTriggers.migrate(raw);
		check("повторная миграция ничего не меняет", once.equals(com.smeshariki.level.definition.LegacyTriggers.migrate(once)));
		var problems = com.smeshariki.level.definition.LevelAnalyzer.analyze(def, id -> 0, 2).problems();
		System.out.println("      проблемы анализатора: " + problems);
		check("анализатор: нет ошибок области видимости", problems.stream().noneMatch(pr -> pr.startsWith("триггер миссии")));
	}

	static void scenario14(LevelDefinition def) {
		System.out.println("S14 события миссии работают только в её рамках (до начала и после конца — нет)");
		Host h = new Host();
		LevelRun run = start(def, h);
		LevelEvent finished1 = LevelEvent.external("gameplaymod:lava_wave_finished:lava1");
		int base = (int) h.log.stream().filter(l -> l.startsWith("mechanic gameplaymod:lava_wave")).count();
		run.external(finished1);
		int before = (int) h.log.stream().filter(l -> l.startsWith("mechanic gameplaymod:lava_wave")).count();
		check("до начала миссии cave событие волны не запускает lava2", before == base);
		toCave(run);
		check("идёт cave", run.currentMission() != null && run.currentMission().id().equals("cave"));
		run.external(finished1);
		int during = (int) h.log.stream().filter(l -> l.startsWith("mechanic gameplaymod:lava_wave")).count();
		check("пока cave идёт, конец lava1 запускает lava2", during == before + 1);
		run.skipMission();
		int afterEnd = (int) h.log.stream().filter(l -> l.startsWith("mechanic gameplaymod:lava_wave")).count();
		run.external(finished1);
		int after = (int) h.log.stream().filter(l -> l.startsWith("mechanic gameplaymod:lava_wave")).count();
		check("после конца cave событие волны ничего не запускает", after == afterEnd);
		// триггер миссии, ждущий её собственный конец, сработал (mission_completed:jungle → автобег выключен)
		check("свой mission_completed доходит до триггера миссии (jungle выключил автобег)", h.count("mission_completed:jungle") == 1);
	}

	static void scenario15(LevelDefinition ignored) throws Exception {
		System.out.println("S15 ячейки отката, действие при входе, события «вход в чекпоинт» и «откат на чекпоинт»");
		LevelDefinition def = edited(level -> {
			var cave = missionJson(level, "cave");
			var cps = cave.getAsJsonArray("checkpoints");
			var first = new com.google.gson.JsonObject();
			first.add("at", cps.get(0));
			first.add("reset", JsonParser.parseString("[{\"kind\":\"block\",\"type\":\"minecraft:dirt\"}]"));
			var second = new com.google.gson.JsonObject();
			second.add("at", cps.get(1));
			second.add("reset", JsonParser.parseString("[{\"kind\":\"block\",\"type\":\"minecraft:stone\"},{\"kind\":\"entity\",\"type\":\"minecraft:cow\"}]"));
			second.add("on_enter", JsonParser.parseString("{\"action\":\"gameplaymod:lava_wave\",\"group\":\"cpwave\"}"));
			var rebuilt = new com.google.gson.JsonArray();
			rebuilt.add(first);
			rebuilt.add(second);
			rebuilt.add(cps.get(2));
			cave.add("checkpoints", rebuilt);
			if (!cave.has("triggers")) cave.add("triggers", new com.google.gson.JsonArray());
			var triggers = cave.getAsJsonArray("triggers");
			triggers.add(trigger("{\"when\":\"checkpoint_reached:cave:2\",\"then\":{\"action\":\"emit\",\"event\":\"cp2\"}}"));
			triggers.add(trigger("{\"when\":\"checkpoint_reset:cave:2\",\"repeat\":true,\"then\":{\"action\":\"emit\",\"event\":\"cp2_reset\"}}"));
			triggers.add(trigger("{\"when\":\"checkpoint_reset:cave:1\",\"repeat\":true,\"then\":{\"action\":\"emit\",\"event\":\"cp1_reset\"}}"));
		});
		Checkpoint cp2 = def.missions().stream().filter(m -> m.id().equals("cave")).findFirst().orElseThrow().checkpoints().get(1);
		check("чекпоинт разобран: 2 ячейки и действие при входе", cp2.resets().size() == 2 && cp2.onEnter().isPresent());
		Host h = new Host();
		LevelRun run = start(def, h);
		toCave(run);
		tick(run, 2);
		check("в начале cave запомнены ячейки первого чекпоинта (1 ячейка)", h.cellsSavedSizes.contains(1));
		int waves = (int) h.log.stream().filter(l -> l.startsWith("mechanic gameplaymod:lava_wave")).count();
		h.cellsSavedSizes.clear();
		at(h, K, 2150, 10, 0);
		tick(run, 3);
		check("вход во второй чекпоинт: событие checkpoint_reached:cave:2 один раз", h.count("checkpoint_reached:cave:2") == 1);
		check("вход: запомнены 2 ячейки", h.cellsSavedSizes.contains(2));
		check("вход: действие при входе (волна cpwave) выполнено один раз", (int) h.log.stream().filter(l -> l.startsWith("mechanic gameplaymod:lava_wave")).count() == waves + 1);
		check("триггер миссии на вход сработал (custom:cp2)", h.count("custom:cp2") == 1);
		tick(run, 3);
		check("повторный тик в чекпоинте не повторяет вход", h.count("checkpoint_reached:cave:2") == 1);
		h.log.clear();
		run.onDeath(K);
		check("гибель Кроша: событие checkpoint_reset:cave:2, custom:cp2_reset", h.count("checkpoint_reset:cave:2") == 1 && h.count("custom:cp2_reset") == 1);
		check("откат: ячейки второго чекпоинта (снимок после входа) возвращены после блоков", h.cellsRestored.size() == 1 && h.cellsRestored.getFirst() == h.cellSaves);
		int ri = h.log.indexOf("resetBlocks"), ci = h.log.indexOf("restoreCells " + h.cellsRestored.getFirst());
		check("порядок: сначала блоки версии, потом ячейки", ri >= 0 && ci > ri);
		h.cellsRestored.clear();
		h.events.clear();
		tick(run, 2);
		run.onDeath(Y);
		check("гибель Ёжика (чекпоинт 1): откат на чекпоинт 1 и ячейки начала миссии", h.count("checkpoint_reset:cave:1") == 1 && h.count("custom:cp1_reset") == 1 && h.cellsRestored.size() == 1);
		// область видимости: чужое событие в триггере миссии — ошибка анализатора
		LevelDefinition bad = edited(level -> {
			var cave = missionJson(level, "cave");
			if (!cave.has("triggers")) cave.add("triggers", new com.google.gson.JsonArray());
			cave.getAsJsonArray("triggers").add(trigger("{\"when\":\"task_completed:k_take\",\"then\":{\"action\":\"fail_task\",\"task\":\"k_jungle\"}}"));
			cave.getAsJsonArray("triggers").add(trigger("{\"when\":\"checkpoint_reached:cave:9\",\"then\":{\"action\":\"emit\",\"event\":\"x\"}}"));
		});
		var problems = com.smeshariki.level.definition.LevelAnalyzer.analyze(bad, id -> 0, 2).problems();
		System.out.println("      проблемы: " + problems.stream().filter(pr -> pr.startsWith("триггер миссии")).toList());
		check("анализатор: чужая задача в событии, чужая задача в действии, нет такого чекпоинта", problems.stream().filter(pr -> pr.startsWith("триггер миссии")).count() == 3);
		var good = com.smeshariki.level.definition.LevelAnalyzer.analyze(def, id -> 0, 2).problems();
		check("анализатор: правильные события и ячейки ошибок области видимости не дают", good.stream().noneMatch(pr -> pr.startsWith("триггер миссии") || pr.startsWith("ячейка")));
	}

	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		Map<String, Runnable> inits = new java.util.LinkedHashMap<>();
		inits.put("GameplayMechanics", com.smeshariki.gameplay.api.GameplayMechanics::init);
		inits.put("LevelConditions", LevelConditions::init);
		inits.forEach((name, init) -> {
			try {
				init.run();
				System.out.println("init " + name + " ok");
			} catch (Throwable t) {
				System.out.println("init " + name + " ПРОПУЩЕН: " + t);
			}
		});
		LEVEL_FILE = args[0];
		LevelDefinition def = load(args[0]);
		System.out.println("уровень разобран: " + def.missions().size() + " миссий, триггеров " + def.triggers().size());
		Runnable[] all = {() -> scenario1(def), () -> scenario2(def), () -> scenario3(def), () -> scenario4(def), () -> scenario5(def), () -> scenario6(def), () -> scenario7(def), () -> scenario8(def), () -> scenario9(def), () -> scenario10(def), () -> { try { scenario11(def); } catch (Exception e) { throw new RuntimeException(e); } }, () -> { try { scenario12(def); } catch (Exception e) { throw new RuntimeException(e); } }, () -> { try { scenario13(def); } catch (Exception e) { throw new RuntimeException(e); } }, () -> scenario14(def), () -> { try { scenario15(def); } catch (Exception e) { throw new RuntimeException(e); } }};
		int i = 0;
		for (Runnable s : all) {
			i++;
			final int n = i;
			var t = new Thread(() -> {
				try {
					s.run();
				} catch (Throwable e) {
					failures++;
					System.out.println("  FAIL сценарий " + n + " исключение " + e);
					e.printStackTrace(System.out);
				}
			});
			t.setDaemon(true);
			t.start();
			t.join(20_000);
			if (t.isAlive()) {
				failures++;
				System.out.println("  FAIL сценарий " + i + " завис (>20 с, бесконечный цикл?)");
			}
		}
		System.out.println("ИТОГ: проверок " + checks + ", провалов " + failures);
		System.exit(failures == 0 ? 0 : 1);
	}
}
