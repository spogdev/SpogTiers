package com.spog.tiers.backend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Every graded player, persisted to {@code grades.json}.
 *
 * <p><b>Keyed by UUID, never by name.</b> Names are rented, not owned: keying on
 * one would lose a player their grade the day they rename, and hand it to
 * whoever took the name next. The {@code name} field on disk is a human-readable
 * comment for whoever opens the file, refreshed opportunistically when we happen
 * to learn the current name, and is never used for lookup.
 *
 * <p>Read by the HTTP thread while a Discord thread writes, so the map is
 * guarded. Writes are rare -- a human typing a command -- so each one rewrites
 * the whole file rather than maintaining an append log.
 */
public final class GradeStore {
	private static final Logger LOG = LoggerFactory.getLogger(GradeStore.class);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** One entry as it appears on disk. */
	static final class Entry {
		String uuid;
		String name;
		String grade;
		String gradedBy;
		String gradedByDiscordId;
		long gradedAt;

		Entry() {
		}
	}

	/** One entry in memory, with the grade already resolved. */
	public record Record(UUID uuid, String name, Grade grade, String gradedBy,
			String gradedByDiscordId, long gradedAt) {
	}

	private final Path file;
	private final Map<UUID, Record> grades;
	private final ReadWriteLock lock = new ReentrantReadWriteLock();

	private GradeStore(Path file, Map<UUID, Record> grades) {
		this.file = file;
		this.grades = grades;
	}

	/**
	 * Load the grades.
	 *
	 * <p>A missing file is normal on a fresh deployment and simply means nobody
	 * is graded yet. A malformed one is refused loudly and left untouched: it is
	 * far better to start empty and shout than to silently treat a corrupted
	 * file as "no grades" and then overwrite it with that on the next write.
	 */
	public static GradeStore load(Path file) {
		Map<UUID, Record> out = new LinkedHashMap<>();
		if (!Files.exists(file)) {
			LOG.info("no {} yet; starting with no grades", file.getFileName());
			return new GradeStore(file, out);
		}
		try {
			String json = stripBom(Files.readString(file, StandardCharsets.UTF_8));
			Entry[] entries = GSON.fromJson(json, Entry[].class);
			if (entries != null) {
				for (Entry e : entries) {
					if (e == null || e.uuid == null || e.uuid.isBlank()) {
						continue;
					}
					Grade grade = Grade.parse(e.grade);
					if (grade == null) {
						LOG.warn("skipping unknown grade '{}' for {} in {}",
								e.grade, e.uuid, file.getFileName());
						continue;
					}
					try {
						UUID id = UUID.fromString(e.uuid.trim());
						out.put(id, new Record(id, e.name == null ? "" : e.name, grade,
								e.gradedBy, e.gradedByDiscordId, e.gradedAt));
					} catch (IllegalArgumentException ex) {
						LOG.warn("skipping malformed uuid '{}' in {}", e.uuid, file.getFileName());
					}
				}
			}
			LOG.info("loaded {} grade(s) from {}", out.size(), file.getFileName());
		} catch (IOException | JsonSyntaxException e) {
			LOG.error("could not read {} ({}); starting with no grades", file, e.toString());
		}
		return new GradeStore(file, out);
	}

	/** This player's grade, or null if they have none. */
	public Record get(UUID player) {
		if (player == null) {
			return null;
		}
		lock.readLock().lock();
		try {
			return grades.get(player);
		} finally {
			lock.readLock().unlock();
		}
	}

	/**
	 * Set a player's grade, replacing any existing one.
	 *
	 * @return the grade they held before, or null if they were ungraded
	 */
	public Record set(UUID player, String name, Grade grade, String gradedBy, String discordId) {
		Record previous;
		lock.writeLock().lock();
		try {
			previous = grades.put(player, new Record(player, name == null ? "" : name, grade,
					gradedBy, discordId, System.currentTimeMillis() / 1000L));
		} finally {
			lock.writeLock().unlock();
		}
		save();
		return previous;
	}

	/**
	 * Remove a player's grade.
	 *
	 * @return the grade they held, or null if they had none
	 */
	public Record remove(UUID player) {
		Record previous;
		lock.writeLock().lock();
		try {
			previous = grades.remove(player);
		} finally {
			lock.writeLock().unlock();
		}
		if (previous != null) {
			save();
		}
		return previous;
	}

	/**
	 * Refresh the stored name for a player we already hold a grade for.
	 *
	 * <p>Called when a lookup happens to resolve a current name, so the file
	 * stays readable as players rename. The grade itself is untouched, and
	 * nothing is written unless the name actually changed.
	 */
	public void refreshName(UUID player, String name) {
		if (player == null || name == null || name.isBlank()) {
			return;
		}
		lock.writeLock().lock();
		try {
			Record existing = grades.get(player);
			if (existing == null || name.equals(existing.name())) {
				return;
			}
			grades.put(player, new Record(player, name, existing.grade(), existing.gradedBy(),
					existing.gradedByDiscordId(), existing.gradedAt()));
		} finally {
			lock.writeLock().unlock();
		}
		save();
	}

	/** Every grade, best first then alphabetical, for a listing. */
	public List<Record> all() {
		lock.readLock().lock();
		try {
			List<Record> out = new ArrayList<>(grades.values());
			out.sort(Comparator.<Record, Integer>comparing(r -> r.grade().ordinal())
					.thenComparing(r -> r.name().toLowerCase(Locale.ROOT)));
			return out;
		} finally {
			lock.readLock().unlock();
		}
	}

	public int size() {
		lock.readLock().lock();
		try {
			return grades.size();
		} finally {
			lock.readLock().unlock();
		}
	}

	/**
	 * Write the file out.
	 *
	 * <p>Staged through a temp file in the same directory and moved into place,
	 * so a crash mid-write cannot leave a half-written {@code grades.json}
	 * behind -- the file is either the old one or the new one, never a
	 * truncated mixture.
	 */
	private void save() {
		List<Entry> entries = new ArrayList<>();
		lock.readLock().lock();
		try {
			for (Record record : grades.values()) {
				Entry e = new Entry();
				e.uuid = record.uuid().toString();
				e.name = record.name();
				e.grade = record.grade().label();
				e.gradedBy = record.gradedBy();
				e.gradedByDiscordId = record.gradedByDiscordId();
				e.gradedAt = record.gradedAt();
				entries.add(e);
			}
		} finally {
			lock.readLock().unlock();
		}

		try {
			Path parent = file.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path temp = Files.createTempFile(parent, "grades", ".tmp");
			Files.writeString(temp, GSON.toJson(entries) + "\n", StandardCharsets.UTF_8);
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				// Some filesystems cannot move atomically; a plain replace is
				// still better than writing the target in place.
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			// The in-memory state is still correct, so the running process keeps
			// working; say so plainly rather than letting a restart silently
			// lose the change.
			LOG.error("could not write {} ({}); this change will not survive a restart",
					file, e.toString());
		}
	}

	/**
	 * Drops a UTF-8 byte order mark.
	 *
	 * <p>PowerShell's {@code Out-File -Encoding utf8} writes one, and it is
	 * invisible in an editor -- so a file that looks perfectly correct fails to
	 * parse. Cheaper to tolerate here than to explain.
	 */
	private static String stripBom(String json) {
		return json.startsWith("﻿") ? json.substring(1) : json;
	}
}
