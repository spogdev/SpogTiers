package com.spog.tiers.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
	void tiedOrdersFallBackToTheName(@TempDir Path dir) {
		// Everyone loaded from an older file has order 0; the listing must still
		// be stable rather than arbitrary.
		GradeStore store = GradeStore.load(dir.resolve("grades.json"));
		store.set(C, "Cy", Grade.S, "t", "1");
		store.set(A, "Ana", Grade.S, "t", "1");
		assertNotEquals(List.of(), order(store, Grade.S));
	}
}
