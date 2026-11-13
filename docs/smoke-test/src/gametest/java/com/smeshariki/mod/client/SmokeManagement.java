package com.smeshariki.mod.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.mod.versions.VersionsPayloads;

import smoke.Case;

public class SmokeManagement {
	public static List<Case> cases() {
		VersionsClient v = VersionsClient.INSTANCE;
		v.versions = new ArrayList<>();
		for (int i = 1; i <= 15; i++) v.versions.add(new VersionsPayloads.Versions.Info(i, "Версия " + i, System.currentTimeMillis() - i * 3600000L, "bogdan", i, 100, 5_000_000L * i));
		v.head = 15;
		List<Case> out = new ArrayList<>();
		out.add(new Case("management.versions", mc -> mc.gui.setScreen(new VersionsScreen(v))));
		return out;
	}
}
