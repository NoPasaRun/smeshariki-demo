package sim;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.smeshariki.mod.client.GitSession;
import com.smeshariki.mod.sync.GitRelease;
import com.sun.net.httpserver.HttpServer;

public final class GitTest {
	static int checks, failures;

	static void check(String what, boolean ok) {
		checks++;
		if (!ok) failures++;
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
	}

	static String sha(byte[] d) throws Exception {
		StringBuilder b = new StringBuilder();
		for (byte x : MessageDigest.getInstance("SHA-256").digest(d)) b.append(String.format("%02x", x));
		return b.toString();
	}

	static byte[] zip(Map<String, String> files) throws Exception {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(o)) {
			for (var e : files.entrySet()) {
				z.putNextEntry(new ZipEntry(e.getKey()));
				z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
				z.closeEntry();
			}
		}
		return o.toByteArray();
	}

	static void run(GitSession s) throws Exception {
		s.start();
		for (int i = 0; i < 300 && s.state() != com.smeshariki.mod.client.SyncSession.State.DONE; i++) Thread.sleep(50);
	}

	public static void main(String[] args) throws Exception {
		byte[] jar = "JAR-CONTENT-v2".getBytes(StandardCharsets.UTF_8);
		byte[] world = zip(Map.of("Test World/level.dat", "LEVEL", "Test World/region/r.0.0.mca", "REGION"));
		byte[] evil = zip(Map.of("../evil.txt", "x", "W/level.dat", "L"));
		Map<String, byte[]> files = new HashMap<>();
		files.put("levelmod-1.0.0.jar", jar);
		files.put("world.zip", world);
		files.put("badsum.jar", "BAD".getBytes());
		files.put("evil.zip", evil);
		byte[] st = "BLOOM=1.0".getBytes(StandardCharsets.UTF_8);
		files.put("Pack.zip.txt", st);
		files.put("Mine.zip.txt", st);
		String manifest = "{\"files\":[{\"name\":\"levelmod-1.0.0.jar\",\"category\":\"mod\",\"size\":" + jar.length + ",\"sha256\":\"" + sha(jar) + "\"},"
				+ "{\"name\":\"world.zip\",\"category\":\"world\",\"world\":\"Test World\",\"size\":" + world.length + ",\"sha256\":\"" + sha(world) + "\"},"
				+ "{\"name\":\"Pack.zip.txt\",\"category\":\"shadersettings\",\"size\":" + st.length + ",\"sha256\":\"" + sha(st) + "\"},"
				+ "{\"name\":\"Mine.zip.txt\",\"category\":\"shadersettings\",\"size\":" + st.length + ",\"sha256\":\"" + sha(st) + "\"},"
				+ "{\"name\":\"badsum.jar\",\"category\":\"mod\",\"size\":3,\"sha256\":\"" + "0".repeat(64) + "\"},"
				+ "{\"name\":\"evil.zip\",\"category\":\"world\",\"world\":\"Evil\",\"size\":" + evil.length + ",\"sha256\":\"" + sha(evil) + "\"}],\"obsolete\":[\"smesharikiworld-1.0.0.jar\",\"installermod-1.0.0.jar\"]}";
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		int port = server.getAddress().getPort();
		String base = "http://127.0.0.1:" + port;
		String assets = "[{\"name\":\"manifest.json\",\"browser_download_url\":\"" + base + "/dl/manifest.json\"}";
		for (String n : files.keySet()) assets += ",{\"name\":\"" + n + "\",\"browser_download_url\":\"" + base + "/redir/" + n + "\"}";
		assets += "]";
		String release = "{\"tag_name\":\"v1\",\"assets\":" + assets + "}";
		server.createContext("/repos/o/r/releases/latest", ex -> send(ex, release.getBytes(StandardCharsets.UTF_8)));
		server.createContext("/dl/manifest.json", ex -> send(ex, manifest.getBytes(StandardCharsets.UTF_8)));
		server.createContext("/redir/", ex -> {
			ex.getResponseHeaders().add("Location", "/files/" + ex.getRequestURI().getPath().substring("/redir/".length()));
			ex.sendResponseHeaders(302, -1);
			ex.close();
		});
		server.createContext("/files/", ex -> send(ex, files.get(ex.getRequestURI().getPath().substring("/files/".length()))));
		server.start();
		try {
			Path game = Files.createTempDirectory("gitgame");
			Files.createDirectories(game.resolve("mods"));
			Files.writeString(game.resolve("mods").resolve("levelmod-1.0.0.jar"), "OLD-JAR");
			Files.writeString(game.resolve("mods").resolve("smesharikiworld-1.0.0.jar"), "OLD-WORLD-MOD");
			Files.createDirectories(game.resolve("shaderpacks"));
			Files.writeString(game.resolve("shaderpacks").resolve("Mine.zip.txt"), "MY-OWN-SETTINGS");
			GitRelease.Plan plan = GitRelease.fetch(base, "o/r", game);
			check("устаревший мод на диске найден, отсутствующий (installermod) игнорируется", plan.obsolete().equals(java.util.List.of("smesharikiworld-1.0.0.jar")));
			System.out.println("план: " + plan.entries().stream().map(e -> e.file().name()).toList());
			check("тег релиза прочитан", plan.tag().equals("v1"));
			check("в плане 5 файлов (jar отличается, мира нет, настройки шейдера Pack нет; свои настройки Mine не трогаем)", plan.entries().size() == 5 && plan.entries().stream().noneMatch(e -> e.file().name().equals("Mine.zip.txt")));
			GitSession session = new GitSession(base, "o/r", plan, game);
			check("замена отмечена как обновление", session.items().stream().anyMatch(i -> i.entry().name().equals("levelmod-1.0.0.jar") && i.isUpdate()));
			run(session);
			System.out.println("ошибки: " + session.errors());
			check("jar поставлен, содержимое новое", Files.readString(game.resolve("mods").resolve("levelmod-1.0.0.jar")).equals("JAR-CONTENT-v2"));
			check("старый jar сохранён как .smesharikimod-old", Files.readString(game.resolve("mods").resolve("levelmod-1.0.0.jar.smesharikimod-old")).equals("OLD-JAR"));
			check("устаревший мод отключён: переименован в .smesharikimod-old, не удалён", !Files.exists(game.resolve("mods/smesharikiworld-1.0.0.jar")) && Files.readString(game.resolve("mods/smesharikiworld-1.0.0.jar.smesharikimod-old")).equals("OLD-WORLD-MOD"));
			check("мир распакован в saves/Test World", Files.exists(game.resolve("saves/Test World/level.dat")) && Files.exists(game.resolve("saves/Test World/region/r.0.0.mca")));
			check("файл с неверной суммой не поставлен", !Files.exists(game.resolve("mods/badsum.jar")) && session.errors().stream().anyMatch(e -> e.startsWith("badsum.jar")));
			check("zip-slip отвергнут: ничего не вышло за saves", !Files.exists(game.resolve("evil.txt")) && !Files.exists(game.resolve("saves/evil.txt")) && !Files.exists(game.resolve("saves/Evil")) && session.errors().stream().anyMatch(e -> e.startsWith("evil.zip")));
			check("временных папок и .part не осталось", Files.list(game.resolve("saves")).noneMatch(p -> p.getFileName().toString().startsWith(".smesharikimod")) && Files.list(game.resolve("mods")).noneMatch(p -> p.toString().endsWith(".smesharikimod-part")));
			check("настройки шейдера поставлены, чужие не затёрты", Files.readString(game.resolve("shaderpacks/Pack.zip.txt")).equals("BLOOM=1.0") && Files.readString(game.resolve("shaderpacks/Mine.zip.txt")).equals("MY-OWN-SETTINGS"));
			check("установлено 3 из 5, итог DONE", session.installed() == 3 && session.state() == com.smeshariki.mod.client.SyncSession.State.DONE);
			GitRelease.Plan again = GitRelease.fetch(base, "o/r", game);
			System.out.println("повторный план: " + again.entries().stream().map(e -> e.file().name()).toList());
			check("повторная проверка: поставленное не предлагается (остались неудавшиеся)", again.entries().stream().noneMatch(e -> e.file().name().equals("levelmod-1.0.0.jar") || e.file().name().equals("world.zip")));
			GitRelease.Plan afterObsolete = GitRelease.fetch(base, "o/r", game);
			check("после отключения устаревших повторно они не предлагаются", afterObsolete.obsolete().isEmpty());
			// мир можно не скачивать: отказ от мира не трогает остальное
			Path game3 = Files.createTempDirectory("gitgame3");
			GitSession s3 = new GitSession(base, "o/r", GitRelease.fetch(base, "o/r", game3), game3);
			check("по умолчанию все разделы включены, мир входит в размер", s3.sections().contains(com.smeshariki.mod.client.GitSection.WORLD) && s3.sectionSelected(com.smeshariki.mod.client.GitSection.WORLD) && s3.selectedCount() == 6);
			check("разделы: только те, что есть в плане, по порядку Мир, Моды, Ресурспаки, Шейдерпаки", s3.sections().equals(java.util.Arrays.stream(com.smeshariki.mod.client.GitSection.values()).filter(sec -> s3.items().stream().anyMatch(i -> com.smeshariki.mod.client.GitSection.of(i.entry().category()) == sec)).toList()));
			long withWorld = s3.totalBytes();
			java.util.Set<com.smeshariki.mod.client.GitSection> remembered = new java.util.HashSet<>();
			s3.onChoice(remembered::addAll);
			s3.setSectionSelected(com.smeshariki.mod.client.GitSection.WORLD, false);
			check("без мира: 4 файла из 6, размер меньше на оба архива мира", s3.selectedCount() == 4 && s3.totalBytes() == withWorld - world.length - evil.length);
			s3.setSectionSelected(com.smeshariki.mod.client.GitSection.MODS, false);
			check("без мира и модов: шейдерпаки только", s3.items().stream().filter(s3::isSelected).allMatch(i -> com.smeshariki.mod.client.GitSection.of(i.entry().category()) == com.smeshariki.mod.client.GitSection.SHADER_PACKS));
			s3.setSectionSelected(com.smeshariki.mod.client.GitSection.MODS, true);
			run(s3);
			check("выключенные разделы переданы на запоминание (только мир)", remembered.equals(java.util.Set.of(com.smeshariki.mod.client.GitSection.WORLD)));
			check("мир не скачан, остальное поставлено", !Files.exists(game3.resolve("saves")) && Files.exists(game3.resolve("mods/levelmod-1.0.0.jar")) && Files.exists(game3.resolve("shaderpacks/Pack.zip.txt")));
			check("без мира: поставлено 3, ошибка только у файла с неверной суммой", s3.installed() == 3 && s3.errors().size() == 1 && s3.errors().get(0).startsWith("badsum.jar"));
			// все разделы выключены: ставить нечего, кнопка неактивна
			Path game4 = Files.createTempDirectory("gitgame4");
			GitSession s4 = new GitSession(base, "o/r", GitRelease.fetch(base, "o/r", game4), game4);
			s4.skip(java.util.EnumSet.allOf(com.smeshariki.mod.client.GitSection.class));
			check("все разделы выключены: 0 файлов, «Скачать» неактивна", s4.selectedCount() == 0 && !s4.canStart());
			check("сохранённые имена разделов читаются обратно", com.smeshariki.mod.client.GitSection.fromIds(com.smeshariki.mod.client.GitSection.ids(java.util.EnumSet.of(com.smeshariki.mod.client.GitSection.WORLD, com.smeshariki.mod.client.GitSection.SHADER_PACKS))).equals(java.util.EnumSet.of(com.smeshariki.mod.client.GitSection.WORLD, com.smeshariki.mod.client.GitSection.SHADER_PACKS)));
			// небезопасное имя в списке устаревших — манифест отвергнут
			String evilManifest = "{\"files\":[],\"obsolete\":[\"../mods-evil.jar\"]}";
			server.removeContext("/dl/manifest.json");
			server.createContext("/dl/manifest.json", ex -> send(ex, evilManifest.getBytes(StandardCharsets.UTF_8)));
			boolean evilObsolete = false;
			try {
				GitRelease.fetch(base, "o/r", Files.createTempDirectory("gitgame3"));
			} catch (java.io.IOException e) {
				evilObsolete = true;
			}
			check("небезопасное имя в списке устаревших отвергнуто", evilObsolete);
			server.removeContext("/dl/manifest.json");
			server.createContext("/dl/manifest.json", ex -> send(ex, manifest.getBytes(StandardCharsets.UTF_8)));
			// чужой узел в переадресации
			server.removeContext("/redir/");
			server.createContext("/redir/", ex -> {
				ex.getResponseHeaders().add("Location", "http://evil.example.com/x");
				ex.sendResponseHeaders(302, -1);
				ex.close();
			});
			Path game2 = Files.createTempDirectory("gitgame2");
			GitRelease.Plan p2 = GitRelease.fetch(base, "o/r", game2);
			GitSession s2 = new GitSession(base, "o/r", p2, game2);
			run(s2);
			check("переадресация на чужой узел отвергнута, ничего не поставлено", s2.installed() == 0 && s2.errors().size() == 6 && !Files.exists(game2.resolve("mods/levelmod-1.0.0.jar")));
			boolean badRepo = false;
			try {
				GitRelease.fetch(base, "../etc/passwd", game2);
			} catch (java.io.IOException e) {
				badRepo = true;
			}
			check("неверное имя репозитория отвергнуто", badRepo);
			boolean remoteHttp = false;
			try {
				GitRelease.fetch("http://example.com", "o/r", game2);
			} catch (java.io.IOException e) {
				remoteHttp = true;
			}
			check("http не на localhost отвергнут", remoteHttp);
		} finally {
			server.stop(0);
		}
		worldUpdate();
		System.out.println("ИТОГ: проверок " + checks + ", провалов " + failures);
		System.exit(failures == 0 ? 0 : 1);
	}

	/** Релиз мира для проверки обновления: файлы мира (путь → содержимое), с {@code contents} или без. */
	static String[] worldRelease(String tag, Map<String, String> worldFiles, boolean withContents, String extraRel) throws Exception {
		Map<String, String> inZip = new java.util.TreeMap<>();
		worldFiles.forEach((k, v) -> inZip.put("W/" + k, v));
		byte[] z = zip(inZip);
		StringBuilder c = new StringBuilder();
		for (var e : new java.util.TreeMap<>(worldFiles).entrySet()) {
			if (c.length() > 0) c.append(',');
			byte[] d = e.getValue().getBytes(StandardCharsets.UTF_8);
			c.append('"').append(e.getKey()).append("\":[").append(d.length).append(",\"").append(sha(d)).append("\"]");
		}
		if (extraRel != null) c.append(",\"").append(extraRel).append("\":[1,\"").append("0".repeat(64)).append("\"]");
		String m = "{\"files\":[{\"name\":\"world.zip\",\"category\":\"world\",\"world\":\"W\",\"size\":" + z.length + ",\"sha256\":\"" + sha(z) + "\""
				+ (withContents ? ",\"contents\":{" + c + "}" : "") + "}]}";
		return new String[] {tag, m, java.util.Base64.getEncoder().encodeToString(z)};
	}

	/** Обновление установленного мира по файлам (WorldUpdate): правки игрока, правки релиза, резервные копии, рабочий мир автора. */
	static void worldUpdate() throws Exception {
		System.out.println("-- обновление установленного мира");
		java.util.concurrent.atomic.AtomicReference<String[]> rel = new java.util.concurrent.atomic.AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		String base = "http://127.0.0.1:" + server.getAddress().getPort();
		server.createContext("/repos/o/r/releases/latest", ex -> send(ex, ("{\"tag_name\":\"" + rel.get()[0] + "\",\"assets\":[{\"name\":\"manifest.json\",\"browser_download_url\":\"" + base
				+ "/m\"},{\"name\":\"world.zip\",\"browser_download_url\":\"" + base + "/z\"}]}").getBytes(StandardCharsets.UTF_8)));
		server.createContext("/m", ex -> send(ex, rel.get()[1].getBytes(StandardCharsets.UTF_8)));
		server.createContext("/z", ex -> send(ex, java.util.Base64.getDecoder().decode(rel.get()[2])));
		server.start();
		try {
			Path game = Files.createTempDirectory("gitworld");
			Path w = game.resolve("saves/W");
			Map<String, String> v1 = new java.util.TreeMap<>(Map.of("level.dat", "L1", "region/a.mca", "A1", "region/b.mca", "B1", "data/x.json", "X1"));
			rel.set(worldRelease("v1", v1, true, null));
			var plan = GitRelease.fetch(base, "o/r", game);
			check("мира нет: ставится целиком", plan.entries().size() == 1 && plan.entries().get(0).update() == null);
			run(new GitSession(base, "o/r", plan, game));
			check("мир поставлен, установленная версия запомнена", Files.readString(w.resolve("region/a.mca")).equals("A1") && Files.exists(game.resolve("data/smesharikimod/worlds/W.json")));
			check("сразу после установки обновлять нечего", GitRelease.fetch(base, "o/r", game).entries().isEmpty());

			Files.writeString(w.resolve("region/b.mca"), "B-PLAYER");
			Files.createDirectories(w.resolve("players"));
			Files.writeString(w.resolve("players/p.dat"), "ME");
			check("правка игрока в файле, который релиз не менял, не считается обновлением", GitRelease.fetch(base, "o/r", game).entries().isEmpty());

			Map<String, String> v2 = new java.util.TreeMap<>(v1);
			v2.put("region/a.mca", "A2");
			v2.put("data/y.json", "Y2");
			rel.set(worldRelease("v2", v2, true, null));
			plan = GitRelease.fetch(base, "o/r", game);
			var up = plan.entries().isEmpty() ? null : plan.entries().get(0).update();
			check("релиз изменил a и добавил y: обновятся только они", up != null && up.files().equals(java.util.List.of("data/y.json", "region/a.mca")) || up != null && new java.util.HashSet<>(up.files()).equals(java.util.Set.of("data/y.json", "region/a.mca")));
			check("мир не рабочий (нет истории версий): раздел «Мир» включён", up != null && !up.authored() && new GitSession(base, "o/r", plan, game).sectionSelected(com.smeshariki.mod.client.GitSection.WORLD));
			GitSession s = new GitSession(base, "o/r", plan, game);
			run(s);
			check("обновление без ошибок", s.errors().isEmpty() && s.installed() == 1);
			check("a и y — из релиза, b игрока и его players/ на месте", Files.readString(w.resolve("region/a.mca")).equals("A2") && Files.readString(w.resolve("data/y.json")).equals("Y2")
					&& Files.readString(w.resolve("region/b.mca")).equals("B-PLAYER") && Files.readString(w.resolve("players/p.dat")).equals("ME") && Files.readString(w.resolve("data/x.json")).equals("X1"));
			Path backups = game.resolve("backups/smesharikimod");
			Path b1;
			try (var l = Files.list(backups)) {
				b1 = l.findFirst().orElse(null);
			}
			check("прежний a лежит в резервной копии", b1 != null && Files.readString(b1.resolve("region/a.mca")).equals("A1"));
			check("после обновления нечего обновлять, временных папок нет", GitRelease.fetch(base, "o/r", game).entries().isEmpty() && Files.list(game.resolve("saves")).noneMatch(p -> p.getFileName().toString().startsWith(".smesharikimod")));

			Map<String, String> v3 = new java.util.TreeMap<>(v2);
			v3.put("region/b.mca", "B3");
			rel.set(worldRelease("v3", v3, true, null));
			plan = GitRelease.fetch(base, "o/r", game);
			check("релиз изменил b, который менял и игрок: обновится (релиз главнее)", plan.entries().size() == 1 && plan.entries().get(0).update().files().equals(java.util.List.of("region/b.mca")));
			Thread.sleep(1100); // другая метка времени у папки резервной копии
			run(new GitSession(base, "o/r", plan, game));
			boolean playerBackup;
			try (var l = Files.walk(backups)) {
				playerBackup = l.filter(p -> p.endsWith("region/b.mca")).anyMatch(p -> {
					try {
						return Files.readString(p).equals("B-PLAYER");
					} catch (java.io.IOException e) {
						return false;
					}
				});
			}
			check("b игрока сохранён в резервной копии", Files.readString(w.resolve("region/b.mca")).equals("B3") && playerBackup);

			Files.delete(game.resolve("data/smesharikimod/worlds/W.json"));
			Files.writeString(w.resolve("level.dat"), "L-PLAYER");
			plan = GitRelease.fetch(base, "o/r", game);
			check("прошлую установку не помним: отличающийся файл предлагается, совпадающие — нет", plan.entries().size() == 1 && plan.entries().get(0).update().files().equals(java.util.List.of("level.dat")) && !plan.entries().get(0).update().known());

			Files.createDirectories(w.resolve("data/smesharikimod/versions"));
			plan = GitRelease.fetch(base, "o/r", game);
			check("рабочий мир (своя история версий): обновление предлагается, но раздел «Мир» выключен", plan.entries().size() == 1 && plan.entries().get(0).update().authored()
					&& !new GitSession(base, "o/r", plan, game).sectionSelected(com.smeshariki.mod.client.GitSection.WORLD));

			rel.set(worldRelease("v0", v3, false, null));
			check("старый релиз без списка файлов: установленный мир не трогается", GitRelease.fetch(base, "o/r", game).entries().isEmpty());

			rel.set(worldRelease("evil", v3, true, "../../evil.txt"));
			boolean evil = false;
			try {
				GitRelease.fetch(base, "o/r", game);
			} catch (java.io.IOException e) {
				evil = true;
			}
			check("путь с .. в списке файлов мира отвергнут", evil);
		} finally {
			server.stop(0);
		}
	}

	static void send(com.sun.net.httpserver.HttpExchange ex, byte[] body) throws java.io.IOException {
		ex.sendResponseHeaders(200, body.length);
		ex.getResponseBody().write(body);
		ex.close();
	}
}
