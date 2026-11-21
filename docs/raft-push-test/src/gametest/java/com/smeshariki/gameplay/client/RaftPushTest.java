package com.smeshariki.gameplay.client;

import org.lwjgl.glfw.GLFW;

import com.smeshariki.gameplay.raft.RaftEntity;
import com.smeshariki.gameplay.raft.Rafts;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Обмен импульсом плотов с телами и прицел сквозь плоты. Строки RAFT в лог.
 * Площадка: каменный пол y=199 (верх 200), плоты стоят на нём (на суше течения нет, толчок гасит сцепление 0,1 за тик).
 */
public class RaftPushTest implements FabricClientGameTest {
	static final int Y = 200;
	/** Прогонять только посадку (по просьбе: остальные сценарии долгие); false — все. */
	static final boolean ONLY_SEATS = true;
	/** Только снимки иконок блоков в инвентаре. */
	static final boolean ONLY_ICONS = false;
	/** Только снимок позы сидя. */
	static final boolean ONLY_POSE = false;
	/** Только управление в полёте из жерла (W весь полёт против контроля без нажатий). */
	static final boolean ONLY_STEER = true;

	static void log(String s) {
		System.out.println("RAFT " + s);
	}

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

	static ServerPlayer player(MinecraftServer sv) {
		return sv.getPlayerList().getPlayers().getFirst();
	}

	/** Плот sx×sy×sz из досок с центром основания (x, z) на полу; номер сущности. */
	static int raft(TestServerContext server, double x, double z, int sx, int sy, int sz) {
		return server.computeOnServer(sv -> {
			ServerLevel level = sv.overworld();
			RaftEntity r = new RaftEntity(Rafts.TYPE, level);
			BlockState[] cells = new BlockState[sx * sy * sz];
			java.util.Arrays.fill(cells, Blocks.OAK_PLANKS.defaultBlockState());
			r.setCells(sx, sy, sz, cells);
			r.setPos(x, Y, z);
			level.addFreshEntity(r);
			return r.getId();
		});
	}

	static void clear(ClientGameTestContext context, TestServerContext server) {
		server.runOnServer(sv -> {
			for (Entity e : sv.overworld().getAllEntities()) if (e instanceof RaftEntity || e instanceof Pig) e.discard();
		});
		context.waitTicks(5);
	}

	static double serverX(TestServerContext server, int id) {
		return server.computeOnServer(sv -> sv.overworld().getEntity(id).getX());
	}

	static double clientX(ClientGameTestContext context, int id) {
		return context.computeOnClient(mc -> mc.level.getEntity(id) == null ? Double.NaN : mc.level.getEntity(id).getX());
	}

	static void place(ClientGameTestContext context, TestServerContext server, double x, double z, float yaw, float pitch) {
		server.runCommand(String.format(java.util.Locale.ROOT, "tp @a %.2f %d %.2f %.1f %.1f", x, Y, z, yaw, pitch));
		context.waitTicks(15);
	}

