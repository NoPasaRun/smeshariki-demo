package com.smeshariki.mod.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.mod.versions.VersionsPayloads;

import com.smeshariki.mod.sync.FileEntry;
import com.smeshariki.mod.sync.GitRelease;
import com.smeshariki.mod.sync.SyncCategory;

import smoke.Case;

public class SmokeManagement {
	/** Автовключение ресурспака после установки с GitHub: если подложен {@code resourcepacks/smoke_pack.zip} (SMOKE_SEED), он должен стать выбранным и попасть в настройки. */
	public static void enablePack(net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext context) {
		java.util.concurrent.atomic.AtomicBoolean has = new java.util.concurrent.atomic.AtomicBoolean();
		context.runOnClient(mc -> has.set(java.nio.file.Files.exists(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("resourcepacks/smoke_pack.zip"))));
		if (!has.get()) {
			System.out.println("SMOKE PACK SKIP (нет resourcepacks/smoke_pack.zip)");
			return;
		}
		context.runOnClient(mc -> GitUpdate.enablePacks(mc, java.util.List.of("smoke_pack.zip")));
		context.waitTicks(100);
		context.runOnClient(mc -> System.out.println("SMOKE PACK " + (mc.options.resourcePacks.contains("file/smoke_pack.zip") && mc.getResourcePackRepository().getSelectedIds().contains("file/smoke_pack.zip") ? "OK" : "FAIL")
				+ " options=" + mc.options.resourcePacks));
		// повторно — уже включён: не дублируется
		context.runOnClient(mc -> GitUpdate.enablePacks(mc, java.util.List.of("smoke_pack.zip")));
		context.waitTicks(100);
		context.runOnClient(mc -> System.out.println("SMOKE PACK again " + (mc.options.resourcePacks.stream().filter("file/smoke_pack.zip"::equals).count() == 1 ? "OK" : "FAIL")));
	}

	public static List<Case> cases() {
		VersionsClient v = VersionsClient.INSTANCE;
		v.versions = new ArrayList<>();
		for (int i = 1; i <= 15; i++) v.versions.add(new VersionsPayloads.Versions.Info(i, "Версия " + i, System.currentTimeMillis() - i * 3600000L, "bogdan", i, 100, 5_000_000L * i));
		v.head = 15;
		List<Case> out = new ArrayList<>();
		out.add(new Case("management.versions", mc -> mc.gui.setScreen(new VersionsScreen(v))));
		out.add(new Case("management.git_offer", mc -> mc.gui.setScreen(new SyncScreen(gitSession(true), null, () -> mc.gui.setScreen(null)))));
		out.add(new Case("management.git_offer_no_world", mc -> mc.gui.setScreen(new SyncScreen(gitSession(false), null, () -> mc.gui.setScreen(null)))));
		out.add(new Case("management.git_offer_never", mc -> {
			GitSession s = gitSession(true);
			s.setExtra(() -> net.minecraft.network.chat.Component.translatable("smesharikimod.loader.never"), () -> true);
			mc.gui.setScreen(new SyncScreen(s, null, () -> mc.gui.setScreen(null)));
		}));
		out.add(new Case("management.git_check", mc -> {
			GitCheckScreen s = new GitCheckScreen(null, new com.smeshariki.mod.sync.SyncConfig());
			mc.gui.setScreen(s);
			s.upToDate("v0.5.2");
		}));
		return out;
	}

	/** Предложение установки с GitHub: шесть файлов во всех четырёх разделах, среди них мир; {@code world} — скачивать ли его. */
	private static GitSession gitSession(boolean world) {
		String sum = "0".repeat(64);
		java.util.List<GitRelease.Entry> entries = new ArrayList<>();
		entries.add(new GitRelease.Entry(new FileEntry(SyncCategory.MOD, "gameplaymod-1.0.0.jar", 1_708_298L, sum, "", "v0"), "", ""));
		entries.add(new GitRelease.Entry(new FileEntry(SyncCategory.MOD, "levelmod-1.0.0.jar", 517_077L, sum, "", "v0"), "", ""));
		entries.add(new GitRelease.Entry(new FileEntry(SyncCategory.RESOURCE_PACK, "Smeshariki_Resources.zip", 3_200_000L, sum, "", "v0"), "", ""));
		entries.add(new GitRelease.Entry(new FileEntry(SyncCategory.SHADER_PACK, "Smeshariki_Shaders.zip", 22_696L, sum, "", "v0"), "", ""));
		entries.add(new GitRelease.Entry(new FileEntry(SyncCategory.SHADER_SETTINGS, "Smeshariki_Shaders.zip.txt", 301L, sum, "", "v0"), "", ""));
		entries.add(new GitRelease.Entry(new FileEntry(SyncCategory.WORLD, "world.zip", 55_000_000L, sum, "", "v0"), "", "Ромашковая Долина"));
		GitSession session = new GitSession("https://api.github.com", "NoPasaRun/smeshariki-demo",
			new GitRelease.Plan("v0.5.2", entries, java.util.List.of("smesharikiworld-1.0.0.jar")), java.nio.file.Path.of("."));
		session.setSectionSelected(GitSection.WORLD, world);
		return session;
	}
}
