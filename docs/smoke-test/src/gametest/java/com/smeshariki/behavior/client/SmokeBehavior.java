package com.smeshariki.behavior.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import smoke.Case;

public class SmokeBehavior {
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