	void run(ClientGameTestContext context) {
		try (TestSingleplayerContext w = context.worldBuilder().adjustSettings(st -> st.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
			TestServerContext server = w.getServer();
			context.waitTicks(40);
			server.runCommand("gamemode creative @a");
			server.runCommand("tp @a 5 " + Y + " 0");
			context.waitTicks(20);
			server.runCommand("fill -5 " + (Y - 1) + " -12 35 " + (Y - 1) + " 12 stone");
			server.runCommand("fill -5 " + Y + " -12 35 " + (Y + 6) + " 12 air");
			context.waitTicks(10);

			if (ONLY_STEER) {
				for (int k = 0; k < 6; k++) server.runCommand("fill -20 " + (Y + 7 * k) + " -20 60 " + (Y + 7 * k + 6) + " 20 air");
				server.runCommand("fill -20 " + (Y - 1) + " -20 60 " + (Y - 1) + " 20 stone");
				server.runCommand(String.format(java.util.Locale.ROOT, "tp @a 20.5 %d 0.5 -90 0", Y));
				context.waitTicks(10);
				server.runCommand("gamemode survival @a");
				context.waitTicks(10);
				SeatHelper.possess(context, server, "krosh_tailed");
				context.waitTicks(20);
				log("после вселения: " + diag(context));
				steerPossessed(context, server);
				return;
			}
			if (ONLY_ICONS) {
				icons(context, server);
				return;
			}
			if (ONLY_POSE) {
				sitPose(context, server);
				return;
			}
			if (ONLY_SEATS) {
				seats(context, server);
				return;
			}
			walkInto(context, server, "лёгкий 2x1x2 (масса 4)", 2, 1, 2, 0.5);
			walkInto(context, server, "тяжёлый 4x2x4 (масса 32)", 4, 2, 4, 0.5);
			walkInto(context, server, "контроль: идёт мимо (z+3)", 2, 1, 2, 3.5);

			strikePig(context, server, "лёгкий 2x1x2", 2, 1, 2);
			strikePig(context, server, "тяжёлый 4x2x4", 4, 2, 4);
			strikePlayer(context, server);

			slabs(context, server);
			launcher(context, server);
			pendulum(context, server);
			tetherOnLand(context, server);
			ropeOwnRaft(context, server);
			ropePull(context, server);

			aim(context, server);
			seats(context, server);
		}
	}

	/** Игрок идёт по +x 40 тиков в сторону плота с центром (10, z). */
	void walkInto(ClientGameTestContext context, TestServerContext server, String what, int sx, int sy, int sz, double playerZ) {
		clear(context, server);
		int id = raft(server, 10, 0.5, sx, sy, sz);
		place(context, server, 10 - sx / 2.0 - 3, playerZ, -90, 0);
		context.waitTicks(10);
		double s0 = serverX(server, id), c0 = clientX(context, id);
		context.getInput().holdKeyFor(o -> o.keyUp, 40);
		context.waitTicks(30);
		double s1 = serverX(server, id), c1 = clientX(context, id);
		double px = context.computeOnClient(mc -> mc.player.getX());
		log(String.format(java.util.Locale.ROOT, "W %s: плот сдвинулся сервер %.3f клиент %.3f; игрок x=%.2f (передний край плота был %.2f); расхождение клиент-сервер %.3f",
				what, s1 - s0, c1 - c0, px, 10 - sx / 2.0, Math.abs(c1 - s1)));
	}

	/** Плот получает толчок 0,4 блока/тик к свинье в 3 блоках впереди. */
	void strikePig(ClientGameTestContext context, TestServerContext server, String what, int sx, int sy, int sz) {
		clear(context, server);
		place(context, server, 5, -8, 0, 0);
		int id = raft(server, 10, 0.5, sx, sy, sz);
		server.runCommand(String.format(java.util.Locale.ROOT, "summon pig %.2f %d 0.5 {NoAI:1b}", 10 + sx / 2.0 + 2.5, Y));
		context.waitTicks(2);
		int pig = server.computeOnServer(sv -> {
			for (Entity e : sv.overworld().getAllEntities()) if (e instanceof Pig) return e.getId();
			return -1;
		});
		context.waitTicks(10);
		double pig0 = serverX(server, pig), raft0 = serverX(server, id);
		server.runOnServer(sv -> ((RaftEntity) sv.overworld().getEntity(id)).bump(0.4, 0));
		double maxPigSpeed = 0;
		for (int i = 0; i < 40; i++) {
			context.waitTicks(1);
			maxPigSpeed = Math.max(maxPigSpeed, server.computeOnServer(sv -> sv.overworld().getEntity(pig).getDeltaMovement().horizontalDistance()));
		}
		log(String.format(java.util.Locale.ROOT, "P %s: свинья наибольшая скорость %.3f, сдвинулась %.2f; плот прошёл %.2f; клиент плот прошёл %.2f",
				what, maxPigSpeed, serverX(server, pig) - pig0, serverX(server, id) - raft0, Double.NaN));
	}

	/** Лёгкий плот с толчком въезжает в стоящего игрока. */
	void strikePlayer(ClientGameTestContext context, TestServerContext server) {
		clear(context, server);
		int id = raft(server, 10, 0.5, 2, 1, 2);
		place(context, server, 13.5, 0.5, -90, 0);
		context.waitTicks(5);
		double p0 = context.computeOnClient(mc -> mc.player.getX()), r0 = serverX(server, id), rc0 = clientX(context, id);
		server.runOnServer(sv -> ((RaftEntity) sv.overworld().getEntity(id)).bump(0.4, 0));
		double maxSpeed = 0;
		for (int i = 0; i < 40; i++) {
			context.waitTicks(1);
			maxSpeed = Math.max(maxSpeed, context.computeOnClient(mc -> mc.player.getDeltaMovement().horizontalDistance()));
		}
		double p1 = context.computeOnClient(mc -> mc.player.getX());
		log(String.format(java.util.Locale.ROOT, "I игрок под ударом лёгкого плота: наибольшая скорость %.3f, сдвинулся %.2f; плот сервер %.2f клиент %.2f, расхождение %.3f",
				maxSpeed, p1 - p0, serverX(server, id) - r0, clientX(context, id) - rc0, Math.abs(serverX(server, id) - clientX(context, id))));
	}

	/** Плоты 2x1x2 из верхних полублоков, нижних и досок падают на пол с высоты 1: где встанут (низ клетки и низ самого блока над полом). */
	void slabs(ClientGameTestContext context, TestServerContext server) {
		clear(context, server);
		place(context, server, 5, -8, 0, 0);
		String[] names = { "верхний полублок", "нижний полублок", "доски" };
		BlockState[] states = { Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP),
				Blocks.OAK_SLAB.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState() };
		int[] ids = new int[3];
		for (int i = 0; i < 3; i++) {
			BlockState st = states[i];
			double x = 8 + i * 4;
			ids[i] = server.computeOnServer(sv -> {
				RaftEntity r = new RaftEntity(Rafts.TYPE, sv.overworld());
				BlockState[] cells = new BlockState[4];
				java.util.Arrays.fill(cells, st);
				r.setCells(2, 1, 2, cells);
				r.setPos(x, Y + 1, 0.5);
				sv.overworld().addFreshEntity(r);
				return r.getId();
			});
		}
		context.waitTicks(60);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 3; i++) {
			int id = ids[i];
			double y = serverX(server, id) * 0 + server.computeOnServer(sv -> sv.overworld().getEntity(id).getY()) - Y;
			double low = states[i].getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).min(net.minecraft.core.Direction.Axis.Y);
			sb.append(String.format(java.util.Locale.ROOT, " %s: клетка %.3f, низ блока %.3f;", names[i], y, y + low));
		}
		log("S полублоки на полу (ждём низ блока ~0):" + sb);
	}

	static String diag(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			Entity b = clientBody(mc);
			String sp = b instanceof net.minecraft.world.entity.LivingEntity l ? String.format(java.util.Locale.ROOT, "speed %.3f ctrlLocal %s", l.getSpeed(), l.isLocalInstanceAuthoritative()) : "";
			return "screen " + (mc.gui.screen() == null ? "-" : mc.gui.screen().getClass().getSimpleName()) + " " + String.format(java.util.Locale.ROOT, "zza %.2f xxa %.2f mv %s frozen %s onGround %s vx %.3f %s", mc.player.zza, mc.player.xxa, mc.player.input.getMoveVector(),
					com.smeshariki.gameplay.character.CharacterFreeze.frozen(), b.onGround(), b.getDeltaMovement().x, sp);
		});
	}

	/** Тело, которое видит клиент: персонаж, если вселён. */
	static Entity clientBody(net.minecraft.client.Minecraft mc) {
		return mc.player.getVehicle() != null ? mc.player.getVehicle() : mc.player;
	}

	/** Вселённый: жерло ставится под ногами персонажа (tp ссадил бы игрока), курс +z, игрок смотрит на +x; W весь полёт или ничего. */
	void steerPossessed(ClientGameTestContext context, TestServerContext server) {
		server.runOnServer(sv -> { ServerPlayer p = player(sv); p.setYRot(-90); p.connection.teleport(p.getX(), p.getY(), p.getZ(), -90, 0); });
		for (String nbt : new String[] { "{Yaw:0f,Distance:3f,Height:30f}", "{Yaw:0f,Distance:20f,Height:30f}", "{Yaw:0f,Distance:3f,Height:120f}" }) {
			for (boolean hold : new boolean[] { false, true }) {
				server.runCommand("kill @e[type=gameplaymod:launcher]");
				for (int w = 0; w < 200 && !context.computeOnClient(mc -> clientBody(mc).onGround()); w++) context.waitTick();
				SeatHelper.possess(context, server, "krosh_tailed");
				context.runOnClient(mc -> { if (mc.gui.screen() != null) mc.gui.setScreen(null); mc.player.setYRot(-90); });
				context.waitTicks(20);
				String before = diag(context);
				double[] p0 = context.computeOnClient(mc -> new double[] { clientBody(mc).getX(), clientBody(mc).getY(), clientBody(mc).getZ() });
				if (hold) context.getInput().holdKey(o -> o.keyUp);
				server.runCommand(String.format(java.util.Locale.ROOT, "summon gameplaymod:launcher %.1f %d %.1f %s", Math.floor(p0[0]) + 0.5, (int) Math.round(p0[1]), Math.floor(p0[2]) + 0.5, nbt));
				StringBuilder sb = new StringBuilder();
				double top = -1;
				boolean launched = false;
				for (int t = 0; t < 400; t++) {
					context.waitTick();
					double[] st = context.computeOnClient(mc -> {
						Entity b = clientBody(mc);
						return new double[] { b.getX(), b.getY(), b.getZ(), b.getDeltaMovement().x, b.getDeltaMovement().y, b.onGround() ? 1 : 0 };
					});
					double dy = st[1] - p0[1];
					if (dy > 1) launched = true;
					top = Math.max(top, dy);
					if (launched && (t == 10 || t == 40)) sb.append(" {" + diag(context) + "}");
					if (launched && t % 12 == 0) sb.append(String.format(java.util.Locale.ROOT, " [t%d y%.1f dx%.2f dz%.2f vx%.3f vy%.2f]", t, dy, st[0] - p0[0], st[2] - p0[2], st[3], st[4]));
					if (launched && st[5] > 0) {
						sb.append(String.format(java.util.Locale.ROOT, " ПОСАДКА t%d dx%.2f dz%.2f", t, st[0] - p0[0], st[2] - p0[2]));
						break;
					}
				}
				server.runCommand("kill @e[type=gameplaymod:launcher]");
				if (hold) context.getInput().releaseKey(o -> o.keyUp);
				log(String.format("W вселён %s %s: вершина %.1f; до: %s;%s", nbt, hold ? "W весь полёт" : "без W", top, before, sb));
				context.waitTicks(10);
			}
		}
	}

	/** Жерло на (20, Y), курс +z: игрок смотрит на +x и держит W весь полёт (или ничего), лог положения по фазам. */
	void steer(ClientGameTestContext context, TestServerContext server, boolean possessed) {
		server.runCommand("fill 4 " + Y + " -12 35 " + (Y + 40) + " 12 air"); server.runCommand("fill 4 " + (Y + 41) + " -12 35 " + (Y + 80) + " 12 air");
		server.runCommand("fill -30 " + (Y - 1) + " -40 60 " + (Y - 1) + " 40 stone");
		for (String nbt : new String[] { "{Yaw:0f,Distance:0f,Height:30f}" }) {
			for (boolean hold : new boolean[] { false, true }) {
				server.runCommand("kill @e[type=gameplaymod:launcher]");
				server.runCommand(String.format(java.util.Locale.ROOT, "tp @a 14.5 %d 0.5 -90 0", Y));
				context.waitTicks(20);
				server.runCommand("summon gameplaymod:launcher 20.5 " + Y + " 0.5 " + nbt);
				context.waitTicks(3);
				if (hold) context.getInput().holdKey(o -> o.keyUp);
				server.runCommand(String.format(java.util.Locale.ROOT, "tp @a 20.5 %d 0.5 -90 0", Y));
				StringBuilder sb = new StringBuilder();
				double top = -1;
				boolean launched = false;
				for (int t = 0; t < 400; t++) {
					context.waitTick();
					double[] st = context.computeOnClient(mc -> {
						Entity b = clientBody(mc);
						return new double[] { b.getX(), b.getY() - Y, b.getZ(), b.getDeltaMovement().x, b.getDeltaMovement().y, b.onGround() ? 1 : 0 };
					});
					if (st[1] > 1) launched = true;
					top = Math.max(top, st[1]);
					if (launched && (t == 10 || t == 40)) sb.append(" {" + diag(context) + "}");
					if (launched && t % 12 == 0) sb.append(String.format(java.util.Locale.ROOT, " [t%d y%.1f x%.2f z%.2f vx%.3f vy%.2f]", t, st[1], st[0], st[2], st[3], st[4]));
					if (launched && st[5] > 0 && st[1] < 1) {
						sb.append(String.format(java.util.Locale.ROOT, " ПОСАДКА t%d x%.2f z%.2f", t, st[0], st[2]));
						break;
					}
				}
				if (hold) context.getInput().releaseKey(o -> o.keyUp);
				log(String.format("W %s %s %s: вершина %.1f;%s", possessed ? "вселён" : "игрок", nbt, hold ? "W весь полёт" : "без W в полёте", top, sb));
				context.waitTicks(10);
			}
		}
	}

	/** Жерло (курс +z, дальность 8, высота 6) на (20, Y): по очереди свинья (контроль, живое), мяч, плот 1x1x1, свободный конец верёвки. */
	void launcher(ClientGameTestContext context, TestServerContext server) {
		clear(context, server);
		place(context, server, 14, 0.5, -90, 0);
		server.runCommand("fill 15 " + Y + " -3 25 " + (Y + 12) + " 14 air");
		server.runCommand("summon gameplaymod:launcher 20.5 " + Y + " 0.5 {Yaw:0f,Distance:8f,Height:6f}");
		context.waitTicks(5);
		fly(context, server, "свинья (живое, как раньше)", sv -> {
			sv.getCommands().performPrefixedCommand(sv.createCommandSourceStack(), "summon pig 20.5 " + (Y + 2) + " 0.5");
			for (Entity e : sv.overworld().getAllEntities()) if (e instanceof Pig) return e;
			return null;
		});
		fly(context, server, "мяч", sv -> {
			Entity ball = com.smeshariki.gameplay.ball.Balls.TYPE.create(sv.overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			ball.setPos(20.5, Y + 1, 0.5);
			sv.overworld().addFreshEntity(ball);
			return ball;
		});
		fly(context, server, "плот 1x1x1", sv -> {
			RaftEntity r = new RaftEntity(Rafts.TYPE, sv.overworld());
			r.setCells(1, 1, 1, new BlockState[] { Blocks.OAK_PLANKS.defaultBlockState() });
			r.setPos(20.5, Y + 2, 0.5);
			sv.overworld().addFreshEntity(r);
			return r;
		});
		server.runCommand("setblock 20 " + (Y + 8) + " 0 stone");
		fly(context, server, "конец верёвки (длина 8, крепление над жерлом на 8: конец лежит в жерле; дальше длины не улетит)", sv -> {
			com.smeshariki.gameplay.rope.RopeEntity rope = new com.smeshariki.gameplay.rope.RopeEntity(com.smeshariki.gameplay.rope.Ropes.TYPE, sv.overworld());
			net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(20.5, Y + 8, 0.5);
			rope.setPos(at);
			rope.setupAnchor(new BlockPos(20, Y + 8, 0), at, player(sv));
			sv.overworld().addFreshEntity(rope);
			rope.release();
			rope.setPos(20.5, Y + 3, 0.5);
			return rope;
		});
		server.runCommand("setblock 20 " + (Y + 8) + " 0 air");
		server.runCommand("kill @e[type=gameplaymod:launcher]");
	}

	/** Сущность появляется над жерлом и падает в него: наибольшая высота над жерлом и где впервые снова на его высоте. */
	void fly(ClientGameTestContext context, TestServerContext server, String what, java.util.function.Function<MinecraftServer, Entity> spawn) {
		int id = server.computeOnServer(sv -> spawn.apply(sv).getId());
		double top = -99, landZ = Double.NaN, landX = Double.NaN;
		boolean rose = false;
		String clientTop = "";
		double cTop = -99;
		for (int i = 0; i < 120; i++) {
			context.waitTicks(1);
			double[] p = server.computeOnServer(sv -> { Entity e = sv.overworld().getEntity(id); return e == null ? null : new double[] { e.getX(), e.getY(), e.getZ() }; });
			if (p == null) break;
			double c = clientX(context, id);
			double cy = context.computeOnClient(mc -> mc.level.getEntity(id) == null ? -99 : mc.level.getEntity(id).getY());
			cTop = Math.max(cTop, cy - Y);
			top = Math.max(top, p[1] - Y);
			if (p[1] - Y > 2.5) rose = true;
			if (rose && Double.isNaN(landZ) && p[1] - Y <= 0.05) {
				landZ = p[2] - 0.5;
				landX = p[0] - 20.5;
			}
		}
		log(String.format(java.util.Locale.ROOT, "L %s: высота %.2f (клиент %.2f, ждём ~6), упал на %.2f по курсу, вбок %.2f (ждём ~8 и 0)", what, top, cTop, landZ, landX));
		server.runOnServer(sv -> { Entity e = sv.overworld().getEntity(id); if (e != null) e.discard(); });
		context.waitTicks(5);
	}

	/** Плот 2x1x2 висит на верёвке длиной 8 под блоком на высоте 10; один толчок 0,4 вбок; качания за 200 тиков. */
	void pendulum(ClientGameTestContext context, TestServerContext server) {
		clear(context, server);
		place(context, server, 5, -8, 0, 0);
		server.runCommand("setblock 4 " + (Y + 10) + " 0 stone");
		int id = raft(server, 4.5, 0.5, 2, 1, 2);
		server.runOnServer(sv -> sv.overworld().getEntity(id).setPos(4.5, Y + 1, 0.5));
		context.waitTicks(2);
		boolean ok = server.computeOnServer(sv -> {
			RaftEntity r = (RaftEntity) sv.overworld().getEntity(id);
			com.smeshariki.gameplay.rope.RopeEntity rope = new com.smeshariki.gameplay.rope.RopeEntity(com.smeshariki.gameplay.rope.Ropes.TYPE, sv.overworld());
			net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(4.5, Y + 10, 0.5);
			rope.setPos(at);
			rope.setupAnchor(new BlockPos(4, Y + 10, 0), at, player(sv));
			sv.overworld().addFreshEntity(rope);
			return rope.attachTo(r, new net.minecraft.world.phys.Vec3(4.5, Y + 2, 0.5));
		});
		context.waitTicks(50);
		double y0 = server.computeOnServer(sv -> sv.overworld().getEntity(id).getY()) - Y;
		server.runOnServer(sv -> ((RaftEntity) sv.overworld().getEntity(id)).bump(0.4, 0));
		StringBuilder sb = new StringBuilder();
		int crossings = 0;
		double last = 0, peak = 0, maxGap = 0;
		for (int i = 1; i <= 200; i++) {
			context.waitTicks(1);
			double dx = serverX(server, id) - 4.5;
			maxGap = Math.max(maxGap, Math.abs(clientX(context, id) - 4.5 - dx));
			if (i > 1 && Math.signum(dx) != Math.signum(last) && last != 0) {
				crossings++;
				sb.append(String.format(java.util.Locale.ROOT, " %.2f", peak));
				peak = 0;
			}
			peak = Math.max(peak, Math.abs(dx));
			last = dx;
		}
		log(String.format(java.util.Locale.ROOT, "M маятник (прицеплена %s, висит на %.2f над полом): переходов через отвес за 200 тиков %d, размахи по порядку:%s; сейчас %.2f; клиент расходится до %.3f",
				ok, y0, crossings, sb, Math.abs(last), maxGap));
		clearRopes(context, server);
		server.runCommand("setblock 4 " + (Y + 10) + " 0 air");
	}

	/** Верёвка (без размаха, длина 8) от блока на высоте 5 к плоту на суше; плот толкают от столба. Высота плота над полом по тикам. */
	void tetherOnLand(ClientGameTestContext context, TestServerContext server) {
		clear(context, server);
		place(context, server, 5, -8, 0, 0);
		server.runCommand("setblock 4 " + (Y + 5) + " 0 stone");
		int id = raft(server, 9, 0.5, 2, 1, 2);
		context.waitTicks(5);
		boolean ok = server.computeOnServer(sv -> {
			RaftEntity r = (RaftEntity) sv.overworld().getEntity(id);
			com.smeshariki.gameplay.rope.RopeEntity rope = new com.smeshariki.gameplay.rope.RopeEntity(com.smeshariki.gameplay.rope.Ropes.CALM_TYPE, sv.overworld());
			net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(4.5, Y + 5, 0.5);
			rope.setPos(at);
			rope.setupAnchor(new BlockPos(4, Y + 5, 0), at, player(sv));
			sv.overworld().addFreshEntity(rope);
			return rope.attachTo(r, new net.minecraft.world.phys.Vec3(8.5, Y + 1, 0.5));
		});
		// клиент первые 40 тиков жизни плота держит его без тяжести (loadHold): толкаем после
		context.waitTicks(50);
		StringBuilder sb = new StringBuilder();
		double maxGap = 0, maxJump = 0, lastCx = Double.NaN, lastCy = Double.NaN;
		for (int i = 0; i < 80; i++) {
			if (i < 10) server.runOnServer(sv -> ((RaftEntity) sv.overworld().getEntity(id)).bump(0.3, 0));
			context.waitTicks(1);
			double[] sp = server.computeOnServer(sv -> { Entity e = sv.overworld().getEntity(id); return new double[] { e.getX(), e.getY() }; });
			double[] cp = context.computeOnClient(mc -> { Entity e = mc.level.getEntity(id); return e == null ? new double[] { Double.NaN, Double.NaN } : new double[] { e.getX(), e.getY() }; });
			maxGap = Math.max(maxGap, Math.hypot(sp[0] - cp[0], sp[1] - cp[1]));
			if (!Double.isNaN(lastCx)) maxJump = Math.max(maxJump, Math.hypot(cp[0] - lastCx, cp[1] - lastCy));
			lastCx = cp[0];
			lastCy = cp[1];
			if (i % 4 == 0 || i == 79) {
				double[] p = server.computeOnServer(sv -> { Entity e = sv.overworld().getEntity(id); return e == null ? new double[] { -1, -1 } : new double[] { e.getX(), e.getY() }; });
				double cy = context.computeOnClient(mc -> mc.level.getEntity(id) == null ? -1 : mc.level.getEntity(id).getY());
				sb.append(String.format(java.util.Locale.ROOT, " t%d x=%.2f y-пол=%.3f(кл %.3f)", i, p[0], p[1] - Y, cy - Y));
			}
		}
		String rope = server.computeOnServer(sv -> {
			for (Entity e : sv.overworld().getAllEntities()) if (e instanceof com.smeshariki.gameplay.rope.RopeEntity r) return "режим " + r.mode() + ", до якоря " + String.format(java.util.Locale.ROOT, "%.2f", r.position().distanceTo(r.anchor()));
			return "верёвки нет";
		});
		log(String.format(java.util.Locale.ROOT, "T наибольшее расхождение клиент-сервер %.2f, наибольший шаг клиентского плота за тик %.2f", maxGap, maxJump));
		log("T прицеплена " + ok + ";" + sb + "; верёвка: " + rope);
		clearRopes(context, server);
		server.runCommand("setblock 4 " + (Y + 5) + " 0 air");
	}

	/** Игрок держит верёвку, привязанную к плоту, и шагает в этот плот: толкать не должен (без верёвки — W лёгкий). */
	void ropeOwnRaft(ClientGameTestContext context, TestServerContext server) {
		clear(context, server);
		int id = raft(server, 10, 0.5, 2, 1, 2);
		place(context, server, 6, 0.5, -90, 0);
		server.runOnServer(sv -> {
			RaftEntity r = (RaftEntity) sv.overworld().getEntity(id);
			com.smeshariki.gameplay.rope.RopeEntity rope = new com.smeshariki.gameplay.rope.RopeEntity(com.smeshariki.gameplay.rope.Ropes.TYPE, sv.overworld());
			net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(10.5, Y + 1, 0.5);
			rope.setPos(at);
			rope.setupAnchor(r, at, player(sv));
			sv.overworld().addFreshEntity(rope);
		});
		context.waitTicks(10);
		boolean held = context.computeOnClient(mc -> com.smeshariki.gameplay.rope.RopeEntity.heldBy(mc.player) != null);
		double s0 = serverX(server, id);
		context.getInput().holdKeyFor(o -> o.keyUp, 40);
		context.waitTicks(30);
		log(String.format(java.util.Locale.ROOT, "R1 верёвка на этом же плоту в руке (держит: %s), шагает в плот: плот сдвинулся %.3f (ждём 0; без верёвки ~2,16); игрок x=%.2f",
				held, serverX(server, id) - s0, context.computeOnClient(mc -> mc.player.getX())));
		clearRopes(context, server);
	}

	/** Верёвка с блока над дальним краем плота укорачивается и тянет игрока в борт: сила внешняя, плот толкается скоростью тела (подтяжка позицией сама не толкает). */
	void ropePull(ClientGameTestContext context, TestServerContext server) {
		clear(context, server);
		server.runCommand("setblock 12 " + (Y + 6) + " 0 stone");
		int id = raft(server, 10, 0.5, 2, 2, 2);
		place(context, server, 8.5, 0.5, -90, 0);
		server.runOnServer(sv -> {
			com.smeshariki.gameplay.rope.RopeEntity rope = new com.smeshariki.gameplay.rope.RopeEntity(com.smeshariki.gameplay.rope.Ropes.TYPE, sv.overworld());
			net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(12.5, Y + 6, 0.5);
			rope.setPos(at);
			rope.setupAnchor(new BlockPos(12, Y + 6, 0), at, player(sv));
			sv.overworld().addFreshEntity(rope);
		});
		context.waitTicks(10);
		double s0 = serverX(server, id);
		String p0 = context.computeOnClient(mc -> String.format(java.util.Locale.ROOT, "(%.2f, %.2f)", mc.player.getX(), mc.player.getY()));
		int collisions = 0;
		for (int i = 0; i < 60; i++) {
			if (i % 5 == 0 && i < 30) context.runOnClient(mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.smeshariki.gameplay.rope.AdjustLength(-1)));
			context.waitTicks(1);
			if (context.computeOnClient(mc -> mc.player.horizontalCollision)) collisions++;
		}
		String p1 = context.computeOnClient(mc -> String.format(java.util.Locale.ROOT, "(%.2f, %.2f)", mc.player.getX(), mc.player.getY()));
		log(String.format(java.util.Locale.ROOT, "R2 верёвка с блока тянет в борт плота: игрок %s -> %s, тиков с упором %d, длина %.2f; плот сдвинулся %.3f (верёвка с мира — внешняя сила, толкает; было 1,19)",
				p0, p1, collisions, context.computeOnClient(mc -> { var r = com.smeshariki.gameplay.rope.RopeEntity.heldBy(mc.player); return r == null ? -1.0 : r.length(); }),
				serverX(server, id) - s0));
		clearRopes(context, server);
		server.runCommand("setblock 12 " + (Y + 6) + " 0 air");
	}

	static void clearRopes(ClientGameTestContext context, TestServerContext server) {
		server.runOnServer(sv -> {
			for (Entity e : sv.overworld().getAllEntities()) if (e instanceof com.smeshariki.gameplay.rope.RopeEntity) e.discard();
		});
		context.waitTicks(5);
	}

	/** Прицел: плот 2x2x2 между игроком и каменным блоком (12, 201, 0); ЛКМ не должна ломать блок за плотом. Контроль — без плота. */
	void aim(ClientGameTestContext context, TestServerContext server) {
		clear(context, server);
		BlockPos wall = new BlockPos(12, Y + 1, 0);
		server.runCommand("fill 12 " + Y + " -1 12 " + (Y + 3) + " 1 stone");
		int id = raft(server, 10, 0.5, 2, 2, 2);
		place(context, server, 8, 0.5, -90, 0);
		context.waitTicks(10);
		String hit = context.computeOnClient(mc -> describe(mc.hitResult));
		for (int i = 0; i < 3; i++) {
			context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
			context.waitTicks(8);
		}
		boolean intact = server.computeOnServer(sv -> !sv.overworld().getBlockState(wall).isAir());
		log("A с плотом: прицел " + hit + "; блок за плотом цел: " + intact + " (ждём MISS, true)");
		server.runOnServer(sv -> sv.overworld().getEntity(id).discard());
		context.waitTicks(10);
		String hit2 = context.computeOnClient(mc -> describe(mc.hitResult));
		context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(8);
		boolean intact2 = server.computeOnServer(sv -> !sv.overworld().getBlockState(wall).isAir());
		log("A контроль без плота: прицел " + hit2 + "; блок цел: " + intact2 + " (ждём BLOCK 12," + (Y + 1) + ",0, false)");
	}

	/** Смотреть клиентом на точку (поворот уходит серверу сам). */
	static void lookAt(ClientGameTestContext context, double x, double y, double z) {
		context.runOnClient(mc -> {
			var eye = mc.player.getEyePosition();
			double dx = x - eye.x, dy = y - eye.y, dz = z - eye.z;
			mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
			mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
		});
		context.waitTicks(2);
	}

	static String vehicleChain(ClientGameTestContext context, TestServerContext server) {
		String sv = server.computeOnServer(s -> chain(player(s)));
		String cl = context.computeOnClient(mc -> chain(mc.player));
		return "сервер " + sv + ", клиент " + cl;
	}

	static String chain(Entity e) {
		StringBuilder b = new StringBuilder();
		for (Entity v = e.getVehicle(); v != null; v = v.getVehicle()) b.append(" -> ").append(v.getType().toShortString());
		return b.length() == 0 ? "пешком" : b.substring(4);
	}

	/** Предметы-блоки модов (кроме предметов с картинкой 16x16) в инвентаре: снимки по 36 штук. */
	void icons(ClientGameTestContext context, TestServerContext server) {
		java.util.List<String> ids = server.computeOnServer(sv -> {
			java.util.List<String> out = new java.util.ArrayList<>();
			for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
				var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
				if (java.util.Set.of("gameplaymod", "buildmod", "levelmod", "worldmod", "behaviormod").contains(key.getNamespace()) && item instanceof net.minecraft.world.item.BlockItem) out.add(key.toString());
			}
			return out;
		});
		log("ICONS " + ids.size() + " " + ids);
		server.runCommand("gamerule send_command_feedback false");
		server.runCommand("tp @a 5 " + Y + " 0 0 -90");
		for (int page = 0; page * 9 < ids.size(); page++) {
			int from = page * 9;
			server.runOnServer(sv -> {
				var inv = player(sv).getInventory();
				for (int i = 0; i < 9; i++) {
					inv.setItem(i, from + i < ids.size() ? new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(ids.get(from + i)))) : net.minecraft.world.item.ItemStack.EMPTY);
				}
				inv.setSelectedSlot(8);
			});
			context.waitTicks(10);
			context.takeScreenshot("icons_" + page);
		}
	}

	/** Смешарики сидят на сиденьях плота 9x1x3 в ряд; снимок сбоку и спереди. */
	void sitPose(ClientGameTestContext context, TestServerContext server) {
		clear(context, server);
		server.runCommand("fill 0 " + Y + " -6 20 " + (Y + 6) + " 8 air");
		server.runCommand("time set noon");
		int id = raft(server, 8.5, 0.5, 15, 1, 3);
		context.waitTicks(50);
		String[] names = { "krosh_tailed", "yozhik", "karych", "losyash", "nyusha", "barash", "sovunya" };
		for (int i = 0; i < names.length; i++) {
			String n = names[i];
			double x = 2.5 + i * 2;
			server.runOnServer(sv -> {
				var level = sv.overworld();
				RaftEntity raft = (RaftEntity) level.getEntity(id);
				var type = com.smeshariki.gameplay.character.Characters.all().stream().filter(t -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath().equals(n)).findFirst().orElseThrow();
				var c = type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				c.setNoAi(true);
				c.setPos(x, Y + 1, 0.5);
				c.setYRot(180);
				c.setYHeadRot(180);
				c.setYBodyRot(180);
				level.addFreshEntity(c);
				var seat = new com.smeshariki.gameplay.raft.SeatEntity(Rafts.SEAT_TYPE, level);
				var at = new net.minecraft.world.phys.Vec3(x, Y + 1, 0.5);
				seat.setLocal(raft.toLocal(at));
				seat.setPos(at);
				level.addFreshEntity(seat);
				seat.startRiding(raft, true, true);
				c.startRiding(seat, true, true);
			});
		}
		// контроль: один стоит на палубе рядом
		server.runOnServer(sv -> {
			var level = sv.overworld();
			var c = com.smeshariki.gameplay.character.Characters.all().getFirst().create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			c.setNoAi(true);
			c.setPos(16.5, Y, 4.5);
			c.setYRot(180);
			c.setYHeadRot(180);
			c.setYBodyRot(180);
			level.addFreshEntity(c);
		});
		context.waitTicks(20);
		server.runCommand("gamerule send_command_feedback false");
		server.runCommand("gamerule sendCommandFeedback false");
		server.runCommand("tp @a 9.5 " + (Y + 1) + " -6 0 10");
		context.waitTicks(30);
		context.takeScreenshot("sit_front");
		// наискосок сбоку: Крош, Ёжик, Карыч сидят, справа вдали стоит контрольный Крош
		server.runCommand("tp @a -1.5 " + (Y + 1.3) + " -3.5 -45 12");
		context.waitTicks(30);
		context.takeScreenshot("sit_side");
		server.runCommand("tp @a 12.5 " + (Y + 0.5) + " 1.5 -90 5");
		context.waitTicks(30);
		context.takeScreenshot("sit_control");
		log("POSE снимки сделаны");
	}

	/** Сесть ПКМ, грести W (зажатие = одно нажатие) и тремя нажатиями, встать Shift; то же вселённым Крошем; клубок Ёжика на W+Shift. */
	void seats(ClientGameTestContext context, TestServerContext server) {
		clear(context, server);
		server.runCommand("fill 5 " + Y + " -4 16 " + (Y + 4) + " 5 air");
		int id = raft(server, 10.5, 0.5, 3, 1, 3);
		place(context, server, 6.5, 0.5, -90, 0);
		context.waitTicks(50);
		for (int round = 0; round < 2; round++) {
			String who = round == 0 ? "игрок" : "вселённый Крош";
			if (round == 1) {
				server.runOnServer(sv -> sv.overworld().getEntity(id).setPos(10.5, Y, 0.5));
				place(context, server, 6.5, 0.5, -90, 0);
				server.runCommand("gamemode survival @a");
				context.waitTicks(5);
				com.smeshariki.gameplay.client.SeatHelper.possess(context, server, "krosh_tailed");
				context.waitTicks(20);
				log("   Крош у " + server.computeOnServer(sv -> player(sv).getVehicle() == null ? "нет" : player(sv).getVehicle().position().toString()));
			}
			lookAt(context, 9.6, Y + 1, 0.5);
			String hit = context.computeOnClient(mc -> describe(mc.hitResult) + ", плот под прицелом: " + (com.smeshariki.gameplay.raft.Rafts.lookedAt(mc.player, mc.level, Double.POSITIVE_INFINITY) != null));
			context.getInput().holdMouseFor(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 3);
			context.waitTicks(10);
			String seated = vehicleChain(context, server);
			double py = context.computeOnClient(mc -> mc.player.getY()) - Y;
			double x0 = serverX(server, id);
			context.getInput().holdKeyFor(o -> o.keyUp, 40);
			context.waitTicks(30);
			double held = serverX(server, id) - x0;
			double x1 = serverX(server, id);
			for (int i = 0; i < 3; i++) {
				context.getInput().holdKeyFor(o -> o.keyUp, 2);
				context.waitTicks(4);
			}
			context.waitTicks(40);
			double taps = serverX(server, id) - x1;
			double riderToRaft = server.computeOnServer(sv -> { Entity r = com.smeshariki.gameplay.raft.SeatEntity.riderOf(player(sv)); return r.getX() - sv.overworld().getEntity(id).getX(); });
			context.getInput().holdKeyFor(o -> o.keyShift, 5);
			context.waitTicks(10);
			String after = vehicleChain(context, server);
			log(String.format(java.util.Locale.ROOT, "C %s: прицел %s; сел: %s (ноги игрока %.2f над полом, палуба на 1); зажатый W сдвинул плот на %.2f, три нажатия на %.2f; сидящий от центра плота по x %.2f (точка клика 9,6 - 10,5 = -0,9); после Shift: %s",
					who, hit, seated, py, held, taps, riderToRaft, after));
		}
		// клубок Ёжика: только W — нет; W+Shift — да; тройное W — нет
		com.smeshariki.gameplay.client.SeatHelper.possess(context, server, "yozhik");
		server.runOnServer(sv -> { var v = player(sv).getVehicle(); if (v != null) v.teleportTo(6.5, Y, -3.5); });
		context.waitTicks(20);
		context.getInput().holdKeyFor(o -> o.keyUp, 20);
		context.waitTicks(22);
		boolean onlyW = server.computeOnServer(sv -> ((com.smeshariki.gameplay.character.CharacterEntity) player(sv).getVehicle()).isAbilityActive());
		for (int i = 0; i < 3; i++) {
			context.getInput().holdKeyFor(o -> o.keyUp, 2);
			context.waitTicks(3);
		}
		context.waitTicks(3);
		boolean tripleW = server.computeOnServer(sv -> ((com.smeshariki.gameplay.character.CharacterEntity) player(sv).getVehicle()).isAbilityActive());
		context.getInput().holdKey(o -> o.keyUp);
		context.waitTicks(3);
		context.getInput().holdKeyFor(o -> o.keyShift, 3);
		context.waitTicks(4);
		boolean combo = server.computeOnServer(sv -> ((com.smeshariki.gameplay.character.CharacterEntity) player(sv).getVehicle()).isAbilityActive());
		context.getInput().releaseKey(o -> o.keyUp);
		log("Y клубок Ёжика: только W " + onlyW + " (ждём false), тройное W " + tripleW + " (ждём false), W+Shift " + combo + " (ждём true); всё ещё вселён: " + vehicleChain(context, server));
	}

	static String describe(HitResult h) {
		if (h == null) return "null";
		if (h instanceof BlockHitResult b && h.getType() == HitResult.Type.BLOCK) return "BLOCK " + b.getBlockPos().toShortString();
		return h.getType().toString();
	}
}
