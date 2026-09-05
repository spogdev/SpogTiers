package com.spog.tiers.config;

import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.TierList;

import java.util.ArrayList;
import java.util.List;

/**
 * The nametag, as a list of elements the user arranges.
 *
 * <p>Replaces the three fixed slots the tag used to be built from -- above,
 * left and right -- which could only ever hold a tier and only ever in that
 * order. An element knows what it is and which row it sits on, so a separator
 * can be moved, a second name is possible, and the region is no longer pinned
 * to one end of one row.
 *
 * <p>Rows are drawn as three lines: {@link Row#TOP} above the name plate,
 * {@link Row#MIDDLE} the plate itself, {@link Row#BOTTOM} below it. Within a
 * row, elements are drawn in list order, so moving an element left or right is
 * a swap with its neighbour.
 *
 * <p>Read by {@link com.spog.tiers.util.TagRenderer} and edited by the tag
 * editor on the nametag tab. It is stored in the config file as a plain list,
 * so a hand-edited file stays readable.
 */
public class TagLayout {
	/** Which of the three lines an element sits on. */
	public enum Row {
		TOP("Top"),
		MIDDLE("Middle"),
		BOTTOM("Bottom");

		private final String title;

		Row(String title) {
			this.title = title;
		}

		public String title() {
			return title;
		}
	}

	/** What an element is. */
	public enum Kind {
		/** The player's own name. At least one is required. */
		NAME("Name"),
		/** A tier from one list, or the best across all of them. */
		TIER("Tier"),
		/** The player's region code. */
		REGION("Region"),
		/** A character between two other elements. */
		SEPARATOR("Separator");

		private final String title;

		Kind(String title) {
			this.title = title;
		}

		public String title() {
			return title;
		}
	}

	/**
	 * One piece of the tag.
	 *
	 * <p>A single class rather than a subclass per kind: the config is
	 * serialised with Gson, which has no polymorphism without a type adapter,
	 * and the unused fields cost nothing. Which fields matter depends on
	 * {@link #kind}, and the editor only offers the ones that do.
	 */
	public static class Element {
		public Kind kind = Kind.NAME;
		public Row row = Row.MIDDLE;

		/** Tier elements: which list, or null for the best across all of them. */
		public TierList list = TierList.PVPTIERS;

		/**
		 * Tier elements: our own Door SMP list rather than one of the six.
		 *
		 * <p>A flag rather than a value of {@link TierList}: Door SMP is a
		 * separate service with one grade per player and no gamemodes, so it
		 * has no place in an enum whose every member has an endpoint, a set of
		 * modes and a shared shape. When this is set, {@link #list} and
		 * {@link #gamemode} are both ignored.
		 */
		public boolean doorSmp;

		/** Tier elements: which gamemode, or null for that list's best. */
		public Gamemode gamemode;

		/** Separator elements: what to draw, and in what colour. */
		public String character = "|";
		public int colour = 0x555555;

		public Element() {
		}

		public Element(Kind kind, Row row) {
			this.kind = kind;
			this.row = row;
		}

		/** A copy, so an editing session can be abandoned without effect. */
		public Element copy() {
			Element copy = new Element(kind, row);
			copy.list = list;
			copy.doorSmp = doorSmp;
			copy.gamemode = gamemode;
			copy.character = character;
			copy.colour = colour;
			return copy;
		}

		/** How this element reads in the editor's element list. */
		public String title() {
			return switch (kind) {
				case NAME -> "Name";
				case REGION -> "Region";
				case SEPARATOR -> "Separator";
				case TIER -> {
					if (doorSmp) {
						yield "Tier: Door SMP";
					}
					yield list == null ? "Tier: Best" : "Tier: " + list.displayName();
				}
			};
		}
	}

	/** Every element, in drawing order within each row. */
	public List<Element> elements = new ArrayList<>();

	public TagLayout() {
	}

	/**
	 * The layout the mod ships with: a tier, the name, a tier, separated.
	 *
	 * <p>The same tag the three fixed slots used to produce with their own
	 * defaults, so someone who never opens the editor sees no change.
	 */
	public static TagLayout defaults() {
		TagLayout layout = new TagLayout();
		layout.elements.add(new Element(Kind.TIER, Row.MIDDLE));
		layout.elements.add(separator());
		layout.elements.add(new Element(Kind.NAME, Row.MIDDLE));
		return layout;
	}

	private static Element separator() {
		Element element = new Element(Kind.SEPARATOR, Row.MIDDLE);
		element.character = "|";
		element.colour = 0x555555;
		return element;
	}

	/** A deep copy, for editing without committing. */
	public TagLayout copy() {
		TagLayout copy = new TagLayout();
		for (Element element : elements) {
			copy.elements.add(element.copy());
		}
		return copy;
	}

	/** The elements on one row, in order. */
	public List<Element> row(Row row) {
		List<Element> out = new ArrayList<>();
		for (Element element : elements) {
			if (element.row == row) {
				out.add(element);
			}
		}
		return out;
	}

	/**
	 * Whether this layout may be saved.
	 *
	 * <p>A tag with no name is not a nametag: it would leave a player
	 * unidentifiable, which is worse than any arrangement the editor can
	 * otherwise produce.
	 */
	public boolean hasName() {
		for (Element element : elements) {
			if (element.kind == Kind.NAME) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Moves an element one place along its row.
	 *
	 * <p>Works on the row rather than the backing list, so an element only
	 * ever swaps with what is drawn beside it, not with whatever happens to be
	 * adjacent in storage.
	 *
	 * @param towardsEnd true to move right, false to move left
	 * @return true if it moved
	 */
	public boolean shift(Element element, boolean towardsEnd) {
		List<Element> row = row(element.row);
		int at = row.indexOf(element);
		int to = at + (towardsEnd ? 1 : -1);
		if (at < 0 || to < 0 || to >= row.size()) {
			return false;
		}
		Element other = row.get(to);
		int here = elements.indexOf(element);
		int there = elements.indexOf(other);
		elements.set(here, other);
		elements.set(there, element);
		return true;
	}

	/**
	 * Moves an element to the row above or below, at the end of it.
	 *
	 * @return true if it moved
	 */
	public boolean reRow(Element element, boolean down) {
		Row[] rows = Row.values();
		int at = element.row.ordinal() + (down ? 1 : -1);
		if (at < 0 || at >= rows.length) {
			return false;
		}
		element.row = rows[at];
		// Moved to the end of the backing list so it lands at the end of its
		// new row: the row's order is the order elements appear here.
		elements.remove(element);
		elements.add(element);
		return true;
	}

	/** Repairs a layout read from a file that has been edited by hand. */
	public void normalise() {
		if (elements == null) {
			elements = new ArrayList<>();
		}
		elements.removeIf(element -> element == null || element.kind == null);
		for (Element element : elements) {
			if (element.row == null) {
				element.row = Row.MIDDLE;
			}
			if (element.kind == Kind.SEPARATOR
					&& (element.character == null || element.character.isEmpty())) {
				element.character = "|";
			}
		}
		// A layout with no name cannot be drawn usefully, and one with no
		// elements at all is a file someone has emptied by accident.
		if (!hasName()) {
			elements.add(new Element(Kind.NAME, Row.MIDDLE));
		}
	}
}
