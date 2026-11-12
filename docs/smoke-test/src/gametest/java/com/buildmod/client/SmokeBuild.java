package com.buildmod.client;

import java.util.ArrayList;
import java.util.List;

import com.buildmod.net.BuildPayloads;

import net.minecraft.core.BlockPos;
import smoke.Case;

public class SmokeBuild {
	public static List<Case> cases() {
		BuildState s = new BuildState();
		for (int i = 0; i < 14; i++) s.buildings.add(new BuildPayloads.Buildings.Info(i + 1, "Постройка с длинным названием номер " + i, 5 + i, 4, 6, 100 + i));
		for (int i = 0; i < 12; i++) {
			var triggers = new ArrayList<BuildPayloads.Platforms.Trigger>();
			for (int t = 0; t < 7; t++) triggers.add(new BuildPayloads.Platforms.Trigger(BlockPos.asLong(10 + t, 64, 10), 3 * t - 6));
			s.platforms.add(new BuildPayloads.Platforms.Info(i + 1, "Платформа №" + (i + 1), 0, 64, 0, 4, 1, 4, i, triggers));
		}
		var offsets = new int[] {0, 2, 4, 6};
		var wallInfo = new BuildPayloads.Walls.Info(1, "Стена у озера", 0, 64, 0, 4, 5, 4, 0, offsets, false, 3, false, List.of(BlockPos.asLong(1, 64, 1)), List.of(BlockPos.asLong(2, 64, 2)));
		for (int i = 0; i < 12; i++) s.walls.add(new BuildPayloads.Walls.Info(i + 1, "", 0, 64, 0, 4, 5, 4, 0, offsets, i % 2 == 0, i, false, List.of(), List.of()));
		s.wallA = new BlockPos(0, 64, 0);
		s.wallB = new BlockPos(3, 68, 3);
		List<Case> out = new ArrayList<>();
		out.add(new Case("build.walls", mc -> mc.gui.setScreen(new WallsScreen(s))));
		out.add(new Case("build.wall.new", mc -> mc.gui.setScreen(new WallScreen(s, null))));
		out.add(new Case("build.wall.edit", mc -> mc.gui.setScreen(new WallScreen(s, wallInfo))));
		out.add(new Case("build.platforms", mc -> mc.gui.setScreen(new PlatformsScreen(s))));
		out.add(new Case("build.platform", mc -> mc.gui.setScreen(new PlatformScreen(s.platforms.get(0), BlockPos.of(s.platforms.get(0).triggers().get(0).pos()), "Привязка"))));
		out.add(new Case("build.buildings", mc -> mc.gui.setScreen(new BuildingsScreen(s, false))));
		out.add(new Case("build.buildings.rename", mc -> mc.gui.setScreen(new BuildingsScreen(s, true))));
		out.add(new Case("build.shapes", mc -> mc.gui.setScreen(new ShapeScreen(s, 0, false))));
		out.add(new Case("build.materials", mc -> mc.gui.setScreen(new MaterialsScreen())));
		out.add(new Case("build.color", mc -> mc.gui.setScreen(new ColorScreen(s, null))));
		out.add(new Case("build.paintings", mc -> mc.gui.setScreen(new PaintingsScreen())));
		out.add(new Case("build.painting.new", mc -> mc.gui.setScreen(new PaintingScreen())));
		return out;
	}
}
