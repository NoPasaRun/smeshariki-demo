package com.smeshariki.gameplay.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.smeshariki.behavior.companion.Companions;
import com.smeshariki.gameplay.api.Calls;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.character.Characters;
import com.smeshariki.gameplay.character.Possession;
import com.smeshariki.gameplay.character.SkillUse;
import com.smeshariki.gameplay.character.Singletons;
import com.smeshariki.gameplay.character.TimeSlow;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Проверка умений Совуньи, Лосяша, Бараша и Нюши (клавиша G шлёт пакет SkillUse: шлём его же). Строки SKILL в лог. */
public class SkillTest implements FabricClientGameTest {
	static void log(String s) {
		System.out.println("SKILL " + s);
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

	static ClientGameTestContext CTX;

	static void possess(TestServerContext server, EntityType<CharacterEntity> type) {
		for (int i = 0; i < 20; i++) {
			boolean done = server.computeOnServer(sv -> player(sv).getVehicle() instanceof CharacterEntity c && c.getType() == type);
			if (done) {
				log("вселён в " + BuiltInRegistries.ENTITY_TYPE.getKey(type));
				return;
			}
			server.runOnServer(sv -> Possession.swapTo(player(sv), type));
			CTX.waitTicks(10);
			log("   попытка " + i + ": " + server.computeOnServer(sv -> player(sv).gameMode() + " vehicle=" + player(sv).getVehicle() + " busy=" + Singletons.busy(sv, type, player(sv)) + " найден=" + Singletons.find(sv, type)));
		}
		log("НЕ ВСЕЛИЛСЯ в " + BuiltInRegistries.ENTITY_TYPE.getKey(type));
	}

	static void hold(TestServerContext server, ItemStack stack) {
		server.runOnServer(sv -> {
			ServerPlayer p = player(sv);
			for (int i = 0; i < p.getInventory().getContainerSize(); i++) p.getInventory().setItem(i, ItemStack.EMPTY);
			p.getInventory().setItem(p.getInventory().getSelectedSlot(), stack);
		});
	}

	static int count(ServerPlayer p, Item item) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(item)) n += p.getInventory().getItem(i).getCount();
		return n;
	}

	static void send(ClientGameTestContext context, String arg) {
		context.runOnClient(mc -> ClientPlayNetworking.send(new SkillUse(arg)));
	}

	static EntityType<CharacterEntity> type(String path) {
		return Characters.all().stream().filter(t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath().equals(path)).findFirst().orElseThrow();
	}

	void run(ClientGameTestContext context) throws Exception {
		CTX = context;
		try (TestSingleplayerContext w = context.worldBuilder().adjustSettings(st -> st.setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
			TestServerContext server = w.getServer();
			context.waitTicks(40);
			server.runCommand("gamemode survival @s");
			context.waitTicks(5);
			sovunya(context, server);
			losyash(context, server);
			barash(context, server);
			nyusha(context, server);
		}
	}

	void sovunya(ClientGameTestContext context, TestServerContext server) {
		possess(server, type("sovunya"));
		context.waitTicks(10);
		hold(server, new ItemStack(Items.IRON_INGOT, 40));
		for (int i = 0; i < 30; i++) {
			send(context, "");
			context.waitTicks(12);
		}
		Map<String, Integer> foods = server.computeOnServer(sv -> {
			Map<String, Integer> m = new HashMap<>();
			ServerPlayer p = player(sv);
			m.put("iron", count(p, Items.IRON_INGOT));
			for (EntityType<CharacterEntity> t : Characters.all()) m.merge(BuiltInRegistries.ITEM.getKey(CharacterEntity.favoriteOf(t)).getPath(), count(p, CharacterEntity.favoriteOf(t)), Integer::sum);
			return m;
		});
		log("S1 совунья: железа осталось " + foods.get("iron") + " (ждём 10), еда " + foods + ", свёклы (её любимое) " + foods.get("beetroot") + " (ждём 0)");
		hold(server, new ItemStack(Items.APPLE, 5));
		send(context, "");
		context.waitTicks(12);
		hold(server, new ItemStack(Items.IRON_SWORD, 1));
		send(context, "");
		context.waitTicks(12);
		log("S2 не неорганика (яблоко, железный меч) не трогает: " + server.computeOnServer(sv -> count(player(sv), Items.IRON_SWORD) + " меч, " + count(player(sv), Items.APPLE) + " яблок"));
	}

	void losyash(ClientGameTestContext context, TestServerContext server) {
		possess(server, type("losyash"));
		context.waitTicks(10);
		hold(server, new ItemStack(Items.PAPER, 3));
		send(context, "scheme:tarzan_rope");
		context.waitTicks(5);
		send(context, "scheme:balloon_burner");
		context.waitTicks(5);
		Item tarzan = BuiltInRegistries.ITEM.getValue(Identifier.parse("gameplaymod:scheme_tarzan_rope")), burner = BuiltInRegistries.ITEM.getValue(Identifier.parse("gameplaymod:scheme_balloon_burner"));
		log("S3 лосяш: бумаги " + server.computeOnServer(sv -> count(player(sv), Items.PAPER)) + " (ждём 1), схем тарзанки " + server.computeOnServer(sv -> count(player(sv), tarzan)) + ", горелки "
				+ server.computeOnServer(sv -> count(player(sv), burner)));
		hold(server, new ItemStack(Items.MAP, 1));
		send(context, "scheme:wind_marker");
		context.waitTicks(5);
		send(context, "scheme:wind_marker"); // нет бумаги — ничего
		context.waitTicks(5);
		Item wind = BuiltInRegistries.ITEM.getValue(Identifier.parse("gameplaymod:scheme_wind_marker"));
		log("S4 лосяш, пустая карта: схем ветра " + server.computeOnServer(sv -> count(player(sv), wind)) + " (ждём 1)");
		// рецепт: тарзанка без схемы не собирается, со схемой — да (крафт через менеджер рецептов)
		log("S5 рецепты: " + server.computeOnServer(sv -> {
			List<String> out = new ArrayList<>();
			for (var holder : sv.getRecipeManager().getRecipes()) {
				String id = holder.id().identifier().toString();
				if (id.startsWith("gameplaymod:") && !id.contains("slide") && !id.equals("gameplaymod:glasses")) out.add(id.substring(12));
			}
			return out;
		}));
	}

	void barash(ClientGameTestContext context, TestServerContext server) {
		possess(server, type("barash"));
		context.waitTicks(10);
		server.runCommand("summon pig ~6 ~ ~");
		context.waitTicks(10);
		int[] before = tickCounts(server);
		context.waitTicks(60);
		int[] after = tickCounts(server);
		log("S6 контроль, 60 тиков: свинья +" + (after[0] - before[0]) + ", бараш +" + (after[1] - before[1]) + ", игрок +" + (after[2] - before[2]));
		log("   мана до: " + server.computeOnServer(sv -> ((CharacterEntity) player(sv).getVehicle()).mana()));
		send(context, "");
		context.waitTicks(3);
		boolean clientActive = context.computeOnClient(mc -> TimeSlow.activeClient());
		before = tickCounts(server);
		context.waitTicks(60);
		after = tickCounts(server);
		log("S7 замедление, 60 тиков: свинья +" + (after[0] - before[0]) + " (ждём ~20), бараш +" + (after[1] - before[1]) + ", игрок +" + (after[2] - before[2]) + ", клиент знает: " + clientActive
				+ ", мана " + server.computeOnServer(sv -> ((CharacterEntity) player(sv).getVehicle()).mana()) + ", владелец " + server.computeOnServer(sv -> TimeSlow.isOwner((CharacterEntity) player(sv).getVehicle())));
		send(context, ""); // второй раз пока идёт — отказ
		context.waitTicks(380);
		boolean owner = server.computeOnServer(sv -> TimeSlow.isOwner((CharacterEntity) player(sv).getVehicle()));
		log("S8 после конца: владелец " + owner + ", мана " + server.computeOnServer(sv -> ((CharacterEntity) player(sv).getVehicle()).mana()) + ", клиент знает: " + context.computeOnClient(mc -> TimeSlow.activeClient()));
		before = tickCounts(server);
		context.waitTicks(60);
		after = tickCounts(server);
		log("S9 после, 60 тиков: свинья +" + (after[0] - before[0]) + " (ждём 60), мана " + server.computeOnServer(sv -> ((CharacterEntity) player(sv).getVehicle()).mana()));
		send(context, ""); // маны меньше пятой части — отказ
		context.waitTicks(5);
		log("S10 с малой маной не включилось: " + server.computeOnServer(sv -> !TimeSlow.isOwner((CharacterEntity) player(sv).getVehicle())));
	}

	static int[] tickCounts(TestServerContext server) {
		return server.computeOnServer(sv -> {
			int pig = -1;
			for (Entity e : sv.overworld().getAllEntities()) if (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("pig")) pig = e.tickCount;
			ServerPlayer p = player(sv);
			return new int[] { pig, p.getVehicle().tickCount, p.tickCount };
		});
	}

	void nyusha(ClientGameTestContext context, TestServerContext server) {
		log("S11 действия вызова: " + Calls.all().keySet());
		possess(server, type("nyusha"));
		context.waitTicks(10);
		EntityType<CharacterEntity> krosh = type("krosh_tailed");
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(krosh).toString();
		log("S12 Кроша до вызова: " + server.computeOnServer(sv -> Singletons.find(sv, krosh) != null));
		send(context, "call:" + id + "|call|");
		context.waitTicks(30);
		log("S13 после вызова: " + near(server, krosh));
		server.runOnServer(sv -> {
			CharacterEntity k = Singletons.find(sv, krosh);
			Singletons.move(k, (ServerLevel) k.level(), k.position().add(60, 0, 0), 0f);
		});
		context.waitTicks(20);
		log("S14 Крош отнесён на 60 блоков: " + near(server, krosh));
		send(context, "call:" + id + "|call|");
		context.waitTicks(30);
		log("S15 вызван снова: " + near(server, krosh));
		send(context, "call:" + id + "|behaviormod:talk|");
		context.waitTicks(10);
		send(context, "call:" + id + "|behaviormod:order|wait");
		context.waitTicks(10);
		log("S16 приказ «жди»: напарник " + server.computeOnServer(sv -> Companions.isCompanion(Singletons.find(sv, krosh))) + ", ждёт " + server.computeOnServer(sv -> Companions.waiting(Singletons.find(sv, krosh))));
		send(context, "call:" + id + "|behaviormod:order|follow");
		context.waitTicks(10);
		log("S17 приказ «за мной»: ждёт " + server.computeOnServer(sv -> Companions.waiting(Singletons.find(sv, krosh))));
		send(context, "call:" + id + "|behaviormod:order|dismiss");
		context.waitTicks(10);
		log("S18 «свободен»: напарник " + server.computeOnServer(sv -> Companions.isCompanion(Singletons.find(sv, krosh))));
		send(context, "call:gameplaymod:nyusha|call|"); // себя звать нельзя
		context.waitTicks(5);
	}

	static String near(TestServerContext server, EntityType<CharacterEntity> type) {
		return server.computeOnServer(sv -> {
			CharacterEntity c = Singletons.find(sv, type);
			if (c == null) return "нет";
			Vec3 n = player(sv).getVehicle().position();
			return String.format("есть, до Нюши %.1f блока", c.position().distanceTo(n));
		});
	}
}
