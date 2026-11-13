package sim;

import java.nio.file.Files;
import java.nio.file.Path;

import com.smeshariki.mod.client.GitSession;
import com.smeshariki.mod.client.SyncSession;
import com.smeshariki.mod.sync.GitRelease;

/** Установка из настоящего релиза на GitHub в пустую временную папку игры. */
public final class RealGit {
	public static void main(String[] args) throws Exception {
		Path game = Files.createTempDirectory("realgit");
		GitRelease.Plan plan = GitRelease.fetch("https://api.github.com", "NoPasaRun/smeshariki-demo", game);
		System.out.println("тег " + plan.tag() + ", к установке: " + plan.entries().stream().map(e -> e.file().name() + "[" + e.file().category() + "]").toList());
		GitSession s = new GitSession("https://api.github.com", "NoPasaRun/smeshariki-demo", plan, game);
		s.start();
		for (int i = 0; i < 1200 && s.state() != SyncSession.State.DONE; i++) {
			Thread.sleep(500);
			if (i % 10 == 0) System.out.println("  " + s.doneBytes() / 1048576 + " / " + s.totalBytes() / 1048576 + " МБ, файл " + s.currentName());
		}
		System.out.println("итог: " + s.state() + ", установлено " + s.installed() + " из " + plan.entries().size() + ", ошибки " + s.errors());
		try (var mods = Files.list(game.resolve("mods"))) {
			System.out.println("mods: " + mods.map(p -> p.getFileName().toString()).sorted().toList());
		}
		System.out.println("мир: level.dat=" + Files.exists(game.resolve("saves/Ромашковая Долина/level.dat")) + " places=" + Files.exists(game.resolve("saves/Ромашковая Долина/data/levelmod/places.json")));
		GitRelease.Plan again = GitRelease.fetch("https://api.github.com", "NoPasaRun/smeshariki-demo", game);
		System.out.println("повторная проверка: к установке " + again.entries().size());
		System.exit(s.errors().isEmpty() && again.entries().isEmpty() ? 0 : 1);
	}
}
