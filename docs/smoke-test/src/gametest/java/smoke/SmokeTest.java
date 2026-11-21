package smoke;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

public class SmokeTest implements FabricClientGameTest {
	private static void log(String s) {
		System.out.println("SMOKE " + s);
	}

	private static String desc(AbstractWidget w) {
		String t = w.getMessage().getString();
		return w.getClass().getSimpleName() + "[" + w.getX() + "," + w.getY() + " " + w.getWidth() + "x" + w.getHeight() + " '" + (t.length() > 24 ? t.substring(0, 24) : t) + "']";
	}

	/** Границы и накладки виджетов экрана. */
	private static String layout(Screen s) {
		List<AbstractWidget> ws = new ArrayList<>();
		for (var c : s.children()) if (c instanceof AbstractWidget w && w.visible) ws.add(w);
		StringBuilder b = new StringBuilder();
		int out = 0, over = 0;
		for (AbstractWidget w : ws) {
			if (w.getX() < 0 || w.getY() < 0 || w.getX() + w.getWidth() > s.width || w.getY() + w.getHeight() > s.height || w.getWidth() <= 0) {
				if (out++ < 6) b.append("\n      OUT ").append(desc(w));
			}
		}
		for (int i = 0; i < ws.size(); i++) {
			for (int j = i + 1; j < ws.size(); j++) {
				AbstractWidget a = ws.get(i), c = ws.get(j);
				int ix = Math.min(a.getX() + a.getWidth(), c.getX() + c.getWidth()) - Math.max(a.getX(), c.getX());
				int iy = Math.min(a.getY() + a.getHeight(), c.getY() + c.getHeight()) - Math.max(a.getY(), c.getY());
				if (ix > 0 && iy > 0) {
					if (over++ < 6) b.append("\n      OVERLAP ").append(desc(a)).append(" x ").append(desc(c));
				}
			}
		}
		return "widgets=" + ws.size() + " out=" + out + " overlap=" + over + b;
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		for (String id : new String[] {"gameplaymod", "smesharikimod", "worldmod", "behaviormod", "buildmod", "levelmod"})
			if (!FabricLoader.getInstance().isModLoaded(id)) log("MOD MISSING " + id);
		// SMOKE_SEED=<папка>: её содержимое кладётся в папку игры до создания мира (Gradle очищает папку запуска, подложить заранее нельзя)
		String seed = System.getenv("SMOKE_SEED");
		if (seed != null && !seed.isEmpty()) {
			java.nio.file.Path from = java.nio.file.Path.of(seed), to = FabricLoader.getInstance().getGameDir();
			try (var walk = java.nio.file.Files.walk(from)) {
				for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk::iterator) {
					java.nio.file.Path t = to.resolve(from.relativize(p).toString());
					if (java.nio.file.Files.isDirectory(p)) java.nio.file.Files.createDirectories(t);
					else java.nio.file.Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				log("SEEDED " + from + " -> " + to);
			} catch (java.io.IOException e) {
				throw new RuntimeException("SMOKE_SEED", e);
			}
		}
		try (var world = context.worldBuilder().adjustSettings(st -> st.setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
			context.waitTicks(60);
			com.smeshariki.behavior.client.SmokeBehavior.musicPack(context);
			com.smeshariki.mod.client.SmokeManagement.enablePack(context);
			List<Case> cases = new ArrayList<>();
			cases.addAll(com.buildmod.client.SmokeBuild.cases());
			cases.addAll(com.smeshariki.mod.client.SmokeManagement.cases());
			cases.addAll(com.smeshariki.behavior.client.SmokeBehavior.cases());
			cases.addAll(com.smeshariki.level.client.SmokeLevel.cases());
			// (ширина окна, высота окна, масштаб GUI, язык): 960x720 при масштабе 3 даёт минимальный GUI 320x240
			Object[][] passes = {{960, 720, 3, "ru_ru"}, {854, 480, 2, "ru_ru"}, {960, 720, 3, "en_us"}};
			AtomicReference<String> info = new AtomicReference<>();
			int pass = -1;
			for (Object[] cfg : passes) {
				pass++;
				final int pw = (Integer) cfg[0], ph = (Integer) cfg[1], psc = (Integer) cfg[2];
				final String lang = (String) cfg[3];
				context.runOnClient(mc -> {
					mc.getWindow().setWindowed(pw, ph);
					mc.options.guiScale().set(psc);
					if (!mc.getLanguageManager().getSelected().equals(lang)) {
						mc.getLanguageManager().setSelected(lang);
						mc.options.languageCode = lang;
						mc.reloadResourcePacks();
					}
				});
				String expect = lang.equals("ru_ru") ? "Закрыть" : "Close";
				for (int w = 0; w < 80; w++) {
					context.waitTicks(5);
					AtomicReference<String> now = new AtomicReference<>();
					context.runOnClient(mc -> now.set(net.minecraft.client.resources.language.I18n.get("gui.uilib.close")));
					if (now.get().equals(expect)) break;
				}
				context.runOnClient(mc -> mc.resizeGui());
				context.waitTicks(10);
				context.runOnClient(mc -> info.set(mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + " gui=" + mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight() + " scale=" + mc.getWindow().getGuiScale()));
				log("=== pass " + pass + " lang=" + cfg[3] + " close=" + net.minecraft.client.resources.language.I18n.get("gui.uilib.close") + " window " + info.get());
				for (Case c : cases) {
					try {
						context.runOnClient(mc -> c.open().accept(mc));
						context.waitTicks(4);
						AtomicReference<String> r = new AtomicReference<>();
						context.runOnClient(mc -> r.set(mc.gui.screen() == null ? "CLOSED" : mc.gui.screen().getClass().getSimpleName() + " " + mc.gui.screen().width + "x" + mc.gui.screen().height + " " + layout(mc.gui.screen())));
						log(c.name() + " -> " + r.get());
						context.runOnClient(mc -> {
							Screen s = mc.gui.screen();
							if (s == null) return;
							for (int i = 0; i < 3; i++) s.mouseScrolled(s.width / 2.0, s.height / 2.0, 0, -1);
							for (int i = 0; i < 3; i++) s.mouseScrolled(10, s.height / 2.0, 0, 1);
						});
						context.waitTicks(3);
						if (pass != 1) context.takeScreenshot("p" + pass + "_" + c.name().replace('.', '_'));
						context.runOnClient(mc -> r.set(mc.gui.screen() == null ? "CLOSED" : mc.gui.screen().getClass().getSimpleName()));
						log(c.name() + " after scroll -> " + r.get());
						context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);
						context.waitTicks(3);
						context.runOnClient(mc -> r.set(mc.gui.screen() == null ? "closed" : mc.gui.screen().getClass().getSimpleName()));
						log(c.name() + " after Esc -> " + r.get());
						context.runOnClient(mc -> mc.gui.setScreen(null));
					} catch (Throwable t) {
						log(c.name() + " FAILED " + t);
						context.runOnClient(mc -> mc.gui.setScreen(null));
					}
				}
			}
			log("DONE");
		}
	}
}
