package com.spog.tiers.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class GradeTest {
	@Test
	void parsesEveryLabelItPrints() {
		// Whatever a grade calls itself must parse back to that same grade, so
		// a value written to grades.json can always be read again.
		for (Grade grade : Grade.values()) {
			assertSame(grade, Grade.parse(grade.label()));
		}
	}

	@Test
	void parseIsLenientAboutCaseAndSpace() {
		assertSame(Grade.A_PLUS, Grade.parse("a+"));
		assertSame(Grade.A_PLUS, Grade.parse("  A+  "));
		assertSame(Grade.S, Grade.parse("s"));
	}

	@Test
	void parseRejectsAnythingElse() {
		assertNull(Grade.parse(null));
		assertNull(Grade.parse(""));
		assertNull(Grade.parse("E"));
		assertNull(Grade.parse("A++"));
		assertNull(Grade.parse("SS"));
		assertNull(Grade.parse("+"));
	}

	@Test
	void publishesTheColoursTheTierlistUses() {
		// These are the tierlist's own colours and are what the mod draws, so
		// they are pinned rather than left to drift.
		assertEquals("F760E6", Grade.S.hex());
		assertEquals("A034C7", Grade.A_PLUS.hex());
		assertEquals("D42626", Grade.A.hex());
		assertEquals("EB8526", Grade.B_PLUS.hex());
		assertEquals("00F2FF", Grade.B.hex());
		assertEquals("EDE04E", Grade.C.hex());
		assertEquals("5F9448", Grade.D.hex());
		assertEquals("824B27", Grade.F.hex());
	}

	@Test
	void ordersBestFirst() {
		// all() sorts on ordinal, so the declaration order is load-bearing.
		assertEquals(0, Grade.S.ordinal());
		assertEquals(Grade.values().length - 1, Grade.F.ordinal());
	}
}
