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
		/** Position within the tier; lower sorts first. See Record.order(). */
		int order;
		/** Retired players keep their tier but leave the rendered list. */
		boolean retired;

		Entry() {
		}
	}

	/**
	 * One entry in memory, with the grade already resolved.
	 *
	 * <p>{@code order} places the player within their tier on the rendered
	 * tierlist: lower sorts first, ties fall back to the name. It is presentation
	 * only -- it is never served over the API, because the mod draws a single
	 * badge and has no notion of anyone's neighbours.
	 */
	public record Record(UUID uuid, String name, Grade grade, String gradedBy,
			String gradedByDiscordId, long gradedAt, int order, boolean retired) {
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
								e.gradedBy, e.gradedByDiscordId, e.gradedAt, e.order,
								e.retired));
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
			// A player already placed keeps their position; a new one goes to
			// the back of the tier rather than jumping into the middle of it.
			int order = 0;
			Record existing = grades.get(player);
			if (existing != null && existing.grade() == grade) {
				order = existing.order();
			} else {
				for (Record other : grades.values()) {
					if (other.grade() == grade) {
						order = Math.max(order, other.order() + 1);
					}
				}
			}
			// Setting a tier brings a retired player back: an explicit new tier
			// is a clearer statement of intent than the old retirement flag.
			previous = grades.put(player, new Record(player, name == null ? "" : name, grade,
					gradedBy, discordId, System.currentTimeMillis() / 1000L, order, false));
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
					existing.gradedByDiscordId(), existing.gradedAt(), existing.order(),
					existing.retired()));
		} finally {
			lock.writeLock().unlock();
		}
		save();
	}

	/**
	 * Move a player within their own tier.
	 *
	 * <p>Positive moves them earlier in the row, negative later, by that many
	 * places -- so {@code 1} is up one and {@code -1} is down one. Clamped at
	 * the ends of the tier rather than refused, so bumping someone already at
	 * the top is a no-op instead of an error.
	 *
	 * <p>Ordering is display only. It never reaches the mod: the API serves one
	 * badge per player, which has no notion of who stands beside them.
	 *
	 * @return how many places they actually moved, 0 if already at the end, or
	 *     -1 if they are not on the tierlist at all
	 */
	public int bump(UUID player, int places) {
		lock.writeLock().lock();
		try {
			Record target = grades.get(player);
			if (target == null) {
				return -1;
			}

			// The tier as it is drawn, so a move is against what was on screen.
			List<Record> tier = new ArrayList<>();
			for (Record record : grades.values()) {
				if (record.grade() == target.grade()) {
					tier.add(record);
				}
			}
			tier.sort(Comparator.comparingInt(Record::order)
					.thenComparing(r -> r.name().toLowerCase(Locale.ROOT)));

			int from = -1;
			for (int i = 0; i < tier.size(); i++) {
				if (tier.get(i).uuid().equals(player)) {
					from = i;
					break;
				}
			}
			if (from < 0) {
				return -1;
			}
			// Positive is "up", which is towards the front of the row.
			int to = Math.clamp(from - places, 0, tier.size() - 1);
			if (to == from) {
				return 0;
			}

			tier.add(to, tier.remove(from));
			// Renumber the whole tier from zero: leaving gaps would let orders
			// drift apart until a later insert lands somewhere unexpected.
			for (int i = 0; i < tier.size(); i++) {
				Record record = tier.get(i);
				grades.put(record.uuid(), new Record(record.uuid(), record.name(),
						record.grade(), record.gradedBy(), record.gradedByDiscordId(),
						record.gradedAt(), i, record.retired()));
			}
			save();
			return from - to;
		} finally {
			lock.writeLock().unlock();
		}
	}

	/**
	 * Mark a player retired, or bring them back.
	 *
	 * <p>A retired player keeps their tier and can still be looked up, but drops
	 * out of the rendered list -- the picture is about who is currently ranked.
	 * The mod shows their tier prefixed with R.
	 *
	 * @return true if the flag changed, false if they were already that way or
	 *     are not on the tierlist
	 */
	public boolean retire(UUID player, boolean retired) {
		lock.writeLock().lock();
		try {
			Record existing = grades.get(player);
			if (existing == null || existing.retired() == retired) {
				return false;
			}
			grades.put(player, new Record(existing.uuid(), existing.name(), existing.grade(),
					existing.gradedBy(), existing.gradedByDiscordId(), existing.gradedAt(),
					existing.order(), retired));
		} finally {
			lock.writeLock().unlock();
		}
		save();
		return true;
	}

	/**
	 * Remove every grade, or every grade in one tier.
	 *
	 * <p>One method for both so the whole clear happens under a single write
	 * lock and a single save: removing players one at a time would rewrite the
	 * file once per player, and would let a reader see a half-cleared list.
	 *
	 * @param grade the tier to clear, or null for the whole list
	 * @return how many players were removed
	 */
	public int clear(Grade grade) {
		int removed;
		lock.writeLock().lock();
		try {
			if (grade == null) {
				removed = grades.size();
				grades.clear();
			} else {
				removed = 0;
				var players = grades.entrySet().iterator();
				while (players.hasNext()) {
					if (players.next().getValue().grade() == grade) {
						players.remove();
						removed++;
					}
				}
			}
		} finally {
			lock.writeLock().unlock();
		}
		// Nothing to write when nothing matched, so an empty clear does not
		// touch the file.
		if (removed > 0) {
			save();
		}
		return removed;
	}

	/** Every grade, best first then alphabetical, for a listing. */
	public List<Record> all() {
		lock.readLock().lock();
		try {
			List<Record> out = new ArrayList<>(grades.values());
			out.sort(Comparator.<Record, Integer>comparing(r -> r.grade().ordinal())
					.thenComparing(Record::order)
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
				e.order = record.order();
				e.retired = record.retired();
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
