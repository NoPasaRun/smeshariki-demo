package com.smeshariki.mod.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.mod.versions.VersionsPayloads;

import com.smeshariki.mod.sync.FileEntry;
import com.smeshariki.mod.sync.GitRelease;
import com.smeshariki.mod.sync.SyncCategory;

import smoke.Case;

public class SmokeManagement {
	public static List<Case> cases() {
		VersionsClient v = VersionsClient.INSTANCE;
		v.versions = new ArrayList<>();
		for (int i = 1; i <= 15; i++) v.versions.add(new VersionsPayloads.Versions.Info(i, "Версия " + i, System.currentTimeMillis() - i * 3600000L, "bogdan", i, 100, 5_000_000L * i));
		v.head = 15;
		List<Case> out = new ArrayList<>();
		out.add(new Case("management.versions", mc -> mc.gui.setScreen(new VersionsScreen(v))));
		out.add(new Case("management.git_offer", mc -> mc.gui.setScreen(new SyncScreen(gitSession(true), null, () -> mc.gui.setScreen(null)))));
		out.add(new Case("management.git_offer_no_world", mc -> mc.gui.setScreen(new SyncScreen(gitSession(false), null, () -> mc.gui.setScreen(null)))));
		return out;
	}

	/** Предложение установки с GitHub: шесть файлов, среди них мир; {@code world} — скачивать ли его. */
	private static GitSession gitSession(boolean world) {
		String sum = "0".repeat(64);
		java.util.List<GitRelease.Entry> entries = new ArrayList<>();
		entries.add(new GitRelease.Entry(new FileEntry(SyncCategory.MOD, "gameplaymod-1.0.0.jar", 1_708_298L, sum, "", "v0"), "", ""));
		entries.add(new GitRelease.Entry(new FileEntry(SyncCategory.MOD, "levelmod-1.0.0.jar", 517_077L, sum, "", "v0"), "", ""));
		entries.add(new GitRelease.Entry(new FileEntry(SyncCategory.SHADER_PACK, "Smeshariki_Shaders.zip", 22_696L, sum, "", "v0"), "", ""));
		entries.add(new GitRelease.Entry(new FileEntry(SyncCategory.SHADER_SETTINGS, "Smeshariki_Shaders.zip.txt", 301L, sum, "", "v0"), "", ""));
		entries.add(new GitRelease.Entry(new FileEntry(SyncCategory.WORLD, "world.zip", 55_000_000L, sum, "", "v0"), "", "Ромашковая Долина"));
		GitSession session = new GitSession("https://api.github.com", "NoPasaRun/smeshariki-demo",
			new GitRelease.Plan("v0.5.2", entries, java.util.List.of("smesharikiworld-1.0.0.jar")), java.nio.file.Path.of("."));
		session.setWorldSelected(world);
		return session;
	}
}
