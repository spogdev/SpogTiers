package com.spog.tiers.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraderStoreTest {
	@Test
	void seedsAnEmptyFileToEdit(@TempDir Path dir) {
		Path file = dir.resolve("graders.json");
		GraderStore store = GraderStore.load(file);

		assertTrue(Files.exists(file), "an operator needs something to edit");
		assertEquals(0, store.size());
		assertFalse(store.isGrader("123"), "nobody can grade until the file is filled in");
	}

	@Test
	void authorisesOnlyListedIds(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("graders.json");
		Files.writeString(file, """
				[ { "id": "123456789012345678", "name": "Spoginator" } ]
				""", StandardCharsets.UTF_8);

		GraderStore store = GraderStore.load(file);
		assertTrue(store.isGrader("123456789012345678"));
		assertFalse(store.isGrader("999999999999999999"));
		assertFalse(store.isGrader(null));
		// The name in the file is a comment, never an identity.
		assertFalse(store.isGrader("Spoginator"));
	}

	@Test
	void malformedFileAuthorisesNobody(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("graders.json");
		Files.writeString(file, "not json at all", StandardCharsets.UTF_8);

		// Failing closed is the only safe direction for an authorization file.
		GraderStore store = GraderStore.load(file);
		assertEquals(0, store.size());
		assertFalse(store.isGrader("123456789012345678"));
	}

	@Test
	void skipsBlankEntries(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("graders.json");
		Files.writeString(file, """
				[ { "id": "", "name": "blank" },
				  { "name": "no id at all" },
				  { "id": "  123  ", "name": "padded" } ]
				""", StandardCharsets.UTF_8);

		GraderStore store = GraderStore.load(file);
		assertEquals(1, store.size());
		assertTrue(store.isGrader("123"), "ids are trimmed");
	}
}
