package com.spog.tiers.backend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Who may set grades, read once at startup from {@code graders.json}.
 *
 * <p>Identity is the <b>Discord user ID</b> -- the permanent snowflake, not the
 * username or the display name, both of which a user can change at will. The
 * {@code name} field in the file is a comment for whoever edits it and is never
 * used for authorization.
 *
 * <p>Deliberately <b>not</b> reloadable at runtime. Granting the ability to
 * grade is the one privilege escalation in this system, so it takes a file edit
 * plus a restart -- an action that already requires access to the server.
 *
 * <p>File format:
 * <pre>{@code
 * [
 *   { "id": "123456789012345678", "name": "Spoginator" }
 * ]
 * }</pre>
 */
public final class GraderStore {
	private static final Logger LOG = LoggerFactory.getLogger(GraderStore.class);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** One entry as it appears on disk. */
	static final class Entry {
		String id;
		String name;

		Entry() {
		}

		Entry(String id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	private final Set<String> graders;

	private GraderStore(Set<String> graders) {
		this.graders = graders;
	}

	/**
	 * Load the grader list.
	 *
	 * <p>A missing file is written out as an empty list so the operator has
	 * something to edit, rather than having to hand-write JSON to configure a
	 * fresh deployment. A malformed file is refused loudly and leaves nobody
	 * authorised: quietly treating it as empty would look identical, but from
	 * the log it would be indistinguishable from a genuine empty list.
	 */
	public static GraderStore load(Path file) {
		Set<String> out = new LinkedHashSet<>();
		if (!Files.exists(file)) {
			try {
				Files.writeString(file, GSON.toJson(new Entry[0]) + "\n", StandardCharsets.UTF_8);
				LOG.info("created {}; add Discord user IDs to it and restart to grant grading",
						file.getFileName());
			} catch (IOException e) {
				LOG.warn("could not create {} ({}); nobody can grade until it exists",
						file, e.toString());
			}
			return new GraderStore(out);
		}
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			Entry[] entries = GSON.fromJson(json, Entry[].class);
			if (entries != null) {
				for (Entry e : entries) {
					if (e == null || e.id == null || e.id.isBlank()) {
						continue;
					}
					out.add(e.id.trim());
				}
			}
			LOG.info("loaded {} grader(s) from {}", out.size(), file.getFileName());
		} catch (IOException | JsonSyntaxException e) {
			LOG.error("could not read {} ({}); nobody will be able to grade", file, e.toString());
		}
		return new GraderStore(out);
	}

	/** Whether this Discord user ID may set grades. */
	public boolean isGrader(String discordId) {
		return discordId != null && graders.contains(discordId);
	}

	public int size() {
		return graders.size();
	}
}
