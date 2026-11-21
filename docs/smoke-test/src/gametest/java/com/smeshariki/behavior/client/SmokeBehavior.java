package com.smeshariki.behavior.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import smoke.Case;

public class SmokeBehavior {
	/**
	 * Музыка в паке мира: если в папке музыки есть {@code smoke_test.mp3} (подкладывается через SMOKE_SEED), сохранение сцены с ним должно дописать ogg
	 * в {@code <мир>/resourcepacks/resources.zip}, переподключить пак, и звук должен стать виден звуковому движку.
	 */
	public static void musicPack(net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext context) {
		java.util.concurrent.atomic.AtomicReference<net.minecraft.resources.Identifier> id = new java.util.concurrent.atomic.AtomicReference<>();
		context.runOnClient(mc -> id.set(WorldMusicPack.soundFor("smoke_test.mp3")));
		if (id.get() == null) {
			System.out.println("SMOKE MUSIC SKIP (нет smoke_test.mp3)");
			return;
		}
		context.runOnClient(mc -> {
			var key = new com.smeshariki.behavior.cutscene.MusicKey(0, 0, com.smeshariki.behavior.cutscene.MusicKey.NONE, 1f, 0, -1, "smoke_test.mp3");
			var scene = WorldMusicPack.prepare(com.smeshariki.behavior.cutscene.Cutscene.empty(100, null).withMusic(List.of(key)));
			System.out.println("SMOKE MUSIC id " + scene.music().getFirst().sound());
			WorldMusicPack.export(scene);
		});
		java.util.concurrent.atomic.AtomicBoolean ok = new java.util.concurrent.atomic.AtomicBoolean();
		for (int i = 0; i < 120 && !ok.get(); i++) {
			context.waitTicks(10);
			context.runOnClient(mc -> ok.set(WorldMusicPack.loaded(id.get())));
		}
		context.runOnClient(mc -> {
			var zip = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.MAP_RESOURCE_FILE);
			System.out.println("SMOKE MUSIC " + (ok.get() ? "OK" : "FAIL") + " " + id.get() + " zip=" + zip + " exists=" + java.nio.file.Files.exists(zip));
		});
		// второе сохранение того же файла ничего не дописывает
		context.runOnClient(mc -> WorldMusicPack.export(WorldMusicPack.prepare(com.smeshariki.behavior.cutscene.Cutscene.empty(100, null)
				.withMusic(List.of(new com.smeshariki.behavior.cutscene.MusicKey(0, 0, com.smeshariki.behavior.cutscene.MusicKey.NONE, 1f, 0, -1, "smoke_test.mp3"))))));
		context.waitTicks(40);
	}

	public static List<Case> cases() {
		List<Case> out = new ArrayList<>();
		out.add(new Case("behavior.music", mc -> mc.gui.setScreen(new MusicPickerScreen())));
		out.add(new Case("behavior.scenes", mc -> mc.gui.setScreen(new CutsceneManagerScreen())));
		out.add(new Case("ui.prompt", mc -> com.smeshariki.ui.PromptScreen.open(Component.literal("Строка ввода"), Component.literal("подсказка к строке"), "значение", v -> { })));
		List<String> many = new ArrayList<>();
		for (int i = 0; i < 40; i++) many.add("Вариант " + i);
		out.add(new Case("ui.select", mc -> com.smeshariki.ui.SelectScreen.open(null, Component.literal("Выбор"), many, Component::literal, "Вариант 3", v -> { })));
		return out;
	}
}
