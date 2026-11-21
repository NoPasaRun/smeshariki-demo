package com.smeshariki.gameplay.client;

import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.character.Characters;
import com.smeshariki.gameplay.character.Possession;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;

/** Вселение в выживании (как в skill-test). */
final class SeatHelper {
	private SeatHelper() {
	}

	static void possess(ClientGameTestContext context, TestServerContext server, String path) {
		EntityType<CharacterEntity> type = Characters.all().stream().filter(t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath().equals(path)).findFirst().orElseThrow();
		for (int i = 0; i < 20; i++) {
			boolean done = server.computeOnServer(sv -> sv.getPlayerList().getPlayers().getFirst().getVehicle() instanceof CharacterEntity c && c.getType() == type);
			if (done) return;
			server.runOnServer(sv -> Possession.swapTo(sv.getPlayerList().getPlayers().getFirst(), type));
			context.waitTicks(10);
		}
		RaftPushTest.log("НЕ ВСЕЛИЛСЯ в " + path);
	}
}
