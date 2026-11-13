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

import com.managementmod.client.GitSession;
import com.managementmod.sync.GitRelease;
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
		for (int i = 0; i < 300 && s.state() != com.managementmod.client.SyncSession.State.DONE; i++) Thread.sleep(50);
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
		String manifest = "{\"files\":[{\"name\":\"levelmod-1.0.0.jar\",\"category\":\"mod\",\"size\":" + jar.length + ",\"sha256\":\"" + sha(jar) + "\"},"
				+ "{\"name\":\"world.zip\",\"category\":\"world\",\"world\":\"Test World\",\"size\":" + world.length + ",\"sha256\":\"" + sha(world) + "\"},"
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
			GitRelease.Plan plan = GitRelease.fetch(base, "o/r", game);
			check("устаревший мод на диске найден, отсутствующий (installermod) игнорируется", plan.obsolete().equals(java.util.List.of("smesharikiworld-1.0.0.jar")));
			System.out.println("план: " + plan.entries().stream().map(e -> e.file().name()).toList());
			check("тег релиза прочитан", plan.tag().equals("v1"));
			check("в плане все 4 файла (jar отличается, мира нет)", plan.entries().size() == 4);
			GitSession session = new GitSession(base, "o/r", plan, game);
			check("замена отмечена как обновление", session.items().stream().anyMatch(i -> i.entry().name().equals("levelmod-1.0.0.jar") && i.isUpdate()));
			run(session);
			System.out.println("ошибки: " + session.errors());
			check("jar поставлен, содержимое новое", Files.readString(game.resolve("mods").resolve("levelmod-1.0.0.jar")).equals("JAR-CONTENT-v2"));
			check("старый jar сохранён как .managementmod-old", Files.readString(game.resolve("mods").resolve("levelmod-1.0.0.jar.managementmod-old")).equals("OLD-JAR"));
			check("устаревший мод отключён: переименован в .managementmod-old, не удалён", !Files.exists(game.resolve("mods/smesharikiworld-1.0.0.jar")) && Files.readString(game.resolve("mods/smesharikiworld-1.0.0.jar.managementmod-old")).equals("OLD-WORLD-MOD"));
			check("мир распакован в saves/Test World", Files.exists(game.resolve("saves/Test World/level.dat")) && Files.exists(game.resolve("saves/Test World/region/r.0.0.mca")));
			check("файл с неверной суммой не поставлен", !Files.exists(game.resolve("mods/badsum.jar")) && session.errors().stream().anyMatch(e -> e.startsWith("badsum.jar")));
			check("zip-slip отвергнут: ничего не вышло за saves", !Files.exists(game.resolve("evil.txt")) && !Files.exists(game.resolve("saves/evil.txt")) && !Files.exists(game.resolve("saves/Evil")) && session.errors().stream().anyMatch(e -> e.startsWith("evil.zip")));
			check("временных папок и .part не осталось", Files.list(game.resolve("saves")).noneMatch(p -> p.getFileName().toString().startsWith(".managementmod")) && Files.list(game.resolve("mods")).noneMatch(p -> p.toString().endsWith(".managementmod-part")));
			check("установлено 2 из 4, итог DONE", session.installed() == 2 && session.state() == com.managementmod.client.SyncSession.State.DONE);
			GitRelease.Plan again = GitRelease.fetch(base, "o/r", game);
			System.out.println("повторный план: " + again.entries().stream().map(e -> e.file().name()).toList());
			check("повторная проверка: поставленное не предлагается (остались неудавшиеся)", again.entries().stream().noneMatch(e -> e.file().name().equals("levelmod-1.0.0.jar") || e.file().name().equals("world.zip")));
			GitRelease.Plan afterObsolete = GitRelease.fetch(base, "o/r", game);
			check("после отключения устаревших повторно они не предлагаются", afterObsolete.obsolete().isEmpty());
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
			check("переадресация на чужой узел отвергнута, ничего не поставлено", s2.installed() == 0 && s2.errors().size() == 4 && !Files.exists(game2.resolve("mods/levelmod-1.0.0.jar")));
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
		System.out.println("ИТОГ: проверок " + checks + ", провалов " + failures);
		System.exit(failures == 0 ? 0 : 1);
	}

	static void send(com.sun.net.httpserver.HttpExchange ex, byte[] body) throws java.io.IOException {
		ex.sendResponseHeaders(200, body.length);
		ex.getResponseBody().write(body);
		ex.close();
	}
}
