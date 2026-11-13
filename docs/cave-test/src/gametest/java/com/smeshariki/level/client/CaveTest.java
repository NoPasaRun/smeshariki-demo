package com.smeshariki.level.client;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.character.Characters;
import com.smeshariki.gameplay.character.Possession;
import com.smeshariki.gameplay.lava.LavaWaveEntity;
import com.smeshariki.level.net.LevelTestTask;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

/**
 * Проверка 4-й миссии (cave) на копии настоящего мира: запуск из редактора (тот же JSON, что шлёт окно), волны лавы, гибель и возврат.
 * Печатает строки CAVE: когда пошла каждая волна, события прогона, кто погиб.
 */
public class CaveTest implements FabricClientGameTest {
	static final String WORLD = "Ромашковая Долина";

	static void log(String s) {
		System.out.println("CAVE " + s);
	}

	static void copy(Path from, Path to) throws IOException {
		try (Stream<Path> walk = Files.walk(from)) {
			for (Path p : (Iterable<Path>) walk::iterator) {
				Path target = to.resolve(from.relativize(p).toString());
				if (p.getFileName() != null && p.getFileName().toString().equals("session.lock")) continue;
				if (Files.isDirectory(p)) Files.createDirectories(target);
				else {
					Files.createDirectories(target.getParent());
					Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	static void wipe(Path dir) throws IOException {
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) if (!p.equals(dir)) Files.delete(p);
		}
	}

	static Object get(Object o, String name) throws Exception {
		Class<?> c = o.getClass();
		while (c != null) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(o);
			} catch (NoSuchFieldException e) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}

	final Map<String, Integer> lastStart = new HashMap<>();
	final Set<String> seenEvents = new HashSet<>();
	final Map<String, Integer> waveAt = new HashMap<>();
	boolean lastAlive = true;
	int lastVehicle = -1;
	StringBuilder pending = new StringBuilder();

	@Override
	public void runTest(ClientGameTestContext context) {
		try {
			run(context);
		} catch (Throwable t) {
			log("EXCEPTION " + t);
			t.printStackTrace(System.out);
		}
		log("DONE");
	}

	Object testsMap() throws Exception {
		Field f = Class.forName("com.smeshariki.level.run.LevelManager").getDeclaredField("TESTS");
		f.setAccessible(true);
		return f.get(null);
	}

	/** Одна выборка: печатает новое и возвращает true, если игрок жив. */
	String observe(MinecraftServer server, int origin) throws Exception {
		int tick = server.getTickCount() - origin;
		StringBuilder out = new StringBuilder();
		ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().getFirst();
		for (LavaWaveEntity w : LavaWaveEntity.ALL) {
			if (w.level().isClientSide()) continue;
			String key = w.group() + "#" + w.getId();
			Integer was = lastStart.put(key, w.start());
			if (was == null) out.append(" MARKER+").append(w.group()).append("(y=").append((int) w.getY()).append(")");
			else if (was != w.start() && w.start() >= 0) {
				out.append(" WAVE ").append(w.group()).append(" пошла (base=").append(w.base()).append(")");
				waveAt.put(w.group(), tick);
			}
		}
		lastStart.keySet().removeIf(k -> LavaWaveEntity.ALL.stream().noneMatch(w -> (w.group() + "#" + w.getId()).equals(k)));
		Map<?, ?> tests = (Map<?, ?>) testsMap();
		for (Object run : tests.values()) {
			Set<?> happened = (Set<?>) get(run, "happened");
			for (Object e : happened) if (seenEvents.add(e.toString())) out.append(" EVENT ").append(e);
			out.append(" run=").append(run.getClass().getMethod("state").invoke(run)).append("/").append(run.getClass().getMethod("elapsed").invoke(run));
		}
		if (tests.isEmpty()) out.append(" нет проверки");
		if (player != null) {
			boolean alive = player.isAlive();
			int vehicle = player.getVehicle() == null ? -1 : player.getVehicle().getId();
			if (alive != lastAlive) out.append(" PLAYER alive=").append(alive);
			if (vehicle != lastVehicle) out.append(" VEHICLE ").append(vehicle).append(player.getVehicle() == null ? "" : " " + player.getVehicle().getType().getDescription().getString());
			lastAlive = alive;
			lastVehicle = vehicle;
			out.append(String.format(" pos=%.0f,%.0f,%.0f", player.getX(), player.getY(), player.getZ()));
		}
		return out.length() == 0 ? "" : "t+" + tick + out;
	}

	static String diag(MinecraftServer sv) {
		try {
			var level = sv.overworld();
			int total = 0;
			for (var e : level.getAllEntities()) if (e instanceof LavaWaveEntity) total++;
			Class<?> wr = Class.forName("com.smeshariki.level.run.WorldReset");
			var busy = wr.getDeclaredMethod("busy", MinecraftServer.class);
			busy.setAccessible(true);
			var percent = wr.getDeclaredMethod("percent", MinecraftServer.class);
			percent.setAccessible(true);
			var store = Class.forName("com.smeshariki.level.run.LevelStore").getMethod("get", MinecraftServer.class).invoke(null, sv);
			Object pending = store.getClass().getMethod("worldResetPending").invoke(store);
			long c1 = net.minecraft.world.level.ChunkPos.pack(11, -17), c2 = net.minecraft.world.level.ChunkPos.pack(7, -16);
			StringBuilder all = new StringBuilder();
			for (LavaWaveEntity w : LavaWaveEntity.ALL) all.append(" [").append(w.level().isClientSide() ? "client" : "server").append(" id=").append(w.getId()).append(" g=").append(w.group()).append(" removed=").append(w.isRemoved()).append(" srv=").append(w.level().getServer() == sv).append("]");
			StringBuilder real = new StringBuilder();
			for (var e : level.getAllEntities()) if (e instanceof LavaWaveEntity w) real.append(" [id=").append(w.getId()).append(" g=").append(w.group()).append(" inALL=").append(LavaWaveEntity.ALL.contains(w)).append("]");
			return "РЕАЛЬНЫЕ:" + real + " ALL:" + all + " | маркеров в мире=" + total + " ALL=" + LavaWaveEntity.ALL.size() + " чанк(11,-17) загружен=" + (level.getChunkSource().getChunkNow(11, -17) != null) + " сущности чанка=" + level.areEntitiesLoaded(c1)
					+ " чанк(7,-16)=" + (level.getChunkSource().getChunkNow(7, -16) != null) + " WorldReset busy=" + busy.invoke(null, sv) + " percent=" + percent.invoke(null, sv) + " pending=" + pending;
		} catch (Throwable t) {
			return "diag ошибка " + t;
		}
	}


	/** Верхняя клетка лавы в столбце (или -999). */
	static int lavaTop(net.minecraft.server.level.ServerLevel level, int x, int z) {
		for (int y = -20; y >= -64; y--) if (level.getFluidState(new net.minecraft.core.BlockPos(x, y, z)).is(net.minecraft.tags.FluidTags.LAVA)) return y;
		return -999;
	}

	String state(MinecraftServer sv) {
		var level = sv.overworld();
		StringBuilder b = new StringBuilder("лава(181,-265)=" + lavaTop(level, 181, -265) + " лава(150,-250)=" + lavaTop(level, 150, -250) + " лава(115,-245)=" + lavaTop(level, 115, -245) + " маркеры:");
		java.util.List<String> ms = new java.util.ArrayList<>();
		for (var e : level.getAllEntities()) if (e instanceof LavaWaveEntity w) ms.add(w.group() + "(y=" + String.format("%.0f", w.getY()) + ",start=" + (w.start() >= 0 ? "идёт/была" : "-") + ")");
		java.util.Collections.sort(ms);
		return b.append(' ').append(String.join(" ", ms)).toString();
	}

	/** Ждёт, пока в прогоне случится событие (подстрока), не дольше max тиков; false — не случилось. */
	boolean until(ClientGameTestContext context, TestServerContext server, String event, int max) throws Exception {
		for (int i = 0; i < max / 5; i++) {
			context.waitTicks(5);
			String line = server.computeOnServer(sv -> {
				try {
					ServerPlayer p = sv.getPlayerList().getPlayers().getFirst();
					p.setInvulnerable(true);
					if (p.getVehicle() instanceof LivingEntity v) v.setInvulnerable(true);
					return observe(sv, sv.getTickCount());
				} catch (Exception e) {
					return "ошибка наблюдения " + e;
				}
			});
			if (!line.isEmpty() && (line.contains("EVENT") || line.contains("WAVE") || line.contains("MARKER") || line.contains("PLAYER") || line.contains("VEHICLE") || line.contains("ошибка"))) log(line);
			if (seenEvents.stream().anyMatch(e -> e.contains(event))) return true;
		}
		return false;
	}

	void wait(ClientGameTestContext context, TestServerContext server, int ticks) throws Exception {
		for (int i = 0; i < ticks / 5; i++) {
			context.waitTicks(5);
			server.runOnServer(sv -> {
				ServerPlayer p = sv.getPlayerList().getPlayers().getFirst();
				p.setInvulnerable(true);
				if (p.getVehicle() instanceof LivingEntity v) v.setInvulnerable(true);
			});
		}
	}

	/** Сценарий: волна 1 → вход в чекпоинт 2 → волна 2 → гибель → состояние после возврата сравнивается с состоянием при входе. */
	void scripted(ClientGameTestContext context, TestServerContext server, int origin) throws Exception {
		log("СЦЕНАРИЙ: ждём конец волны 1");
		if (!until(context, server, "lava_wave_finished:lava1", 1200)) { log("волна 1 не кончилась"); return; }
		wait(context, server, 200);
		log("S1 после волны 1:        " + server.computeOnServer(this::state));
		server.runOnServer(sv -> {
			ServerPlayer p = sv.getPlayerList().getPlayers().getFirst();
			var root = p.getRootVehicle();
			boolean ok = root.teleportTo((net.minecraft.server.level.ServerLevel) root.level(), 171.5, -30, -273.5, java.util.Set.of(), root.getYRot(), root.getXRot(), true);
			System.out.println("CAVE телепорт: " + ok + " -> " + root.position());
		});
		if (!until(context, server, "checkpoint_reached:cave:2", 200)) { log("чекпоинт 2 не засчитан"); return; }
		wait(context, server, 10);
		log("S2 вход в чекпоинт 2:   " + server.computeOnServer(this::state));
		wait(context, server, 200);
		log("S3 через 200 тиков:     " + server.computeOnServer(this::state));
		seenEvents.removeIf(e -> e.contains("task_reset") || e.contains("checkpoint_reset"));
		server.runOnServer(sv -> {
			ServerPlayer p = sv.getPlayerList().getPlayers().getFirst();
			if (p.getVehicle() instanceof LivingEntity v && v.level() instanceof net.minecraft.server.level.ServerLevel l) v.kill(l);
		});
		log("персонаж убит");
		if (!until(context, server, "checkpoint_reset", 400)) log("откат на чекпоинт не случился");
		wait(context, server, 100);
		log("S4 после возврата:      " + server.computeOnServer(this::state));
		wait(context, server, 300);
		log("S5 ещё через 300 тиков: " + server.computeOnServer(this::state));
	}

	void run(ClientGameTestContext context) throws Exception {
		Path home = Path.of(System.getProperty("user.home"), ".minecraft");
		Path saveDir;
		net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave save;
		try (TestSingleplayerContext w = context.worldBuilder().adjustSettings(st -> st.setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
			context.waitTicks(20);
			save = w.getWorldSave();
			saveDir = save.getSaveDirectory();
		}
		log("копирую мир в " + saveDir);
		wipe(saveDir);
		copy(home.resolve("saves").resolve(WORLD), saveDir);
		Path data = FabricLoader.getInstance().getGameDir().resolve("data/levelmod");
		Files.createDirectories(data);
		copy(home.resolve("data/levelmod/levels"), data.resolve("levels"));
		Files.createDirectories(data.resolve("worlds").resolve(saveDir.getFileName().toString()));
		Files.copy(home.resolve("data/levelmod/worlds").resolve(WORLD).resolve("places.json"), data.resolve("worlds").resolve(saveDir.getFileName().toString()).resolve("places.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		log("мир скопирован");
		String source = Files.readString(data.resolve("levels/levelmod/sandy_meadow.json"));
		Identifier levelId = Identifier.parse("levelmod:sandy_meadow");
		LevelDraft draft = LevelDraft.of(levelId, source);
		LevelDraft.MissionDraft cave = draft.missions.stream().filter(m -> m.id.equals("cave")).findFirst().orElseThrow();
		String json = draft.missionJson(cave).toString();
		log("JSON миссии cave из редактора: " + json.length() + " знаков, триггеров в миссии: " + cave.rules.size() + " правил + " + cave.otherTriggers.size() + " прочих");

		try (TestSingleplayerContext world = save.open()) {
			context.waitTicks(60);
			var server = world.getServer();
			server.runCommand("gamemode creative @s");
			server.runCommand("tp @s 205 -19 -270");
			context.waitTicks(100);
			int origin = server.computeOnServer(MinecraftServer::getTickCount);
			log("игрок у начала пещеры; запуск проверки");
			context.runOnClient(mc -> ClientPlayNetworking.send(new LevelTestTask(Optional.of(json), Optional.of(levelId))));
			context.waitTicks(10);
			// вселяем Ёжика (как выбор в окне), пока проверка не начнётся
			for (int i = 0; i < 40; i++) {
				boolean possessing = server.computeOnServer(sv -> {
					ServerPlayer p = sv.getPlayerList().getPlayers().getFirst();
					if (CharacterEntity.isPossessing(p)) return true;
					for (EntityType<CharacterEntity> t : Characters.all()) if (EntityType.getKey(t).toString().equals("gameplaymod:yozhik")) Possession.swapTo(p, t);
					return false;
				});
				if (possessing) break;
				context.waitTicks(5);
			}
			scripted(context, server, origin);
		}
	}
}
