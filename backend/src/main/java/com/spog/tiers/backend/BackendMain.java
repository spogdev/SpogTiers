package com.spog.tiers.backend;

import io.javalin.Javalin;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entrypoint for the Door SMP backend: the grading bot and the API the mod
 * reads, in one process.
 *
 * <pre>
 *   DISCORD_TOKEN=... java -jar doorsmp-backend-all.jar --port 8081
 * </pre>
 *
 * <p><b>The token comes from the environment, never an argument.</b> Command
 * lines are world-readable in {@code ps}, so a token passed as {@code --token}
 * leaks to every user on the machine.
 *
 * <p>Put a TLS-terminating reverse proxy (Caddy) in front; see the README.
 */
public final class BackendMain {
	private static final Logger LOG = LoggerFactory.getLogger(BackendMain.class);

	private static final int DEFAULT_PORT = 8081;

	public static void main(String[] args) throws Exception {
		int port = DEFAULT_PORT;
		String host = "0.0.0.0";
		// grades.json / graders.json live here; defaults to the working
		// directory so the systemd unit's WorkingDirectory decides.
		Path dataDir = Path.of(".");

		for (int i = 0; i < args.length - 1; i++) {
			switch (args[i]) {
				case "--port" -> port = Integer.parseInt(args[++i]);
				case "--host" -> host = args[++i];
				case "--data-dir" -> dataDir = Path.of(args[++i]);
				default -> { }
			}
		}

		GradeStore grades = GradeStore.load(dataDir.resolve("grades.json"));
		GraderStore graders = GraderStore.load(dataDir.resolve("graders.json"));
		MojangNames names = new MojangNames();

		// The API comes up first and is useful on its own, so a missing or
		// rejected Discord token degrades to a read-only service rather than
		// taking the mod's tier lookups down with it.
		Javalin http = new HttpApi(grades, names).build();
		http.start(host, port);
		LOG.info("Door SMP API listening on {}:{}", host, port);

		JDA jda = null;
		String token = System.getenv("DISCORD_TOKEN");
		if (token == null || token.isBlank()) {
			LOG.warn("DISCORD_TOKEN is not set; running API-only, no grading commands");
		} else {
			try {
				jda = new DiscordBot(grades, graders, names).start(token);
			} catch (Exception e) {
				LOG.error("could not start the Discord bot ({}); continuing API-only",
						e.toString());
			}
		}

		JDA bot = jda;
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOG.info("shutting down");
			if (bot != null) {
				bot.shutdown();
			}
			http.stop();
		}));
	}
}
