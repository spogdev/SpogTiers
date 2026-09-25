package com.spog.tiers.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BumpTest {
	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
	private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

	/** A store holding Ana, Bo, Cy in that order, all on S. */
	private static GradeStore seeded(Path dir) {
		GradeStore store = GradeStore.load(dir.resolve("grades.json"));
		store.set(A, "Ana", Grade.S, "t", "1");
		store.set(B, "Bo", Grade.S, "t", "1");
		store.set(C, "Cy", Grade.S, "t", "1");
		return store;
	}

	/** Names on a tier, in display order. */
	private static List<String> order(GradeStore store, Grade tier) {
		List<String> out = new ArrayList<>();
		for (GradeStore.Record record : store.all()) {
			if (record.grade() == tier) {
				out.add(record.name());
			}
		}
		return out;
	}

	@Test
	void newPlayersLandAtTheBackInInsertionOrder(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		assertEquals(List.of("Ana", "Bo", "Cy"), order(store, Grade.S));
	}

	@Test
	void positiveMovesUp(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		assertEquals(1, store.bump(C, 1), "moved one place");
		assertEquals(List.of("Ana", "Cy", "Bo"), order(store, Grade.S));
	}

	@Test
	void negativeMovesDown(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		assertEquals(-1, store.bump(A, -1));
		assertEquals(List.of("Bo", "Ana", "Cy"), order(store, Grade.S));
	}

	@Test
	void movesMoreThanOnePlace(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		assertEquals(2, store.bump(C, 2));
		assertEquals(List.of("Cy", "Ana", "Bo"), order(store, Grade.S));
	}

	@Test
	void clampsAtTheTopRatherThanFailing(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		// Ana is already first, so there is nowhere to go.
		assertEquals(0, store.bump(A, 5));
		assertEquals(List.of("Ana", "Bo", "Cy"), order(store, Grade.S));
	}

	@Test
	void clampsAtTheBottom(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		assertEquals(0, store.bump(C, -5));
		assertEquals(List.of("Ana", "Bo", "Cy"), order(store, Grade.S));
	}

	@Test
	void anOversizedMoveGoesAsFarAsItCan(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		// Cy is last of three, so "up 99" means up two.
		assertEquals(2, store.bump(C, 99));
		assertEquals(List.of("Cy", "Ana", "Bo"), order(store, Grade.S));
	}

	@Test
	void reportsWhenThePlayerIsNotListed(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		assertEquals(-1, store.bump(D, 1));
	}

	@Test
	void onlyTouchesTheOwnTier(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		store.set(D, "Dee", Grade.B, "t", "1");

		store.bump(C, 1);
		assertEquals(List.of("Dee"), order(store, Grade.B), "B is untouched");
		assertEquals(List.of("Ana", "Cy", "Bo"), order(store, Grade.S));
	}

	@Test
	void orderSurvivesAReload(@TempDir Path dir) {
		Path file = dir.resolve("grades.json");
		GradeStore store = seeded(dir);
		store.bump(C, 2);

		// The whole point of persisting order: a restart must not reshuffle.
		assertEquals(List.of("Cy", "Ana", "Bo"), order(GradeStore.load(file), Grade.S));
	}

	@Test
	void regradingKeepsAPlayersPlace(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		store.bump(C, 2);
		// Setting the same tier again should not send them back to the end.
		store.set(C, "Cy", Grade.S, "t", "1");
		assertEquals(List.of("Cy", "Ana", "Bo"), order(store, Grade.S));
	}

	@Test
	void movingTierSendsThemToTheBackOfTheNewOne(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		store.set(D, "Dee", Grade.B, "t", "1");
		store.set(A, "Ana", Grade.B, "t", "1");

		assertEquals(List.of("Dee", "Ana"), order(store, Grade.B));
		assertEquals(List.of("Bo", "Cy"), order(store, Grade.S));
	}

	@Test
	void retiredPlayersKeepTheirTierAndPlace(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		assertTrue(store.retire(B, true));

		GradeStore.Record record = store.get(B);
		assertTrue(record.retired());
		assertEquals(Grade.S, record.grade(), "retirement does not clear the tier");
		// Still in all(); it is the renderer that filters them out, so a
		// lookup can still find them.
		assertEquals(List.of("Ana", "Bo", "Cy"), order(store, Grade.S));
	}

	@Test
	void retiringTwiceReportsNoChange(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		assertTrue(store.retire(A, true));
		assertFalse(store.retire(A, true), "already retired");
		assertTrue(store.retire(A, false), "brought back");
		assertFalse(store.get(A).retired());
	}

	@Test
	void retiringSomeoneUnlistedReportsNothing(@TempDir Path dir) {
		assertFalse(seeded(dir).retire(D, true));
	}

	@Test
	void retirementSurvivesAReload(@TempDir Path dir) {
		Path file = dir.resolve("grades.json");
		GradeStore store = seeded(dir);
		store.retire(B, true);
		assertTrue(GradeStore.load(file).get(B).retired());
	}

	@Test
	void settingATierBringsThemBack(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		store.retire(B, true);
		// An explicit new tier is a clearer statement than the stale flag.
		store.set(B, "Bo", Grade.A, "t", "1");
		assertFalse(store.get(B).retired());
	}

	@Test
	void tiedOrdersFallBackToTheName(@TempDir Path dir) {
		// Everyone loaded from an older file has order 0; the listing must still
		// be stable rather than arbitrary.
		GradeStore store = GradeStore.load(dir.resolve("grades.json"));
		store.set(C, "Cy", Grade.S, "t", "1");
		store.set(A, "Ana", Grade.S, "t", "1");
		assertNotEquals(List.of(), order(store, Grade.S));
	}

	/** Names on a tier as the tierlist draws it: retired players are hidden. */
	private static List<String> visible(GradeStore store, Grade tier) {
		List<String> out = new ArrayList<>();
		for (GradeStore.Record record : store.all()) {
			if (record.grade() == tier && !record.retired()) {
				out.add(record.name());
			}
		}
		return out;
	}

	@Test
	void bumpingCountsOnlyThePlayersOnScreen(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		store.set(D, "Di", Grade.S, "t", "1");

		// Ana Bo Cy Di, with the two in the middle retired, so the drawn list
		// is just Ana Di.
		assertTrue(store.retire(B, true));
		assertTrue(store.retire(C, true));
		assertEquals(List.of("Ana", "Di"), visible(store, Grade.S));

		// One place up is one place up on screen. Counting the retired pair
		// would have needed three to pass Ana.
		assertEquals(1, store.bump(D, 1));
		assertEquals(List.of("Di", "Ana"), visible(store, Grade.S));
	}

	@Test
	void aRetiredPlayerCannotBeBumped(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		assertTrue(store.retire(C, true));

		// They are not on the drawn list, so there is no place to move within.
		assertEquals(-1, store.bump(C, 1));
	}

	@Test
	void bumpingDoesNotDisturbRetiredPlayers(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		assertTrue(store.retire(A, true));
		int kept = store.get(A).order();

		store.bump(C, 1);

		// Renumbering the visible players must not renumber the hidden one.
		assertEquals(kept, store.get(A).order());
		assertTrue(store.get(A).retired());
	}

	@Test
	void placesAreCountedOverTheDrawnRow(@TempDir Path dir) {
		GradeStore store = seeded(dir);
		store.set(D, "Di", Grade.S, "t", "1");
		assertTrue(store.retire(B, true));

		// What /whois counts: the drawn row, retired players left out. Ana is
		// place 1 and Cy place 2, even though Bo sits between them in storage.
		List<String> row = visible(store, Grade.S);
		assertEquals(List.of("Ana", "Cy", "Di"), row);
		assertEquals("Cy", row.get(2 - 1));

		// And it tracks a bump, so a place read off the picture stays right.
		store.bump(D, 2);
		assertEquals(List.of("Di", "Ana", "Cy"), visible(store, Grade.S));
	}
}
