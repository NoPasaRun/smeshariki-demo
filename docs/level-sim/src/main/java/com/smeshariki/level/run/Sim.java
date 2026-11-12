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
import com.smeshariki.level.definition.LevelDefinition;
import com.smeshariki.level.definition.LevelEvent;
import com.smeshariki.level.definition.Levels;
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
		int publishes;

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
			return new CompoundTag();
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
		Set<String> missing = new java.util.TreeSet<>();
		// реестры мода без игры не заполнить: персонажей и предметы заменяем ванильными, логике прогона их имена безразличны
		String text = Files.readString(Path.of(file)).replace("gameplaymod:krosh_tailed", "minecraft:cow").replace("gameplaymod:yozhik", "minecraft:pig")
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
		LevelDefinition def = load(args[0]);
		System.out.println("уровень разобран: " + def.missions().size() + " миссий, триггеров " + def.triggers().size());
		Runnable[] all = {() -> scenario1(def), () -> scenario2(def), () -> scenario3(def), () -> scenario4(def), () -> scenario5(def), () -> scenario6(def), () -> scenario7(def), () -> scenario8(def), () -> scenario9(def), () -> scenario10(def)};
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
