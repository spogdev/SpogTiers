package com.spog.tiers.data;

import java.util.List;

/**
 * A player's past names, newest first.
 *
 * <p>Mojang deleted its name-history endpoint in 2022, so this comes from
 * laby.net, which kept its own records from before the removal and has carried
 * on tracking changes since. Entries it inferred rather than observed are
 * flagged {@code accurate=false} upstream; we keep them, because for anything
 * after 2022 that is all there is.
 */
public record NameHistory(List<Entry> entries) {
	public static final NameHistory EMPTY = new NameHistory(List.of());

	/**
	 * One name.
	 *
	 * @param name      the name held
	 * @param changedAt when it was taken, in epoch seconds; 0 for the original
	 *                  name, which Mojang never dated
	 */
	public record Entry(String name, long changedAt) {
		public boolean isDated() {
			return changedAt > 0L;
		}
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	/** Names before the current one, newest first. */
	public List<Entry> previous() {
		return entries.size() <= 1 ? List.of() : entries.subList(1, entries.size());
	}
}
