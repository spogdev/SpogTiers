package com.spog.tiers.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradeStoreTest {
	private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
	private static final UUID JEB = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");

	@Test
	void missingFileIsAnEmptyStore(@TempDir Path dir) {
		GradeStore store = GradeStore.load(dir.resolve("grades.json"));
		assertEquals(0, store.size());
		assertNull(store.get(NOTCH));
	}

	@Test
	void gradesSurviveAReload(@TempDir Path dir) {
		Path file = dir.resolve("grades.json");
		GradeStore store = GradeStore.load(file);
		store.set(NOTCH, "Notch", Grade.S, "Spoginator", "123");

		// The point of the store: a restart must not lose grades.
		GradeStore reloaded = GradeStore.load(file);
		GradeStore.Record record = reloaded.get(NOTCH);
		assertNotNull(record);
		assertSame(Grade.S, record.grade());
		assertEquals("Notch", record.name());
		assertEquals("Spoginator", record.gradedBy());
		assertEquals("123", record.gradedByDiscordId());
	}

	@Test
	void settingAgainReportsThePreviousGrade(@TempDir Path dir) {
		GradeStore store = GradeStore.load(dir.resolve("grades.json"));
		assertNull(store.set(NOTCH, "Notch", Grade.B, "a", "1"));

		GradeStore.Record previous = store.set(NOTCH, "Notch", Grade.S, "a", "1");
		assertNotNull(previous);
		assertSame(Grade.B, previous.grade());
		assertSame(Grade.S, store.get(NOTCH).grade());
	}

	@Test
	void removeReportsWhatWasThere(@TempDir Path dir) {
		GradeStore store = GradeStore.load(dir.resolve("grades.json"));
		assertNull(store.remove(NOTCH), "removing an ungraded player reports nothing");

		store.set(NOTCH, "Notch", Grade.A, "a", "1");
		assertSame(Grade.A, store.remove(NOTCH).grade());
		assertNull(store.get(NOTCH));
	}

	@Test
	void renamingKeepsTheGrade(@TempDir Path dir) {
		// Grades key on UUID precisely so a rename cannot lose one.
		GradeStore store = GradeStore.load(dir.resolve("grades.json"));
		store.set(NOTCH, "Notch", Grade.S, "a", "1");
		store.refreshName(NOTCH, "NotchRenamed");

		GradeStore.Record record = store.get(NOTCH);
		assertEquals("NotchRenamed", record.name());
		assertSame(Grade.S, record.grade());
	}

	@Test
	void listingIsBestFirst(@TempDir Path dir) {
		GradeStore store = GradeStore.load(dir.resolve("grades.json"));
		store.set(JEB, "jeb_", Grade.F, "a", "1");
		store.set(NOTCH, "Notch", Grade.S, "a", "1");

		var all = store.all();
		assertEquals("Notch", all.get(0).name());
		assertEquals("jeb_", all.get(1).name());
	}

	@Test
	void malformedJsonStartsEmptyRatherThanThrowing(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("grades.json");
		Files.writeString(file, "{ this is not valid json", StandardCharsets.UTF_8);

		// Loud in the log, but the service still starts.
		GradeStore store = GradeStore.load(file);
		assertEquals(0, store.size());
	}

	@Test
	void unknownGradesAreSkippedNotFatal(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("grades.json");
		Files.writeString(file, """
				[
				  { "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "name": "Notch", "grade": "S" },
				  { "uuid": "853c80ef-3c37-49fd-aa49-938b674adae6", "name": "jeb_", "grade": "Z" }
				]
				""", StandardCharsets.UTF_8);

		GradeStore store = GradeStore.load(file);
		assertEquals(1, store.size(), "the valid row still loads");
		assertSame(Grade.S, store.get(NOTCH).grade());
		assertNull(store.get(JEB));
	}

	@Test
	void malformedUuidsAreSkipped(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("grades.json");
		Files.writeString(file, """
				[ { "uuid": "not-a-uuid", "name": "x", "grade": "S" } ]
				""", StandardCharsets.UTF_8);

		assertEquals(0, GradeStore.load(file).size());
	}

	@Test
	void writesLeaveNoTempFilesBehind(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("grades.json");
		GradeStore store = GradeStore.load(file);
		store.set(NOTCH, "Notch", Grade.S, "a", "1");

		try (var files = Files.list(dir)) {
			assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
					"the staged temp file should have been moved into place");
		}
	}
}
