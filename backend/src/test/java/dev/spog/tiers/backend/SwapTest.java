package dev.spog.tiers.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwapTest {
	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
	private static final UUID MISSING =
			UUID.fromString("00000000-0000-0000-0000-00000000000f");

	private static GradeStore store(Path dir) {
		return GradeStore.load(dir.resolve("grades.json"));
	}

	/** Names on a tier, in display order, retired excluded as the list draws it. */
	private static List<String> drawn(GradeStore store, Grade tier) {
		List<String> out = new ArrayList<>();
		for (GradeStore.Record record : store.all()) {
			if (record.grade() == tier && !record.retired()) {
				out.add(record.name());
			}
		}
		return out;
	}

	@Test
	void swapsAcrossTiers(@TempDir Path dir) {
		GradeStore store = store(dir);
		store.set(A, "Ana", Grade.S, "t", "1");
		store.set(B, "Bo", Grade.B, "t", "1");

		assertNull(store.swap(A, B));

		assertEquals(Grade.B, store.get(A).grade());
		assertEquals(Grade.S, store.get(B).grade());
	}

	@Test
	void swapsWithinOneTier(@TempDir Path dir) {
		GradeStore store = store(dir);
		store.set(A, "Ana", Grade.S, "t", "1");
		store.set(B, "Bo", Grade.S, "t", "1");
		store.set(C, "Cy", Grade.S, "t", "1");
		assertEquals(List.of("Ana", "Bo", "Cy"), drawn(store, Grade.S));

		assertNull(store.swap(A, C));

		// The two trade places and Bo, who did not ask, keeps his.
		assertEquals(List.of("Cy", "Bo", "Ana"), drawn(store, Grade.S));
	}

	/** The case a pair of /assign calls cannot express. */
	@Test
	void swapsAcrossRetirement(@TempDir Path dir) {
		GradeStore store = store(dir);
		store.set(A, "Ana", Grade.S, "t", "1");
		store.set(B, "Bo", Grade.A, "t", "1");
		store.retire(B, true);

		assertNull(store.swap(A, B));

		// Ana takes Bo's retired place at A; Bo takes Ana's drawn place at S.
		assertTrue(store.get(A).retired());
		assertEquals(Grade.A, store.get(A).grade());
		assertFalse(store.get(B).retired());
		assertEquals(Grade.S, store.get(B).grade());

		// And that is what the drawn list shows: Ana gone, Bo arrived.
		assertEquals(List.of("Bo"), drawn(store, Grade.S));
		assertEquals(List.of(), drawn(store, Grade.A));
	}

	@Test
	void swapsTwoRetiredPlayers(@TempDir Path dir) {
		GradeStore store = store(dir);
		store.set(A, "Ana", Grade.S, "t", "1");
		store.set(B, "Bo", Grade.C, "t", "1");
		store.retire(A, true);
		store.retire(B, true);

		assertNull(store.swap(A, B));

		assertTrue(store.get(A).retired());
		assertTrue(store.get(B).retired());
		assertEquals(Grade.C, store.get(A).grade());
		assertEquals(Grade.S, store.get(B).grade());
	}

	/** Identity and history belong to the player, not to the seat. */
	@Test
	void keepsNameAndGradingHistory(@TempDir Path dir) {
		GradeStore store = store(dir);
		store.set(A, "Ana", Grade.S, "annie", "111");
		store.set(B, "Bo", Grade.B, "bob", "222");
		long anaAt = store.get(A).gradedAt();
		long boAt = store.get(B).gradedAt();

		assertNull(store.swap(A, B));

		assertEquals("Ana", store.get(A).name());
		assertEquals("annie", store.get(A).gradedBy());
		assertEquals("111", store.get(A).gradedByDiscordId());
		assertEquals(anaAt, store.get(A).gradedAt());
		assertEquals("Bo", store.get(B).name());
		assertEquals("bob", store.get(B).gradedBy());
		assertEquals("222", store.get(B).gradedByDiscordId());
		assertEquals(boAt, store.get(B).gradedAt());
	}

	@Test
	void swappingTwiceRestoresBoth(@TempDir Path dir) {
		GradeStore store = store(dir);
		store.set(A, "Ana", Grade.S, "t", "1");
		store.set(B, "Bo", Grade.D, "t", "1");
		store.retire(B, true);

		assertNull(store.swap(A, B));
		assertNull(store.swap(A, B));

		assertEquals(Grade.S, store.get(A).grade());
		assertFalse(store.get(A).retired());
		assertEquals(Grade.D, store.get(B).grade());
		assertTrue(store.get(B).retired());
	}

	@Test
	void refusesAnUngradedPlayer(@TempDir Path dir) {
		GradeStore store = store(dir);
		store.set(A, "Ana", Grade.S, "t", "1");

		GradeStore.SwapRefusal second = store.swap(A, MISSING);
		assertNotNull(second);
		assertEquals(MISSING, second.missing());
		assertFalse(second.same());

		GradeStore.SwapRefusal first = store.swap(MISSING, A);
		assertNotNull(first);
		assertEquals(MISSING, first.missing());

		// The graded player is left exactly as they were.
		assertEquals(Grade.S, store.get(A).grade());
	}

	@Test
	void refusesTheSamePlayerTwice(@TempDir Path dir) {
		GradeStore store = store(dir);
		store.set(A, "Ana", Grade.S, "t", "1");

		GradeStore.SwapRefusal refused = store.swap(A, A);
		assertNotNull(refused);
		assertTrue(refused.same());
		assertNull(refused.missing());
	}

	@Test
	void refusesNulls(@TempDir Path dir) {
		GradeStore store = store(dir);
		store.set(A, "Ana", Grade.S, "t", "1");

		assertNotNull(store.swap(null, A));
		assertNotNull(store.swap(A, null));
		assertNotNull(store.swap(null, null));
		assertEquals(Grade.S, store.get(A).grade());
	}

	@Test
	void survivesAReload(@TempDir Path dir) {
		Path file = dir.resolve("grades.json");
		GradeStore store = GradeStore.load(file);
		store.set(A, "Ana", Grade.S, "t", "1");
		store.set(B, "Bo", Grade.B, "t", "1");
		store.retire(B, true);
		assertNull(store.swap(A, B));

		GradeStore reloaded = GradeStore.load(file);
		assertEquals(Grade.B, reloaded.get(A).grade());
		assertTrue(reloaded.get(A).retired());
		assertEquals(Grade.S, reloaded.get(B).grade());
		assertFalse(reloaded.get(B).retired());
	}

	/** A swapped-in player must still be movable by /bump. */
	@Test
	void bumpWorksAfterASwap(@TempDir Path dir) {
		GradeStore store = store(dir);
		store.set(A, "Ana", Grade.S, "t", "1");
		store.set(B, "Bo", Grade.S, "t", "1");
		store.set(C, "Cy", Grade.A, "t", "1");
		store.retire(C, true);

		// Cy comes back onto S in Ana's place; Ana retires to A.
		assertNull(store.swap(A, C));
		assertEquals(List.of("Cy", "Bo"), drawn(store, Grade.S));

		assertEquals(-1, store.bump(C, -1));
		assertEquals(List.of("Bo", "Cy"), drawn(store, Grade.S));
		// Ana is retired, so she has no drawn place to be bumped within.
		assertEquals(GradeStore.BUMP_ABSENT, store.bump(A, 1));
	}
}
