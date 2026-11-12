package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import smoke.Case;

public class SmokeLevel {
	public static List<Case> cases() {
		LevelDraft d = LevelDraft.blank();
		d.title = "Тестовый уровень";
		LevelDraft.MissionDraft m1 = d.addMission();
		LevelDraft.MissionDraft m2 = d.addMission();
		for (int i = 0; i < 4; i++) d.addTask(m1);
		for (int i = 0; i < 6; i++) d.addTask(m2);
		for (int i = 0; i < 9; i++) {
			LevelDraft.CheckpointDraft c = new LevelDraft.CheckpointDraft();
			c.location = "точка_" + i;
			m1.checkpoints.add(c);
		}
		for (int i = 0; i < 12; i++) d.rules.add(new LevelDraft.MechanicRule());
		for (int i = 0; i < 6; i++) m1.rules.add(new LevelDraft.MechanicRule());
		if (!m1.checkpoints.isEmpty()) {
			LevelDraft.CheckpointDraft cp = m1.checkpoints.get(0);
			for (String id : new String[] {"minecraft:stone", "minecraft:dirt", "minecraft:water", "minecraft:oak_log", "minecraft:glass"}) cp.resets.add(new com.smeshariki.level.task.ResetCell(com.smeshariki.level.task.ResetCell.Kind.BLOCK, net.minecraft.resources.Identifier.parse(id)));
			cp.resets.add(new com.smeshariki.level.task.ResetCell(com.smeshariki.level.task.ResetCell.Kind.ENTITY, net.minecraft.resources.Identifier.parse("minecraft:cow")));
		}
		for (int i = 0; i < 12; i++) {
			LevelDraft.WatchDraft w = new LevelDraft.WatchDraft();
			w.id = "w" + i;
			w.conditions.add(ConditionDraft.blank());
			d.watches.add(w);
		}
		List<ConditionDraft> conditions = new ArrayList<>();
		for (int i = 0; i < 12; i++) conditions.add(ConditionDraft.blank());
		List<Case> out = new ArrayList<>();
		out.add(new Case("level.manager", mc -> mc.gui.setScreen(new LevelManagerScreen())));
		out.add(new Case("level.editor", mc -> LevelEditorScreen.open(mc, d)));
		out.add(new Case("level.tasks", mc -> {
			ClientLevels.draft = d;
			ClientLevels.draftMission = m1;
			ClientLevels.draftTask = null;
			mc.gui.setScreen(new TaskEditorScreen(d));
		}));
		out.add(new Case("level.tasks.task", mc -> {
			ClientLevels.draft = d;
			ClientLevels.draftMission = m1;
			ClientLevels.draftTask = m1.tasks.get(0);
			mc.gui.setScreen(new TaskEditorScreen(d));
		}));
		out.add(new Case("level.checkpoints", mc -> mc.gui.setScreen(new CheckpointsScreen(d, m1))));
		out.add(new Case("level.checkpoint", mc -> mc.gui.setScreen(new CheckpointScreen(null, m1.checkpoints.get(0), 1))));
		out.add(new Case("level.rules", mc -> mc.gui.setScreen(new MechanicRulesScreen(d))));
		out.add(new Case("level.rules_mission", mc -> mc.gui.setScreen(new MechanicRulesScreen(d, m1))));
		out.add(new Case("level.watches", mc -> mc.gui.setScreen(new WatchesScreen(d))));
		out.add(new Case("level.conditions", mc -> mc.gui.setScreen(new ConditionsScreen(Component.literal("задача"), conditions, () -> { }))));
		out.add(new Case("level.picker", mc -> mc.gui.setScreen(new LocationPickerScreen(null, false, name -> { }))));
		out.add(new Case("level.locations", mc -> mc.gui.setScreen(new LocationsScreen())));
		out.add(new Case("level.groups", mc -> mc.gui.setScreen(new GroupsScreen())));
		out.add(new Case("level.mission_details", mc -> mc.gui.setScreen(new MissionDetailsScreen())));
		return out;
	}
}
