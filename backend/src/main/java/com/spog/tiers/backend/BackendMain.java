package com.spog.tiers.backend;

import io.javalin.Javalin;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

	/** Where the token is kept when it is not in the environment. */
	private static final String TOKEN_FILE = "token.txt";

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

		// Say where the data is being read from. Pointing --data-dir somewhere
		// unintended looks exactly like "no permission" and "nobody on the
		// tierlist", because both files are simply absent there.
		LOG.info("data directory: {}", dataDir.toAbsolutePath().normalize());

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
		String token = resolveToken(dataDir);
		if (token == null || token.isBlank()) {
			LOG.warn("no Discord token found; running API-only, no tier commands. "
					+ "Set DISCORD_TOKEN or put the token in {}",
					dataDir.resolve(TOKEN_FILE).toAbsolutePath());
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

	/**
	 * The bot token, from the environment or from a file beside the data.
	 *
	 * <p>The environment wins, so the systemd unit's {@code EnvironmentFile}
	 * stays the way a server runs this. The file is for running it by hand,
	 * where exporting a variable every time is a nuisance and it is easy to end
	 * up pasting the token onto a command line instead -- which leaks it to
	 * every user on the machine through {@code ps}.
	 *
	 * <p>A token is a password, not configuration: keep {@code token.txt} out of
	 * version control, and prefer the environment anywhere it is shared.
	 */
	private static String resolveToken(Path dataDir) {
		String fromEnv = System.getenv("DISCORD_TOKEN");
		if (fromEnv != null && !fromEnv.isBlank()) {
			return fromEnv.trim();
		}
		Path file = dataDir.resolve(TOKEN_FILE);
		if (!Files.exists(file)) {
			return null;
		}
		try {
			// Only the first non-blank line, so a trailing newline or a comment
			// underneath does not become part of the token.
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					LOG.info("read the Discord token from {}", file.getFileName());
					return trimmed;
				}
			}
			LOG.warn("{} is empty", file);
		} catch (IOException e) {
			LOG.error("could not read {} ({})", file, e.toString());
		}
		return null;
	}
}
