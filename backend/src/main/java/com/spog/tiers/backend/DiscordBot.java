package com.spog.tiers.backend;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Door SMP grading bot.
 *
 * <p><b>Authorization is re-checked inside every write handler</b> against
 * {@link GraderStore}, rather than trusted from wherever the command came from.
 * An unauthorised caller gets the same refusal whether or not the bot has any
 * graders configured, so probing tells them nothing.
 *
 * <p>Failures reply ephemerally -- only the caller sees them -- so a mistyped
 * name does not litter the channel.
 */
public final class DiscordBot extends ListenerAdapter {
	private static final Logger LOG = LoggerFactory.getLogger(DiscordBot.class);

	/** The tierlist's name, as players see it. */
	public static final String LIST_NAME = "Door SMP";

	/** Shown when a lookup finds nobody. Neutral, not alarming: most players are ungraded. */
	private static final Color NEUTRAL = new Color(0x9AA5B1);

	/** The choice that clears a player's tier, rather than setting one. */
	private static final String NONE_CHOICE = "None";

	/** Button id prefixes for the /clear confirmation. */
	private static final String CLEAR_CONFIRM = "clear.confirm";
	private static final String CLEAR_CANCEL = "clear.cancel";

	/**
	 * Stands in for the tier in a button id when the whole list is being
	 * cleared. Not a tier label, so it cannot collide with one.
	 */
	private static final String ALL_TIERS = "*";

	private final GradeStore grades;
	private final GraderStore graders;
	private final MojangNames names;
	private final TierlistImage image = new TierlistImage();

	public DiscordBot(GradeStore grades, GraderStore graders, MojangNames names) {
		this.grades = grades;
		this.graders = graders;
		this.names = names;
	}

	/**
	 * Log in and register commands.
	 *
	 * <p>No privileged intents: the bot only ever reacts to its own slash
	 * commands, so it needs neither message content nor member lists.
	 */
	public JDA start(String token) throws InterruptedException {
		// No intents at all: the bot only ever reacts to its own slash commands,
		// so it needs neither message content nor member lists. Guild caching is
		// left on deliberately -- createLight() would disable it, and commands
		// are registered per guild, which needs getGuilds() to be populated.
		JDA jda = JDABuilder.createDefault(token)
				.enableIntents(java.util.Collections.emptyList())
				.setMemberCachePolicy(net.dv8tion.jda.api.utils.MemberCachePolicy.NONE)
				.disableCache(java.util.EnumSet.allOf(
						net.dv8tion.jda.api.utils.cache.CacheFlag.class))
				.addEventListeners(this)
				.build();
		jda.awaitReady();
		return jda;
	}

	@Override
	public void onReady(@NotNull ReadyEvent event) {
		LOG.info("logged in as {}", event.getJDA().getSelfUser().getName());

		// Register per guild, not globally. Global commands take up to an hour
		// to propagate, which looks exactly like a broken bot; a guild's appear
		// at once. The bot is invited to a handful of servers at most, so there
		// is nothing to gain from the global route.
		// Global registration is what makes the commands user-installable: a
		// guild-scoped command belongs to that server and cannot travel with a
		// person's account. The cost is propagation, which Discord takes up to
		// an hour over.
		event.getJDA().updateCommands().addCommands(commands()).queue(
				ok -> LOG.info("registered {} command(s) globally; user installs "
						+ "and new servers pick these up, which can take up to "
						+ "an hour to propagate", ok.size()),
				error -> LOG.error("could not register global commands", error));

		// Servers the bot is actually in also get a guild-scoped copy, which
		// appears at once. Discord shows a guild command in place of the global
		// one of the same name rather than both, so this is a head start rather
		// than a duplicate.
		for (Guild guild : event.getJDA().getGuilds()) {
			register(guild);
		}
	}

	/** A server that invited the bot while it was already running. */
	@Override
	public void onGuildJoin(@NotNull GuildJoinEvent event) {
		LOG.info("joined {}", event.getGuild().getName());
		register(event.getGuild());
	}

	/** Publish the command set to one guild, for immediate availability. */
	private void register(Guild guild) {
		List<SlashCommandData> commands = commands();
		guild.updateCommands().addCommands(commands).queue(
				ok -> LOG.info("registered {} command(s) in {}",
						commands.size(), guild.getName()),
				error -> LOG.error("could not register commands in {}",
						guild.getName(), error));
	}

	/** The command set. Built fresh each time; JDA's builders are not reusable. */
	private List<SlashCommandData> commands() {
		OptionData player = new OptionData(OptionType.STRING, "player",
				"The Minecraft username", true);

		// A choice list rather than free text: an invalid grade becomes
		// unrepresentable, and graders get a picker instead of having to
		// remember the ladder.
		OptionData grade = new OptionData(OptionType.STRING, "grade",
				"The tier to assign, or None to remove it", true);
		for (Grade value : Grade.values()) {
			grade.addChoice(value.label(), value.label());
		}
		// Removing a tier is the same act as assigning one -- deciding where a
		// player sits -- so it is a choice here rather than a second command.
		grade.addChoice(NONE_CHOICE, NONE_CHOICE);

		// Positive is up, negative is down, matching how the move reads aloud:
		// "bump them up two" is 2.
		OptionData places = new OptionData(OptionType.INTEGER, "places",
				"Places to move: 1 is up one, -1 is down one", true);

		OptionData filter = new OptionData(OptionType.STRING, "grade",
				"Only show this tier", false);
		for (Grade value : Grade.values()) {
			filter.addChoice(value.label(), value.label());
		}

		// Optional, and its absence is the dangerous case: with no tier named
		// the whole list goes. Left optional anyway rather than requiring an
		// "everything" choice, because the confirmation is what makes the
		// scale of it clear, and a required option would not change that.
		OptionData clearFilter = new OptionData(OptionType.STRING, "grade",
				"Only clear this tier, instead of the whole list", false);
		for (Grade value : Grade.values()) {
			clearFilter.addChoice(value.label(), value.label());
		}

		// Installable either to a server or to a person's own account, and
		// usable in servers, the bot's DMs, and group chats. A user who adds
		// the app carries these commands into any server they are in, whether
		// or not the bot itself is there.
		List<SlashCommandData> commands = List.of(
				Commands.slash("assign", "Assign a tier to a player or remove it")
						.addOptions(player, grade),
				Commands.slash("tier", "Search for a player on the tierlist")
						.addOptions(player),
				Commands.slash("tierlist", "Show current tierlist")
						.addOptions(filter),
				Commands.slash("bump", "Move a player within their tier")
						.addOptions(player, places),
				Commands.slash("retire", "Toggle retirement of a player")
						.addOptions(player),
				Commands.slash("clear", "Remove every player from the tierlist, or from one tier")
						.addOptions(clearFilter));

		for (SlashCommandData command : commands) {
			command.setIntegrationTypes(IntegrationType.ALL)
					.setContexts(InteractionContextType.ALL);
		}
		return commands;
	}

	@Override
	public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
		switch (event.getName()) {
			case "assign" -> assign(event);
			case "tier" -> lookup(event);
			case "tierlist" -> list(event);
			case "bump" -> bump(event);
			case "retire" -> retire(event);
			case "clear" -> clear(event);
			default -> event.reply("Unknown command.").setEphemeral(true).queue();
		}
	}

	private void assign(SlashCommandInteractionEvent event) {
		if (!authorised(event)) {
			return;
		}
		String name = event.getOption("player", "", OptionMapping::getAsString);
		String choice = event.getOption("grade", "", OptionMapping::getAsString);
		boolean removing = NONE_CHOICE.equalsIgnoreCase(choice.trim());

		Grade grade = removing ? null : Grade.parse(choice);
		if (grade == null && !removing) {
			event.reply("That is not a valid tier").setEphemeral(true).queue();
			return;
		}

		// Mojang can be slow; acknowledge first so the interaction cannot expire.
		event.deferReply().queue();
		UUID id = resolve(event, name);
		if (id == null) {
			return;
		}

		if (removing) {
			remove(event, id, name);
			return;
		}

		String current = names.nameFor(id);
		String shown = current == null ? name : current;
		grades.set(id, shown, grade, event.getUser().getName(), event.getUser().getId());

		LOG.info("{} set {} ({}) to {}", event.getUser().getName(), shown, id, grade.label());
		event.getHook().sendMessageEmbeds(new EmbedBuilder()
				.setDescription("Set **" + shown + "** tier to **" + grade.label() + "**")
				.setColor(new Color(grade.color()))
				.build()).queue();
	}

	/** The None choice: take the player off the tierlist entirely. */
	private void remove(SlashCommandInteractionEvent event, UUID id, String name) {
		GradeStore.Record previous = grades.remove(id);
		if (previous == null) {
			event.getHook().sendMessage("**" + name + "** is not on the tierlist")
					.setEphemeral(true).queue();
			return;
		}
		String shown = previous.name().isEmpty() ? name : previous.name();
		LOG.info("{} removed {} from the tierlist (was {})", event.getUser().getName(),
				shown, previous.grade().label());
		// Coloured with the tier they held, so the message still says which.
		event.getHook().sendMessageEmbeds(new EmbedBuilder()
				.setDescription("Removed **" + shown + "** from tierlist")
				.setColor(new Color(previous.grade().color()))
				.build()).queue();
	}


	private void lookup(SlashCommandInteractionEvent event) {
		String name = event.getOption("player", "", OptionMapping::getAsString);
		event.deferReply().queue();
		UUID id = resolve(event, name);
		if (id == null) {
			return;
		}

		// Keep the stored name current while we happen to know it.
		String current = names.nameFor(id);
		if (current != null) {
			grades.refreshName(id, current);
		}
		String shown = current == null ? name : current;

		GradeStore.Record record = grades.get(id);
		if (record == null) {
			event.getHook().sendMessageEmbeds(new EmbedBuilder()
					.setTitle(shown)
					.setThumbnail(head(id))
					.setDescription("Not on the " + LIST_NAME + " tierlist yet")
					.setColor(NEUTRAL)
					.build()).queue();
			return;
		}

		EmbedBuilder embed = new EmbedBuilder()
				.setTitle(shown)
				.setThumbnail(head(id))
				.setColor(new Color(record.grade().color()))
				.addField(LIST_NAME + " Tierlist",
						"**" + (record.retired() ? "R" : "") + record.grade().label() + "**"
								+ (record.retired() ? "  *(retired)*" : ""), false);
		if (record.gradedBy() != null && !record.gradedBy().isBlank()) {
			// <t:unix:R> renders as "3 days ago" in each reader's own locale.
			embed.setFooter("Graded by " + record.gradedBy());
			embed.addField("Graded", "<t:" + record.gradedAt() + ":R>", false);
		}
		event.getHook().sendMessageEmbeds(embed.build()).queue();
	}

	private void list(SlashCommandInteractionEvent event) {
		Grade filter = Grade.parse(event.getOption("grade", "", OptionMapping::getAsString));

		// all() is already sorted best tier first, then by name, so grouping is
		// just a walk: a LinkedHashMap keeps the tiers in that same order.
		Map<Grade, List<GradeStore.Record>> byTier = new LinkedHashMap<>();
		int total = 0;
		for (GradeStore.Record record : grades.all()) {
			// Retired players keep their tier and can still be looked up one by
			// one, but the picture is about who is currently ranked.
			if (record.retired() || (filter != null && record.grade() != filter)) {
				continue;
			}
			byTier.computeIfAbsent(record.grade(), k -> new ArrayList<>()).add(record);
			total++;
		}

		if (total == 0) {
			event.reply(filter == null
					? "Nobody is on the tierlist yet"
					: "Nobody is **" + filter.label() + "**").queue();
			return;
		}

		// Every tier gets a row, even an empty one, so the picture shows the
		// whole ladder rather than only the rungs that happen to be occupied.
		// Filtered to one tier, only that row is drawn.
		List<Map.Entry<Grade, List<GradeStore.Record>>> rows = new ArrayList<>();
		for (Grade tier : Grade.values()) {
			if (filter != null && tier != filter) {
				continue;
			}
			rows.add(Map.entry(tier, byTier.getOrDefault(tier, List.of())));
		}

		// Faces are fetched over the network, so acknowledge first: rendering
		// can outlast the three seconds Discord allows for a reply.
		event.deferReply().queue();

		byte[] png = image.render(rows);
		if (png == null) {
			// Rendering is a nicety; the standing itself is the point, so fall
			// back to text rather than failing the command.
			event.getHook().sendMessageEmbeds(textList(filter, rows, total)).queue();
			return;
		}
		event.getHook()
				.sendFiles(FileUpload.fromData(png, "tierlist.png"))
				.queue();
	}

	/** The listing as text, for when the image cannot be drawn. */
	private MessageEmbed textList(Grade filter,
			List<Map.Entry<Grade, List<GradeStore.Record>>> rows, int total) {
		StringBuilder body = new StringBuilder();
		int shown = 0;
		for (Map.Entry<Grade, List<GradeStore.Record>> entry : rows) {
			if (entry.getValue().isEmpty()) {
				continue;
			}
			StringBuilder block = new StringBuilder();
			if (body.length() > 0) {
				block.append('\n');
			}
			block.append("**").append(entry.getKey().label()).append("**\n");
			for (GradeStore.Record record : entry.getValue()) {
				block.append("- ").append(record.name()).append('\n');
			}
			if (body.length() + block.length() > 3900) {
				break;
			}
			body.append(block);
			shown += entry.getValue().size();
		}
		if (shown < total) {
			body.append("\n_...and ").append(total - shown).append(" more._");
		}

		return new EmbedBuilder()
				.setTitle(LIST_NAME + " Tierlist"
						+ (filter == null ? "" : " -- " + filter.label()))
				.setDescription(body.toString())
				.setColor(filter == null ? NEUTRAL : new Color(filter.color()))
				.setFooter(total + " player" + (total == 1 ? "" : "s"))
				.build();
	}

	private void bump(SlashCommandInteractionEvent event) {
		if (!authorised(event)) {
			return;
		}
		String name = event.getOption("player", "", OptionMapping::getAsString);
		int places = event.getOption("places", 0L, OptionMapping::getAsLong).intValue();
		if (places == 0) {
			event.reply("Give a number of places: 1 moves up one, -1 moves down one")
					.setEphemeral(true).queue();
			return;
		}

		event.deferReply().queue();
		UUID id = resolve(event, name);
		if (id == null) {
			return;
		}

		int moved = grades.bump(id, places);
		if (moved < 0) {
			event.getHook().sendMessage("**" + name + "** is not on the tierlist")
					.setEphemeral(true).queue();
			return;
		}

		GradeStore.Record record = grades.get(id);
		String shown = record.name().isEmpty() ? name : record.name();
		if (moved == 0) {
			event.getHook().sendMessage("**" + shown + "** is already at the "
					+ (places > 0 ? "top" : "bottom") + " of **"
					+ record.grade().label() + "**").queue();
			return;
		}

		LOG.info("{} bumped {} by {} in {}", event.getUser().getName(), shown, moved,
				record.grade().label());
		event.getHook().sendMessageEmbeds(new EmbedBuilder()
				.setDescription("Moved **" + shown + "** "
						+ (moved > 0 ? "up " : "down ") + Math.abs(moved)
						+ (Math.abs(moved) == 1 ? " place" : " places")
						+ " in **" + record.grade().label() + "**")
				.setColor(new Color(record.grade().color()))
				.build()).queue();
	}

	private void retire(SlashCommandInteractionEvent event) {
		if (!authorised(event)) {
			return;
		}
		String name = event.getOption("player", "", OptionMapping::getAsString);

		event.deferReply().queue();
		UUID id = resolve(event, name);
		if (id == null) {
			return;
		}

		GradeStore.Record record = grades.get(id);
		if (record == null) {
			event.getHook().sendMessage("**" + name + "** is not on the tierlist")
					.setEphemeral(true).queue();
			return;
		}
		String shown = record.name().isEmpty() ? name : record.name();

		// A toggle: whichever way they are, flip them. One command covers both
		// directions, and there is no way to ask for the state they already
		// have and get an unhelpful "already retired" back.
		boolean retiring = !record.retired();
		grades.retire(id, retiring);

		LOG.info("{} {} {}", event.getUser().getName(),
				retiring ? "retired" : "un-retired", shown);
		// The tier is named plainly in both messages: the R prefix belongs on
		// the badge in game, not in a sentence that already says "Retired".
		event.getHook().sendMessageEmbeds(new EmbedBuilder()
				.setDescription((retiring ? "Retired **" : "Unretired **") + shown
						+ "** at **" + record.grade().label() + "**")
				.setColor(new Color(record.grade().color()))
				.build()).queue();
	}

	/**
	 * Ask an admin to confirm clearing the list, or one tier of it.
	 *
	 * <p>Nothing is removed here. The work happens in
	 * {@link #onButtonInteraction}, once the caller has confirmed, so that a
	 * mistyped command is never destructive on its own.
	 */
	private void clear(SlashCommandInteractionEvent event) {
		if (!authorised(event)) {
			return;
		}
		String choice = event.getOption("grade", "", OptionMapping::getAsString);
		Grade grade = choice.isBlank() ? null : Grade.parse(choice);
		if (!choice.isBlank() && grade == null) {
			event.reply("**" + choice + "** is not a tier").setEphemeral(true).queue();
			return;
		}

		// Counted now only to say how much is at stake. The count is taken
		// again when the button is pressed, so a slow confirmation cannot
		// remove more than the admin was shown.
		long affected = grades.all().stream()
				.filter(record -> grade == null || record.grade() == grade)
				.count();
		String scope = grade == null ? "the entire tierlist"
				: "everyone at **" + grade.label() + "**";

		if (affected == 0) {
			event.reply("There is nobody to clear from " + scope + ".")
					.setEphemeral(true).queue();
			return;
		}

		EmbedBuilder embed = new EmbedBuilder()
				.setTitle("Clear " + (grade == null ? "the tierlist" : grade.label() + "?"))
				.setDescription("This removes **" + affected + "** player"
						+ (affected == 1 ? "" : "s") + " from " + scope
						+ ".\n\nThis cannot be undone.")
				.setColor(grade == null ? Color.RED : new Color(grade.color()));

		// The caller's id rides in the button id so the handler can refuse a
		// different admin pressing someone else's prompt: two people clearing
		// at once should not have one of them confirm the other's decision.
		String owner = event.getUser().getId();
		String suffix = ":" + owner + ":" + (grade == null ? ALL_TIERS : grade.label());

		// Ephemeral: the prompt is the caller's own decision to make, and a
		// destructive button left sitting in a channel invites a misclick.
		event.replyEmbeds(embed.build())
				.addActionRow(
						Button.danger(CLEAR_CONFIRM + suffix, "Clear"),
						Button.secondary(CLEAR_CANCEL + suffix, "Cancel"))
				.setEphemeral(true)
				.queue();
	}

	/** The confirm and cancel buttons from {@link #clear}. */
	@Override
	public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
		String id = event.getComponentId();
		if (!id.startsWith(CLEAR_CONFIRM) && !id.startsWith(CLEAR_CANCEL)) {
			return;
		}

		// id is "<action>:<caller>:<tier>", and the tier may be the all-tiers
		// marker. Split from the left on a fixed count: a tier label never
		// contains a colon, so the remainder is unambiguous.
		String[] parts = id.split(":", 3);
		if (parts.length != 3) {
			return;
		}
		String owner = parts[1];
		String tier = parts[2];

		// Checked again rather than trusted from the prompt: a grader can be
		// removed between issuing the command and pressing the button.
		if (!graders.isGrader(event.getUser().getId())) {
			event.reply("You do not have permission to set tiers").setEphemeral(true).queue();
			return;
		}
		if (!event.getUser().getId().equals(owner)) {
			event.reply("That confirmation belongs to someone else. Run /clear yourself.")
					.setEphemeral(true).queue();
			return;
		}

		if (id.startsWith(CLEAR_CANCEL)) {
			event.editMessageEmbeds(new EmbedBuilder()
					.setDescription("Cancelled. Nothing was removed.")
					.setColor(Color.GRAY)
					.build()).setComponents().queue();
			return;
		}

		Grade grade = ALL_TIERS.equals(tier) ? null : Grade.parse(tier);
		if (!ALL_TIERS.equals(tier) && grade == null) {
			event.reply("That tier no longer exists.").setEphemeral(true).queue();
			return;
		}

		int removed = grades.clear(grade);
		String scope = grade == null ? "the tierlist" : grade.label();
		LOG.info("{} cleared {} ({} player(s))", event.getUser().getName(), scope, removed);

		// The buttons are stripped as well as the embed replaced, so the
		// prompt cannot be confirmed twice.
		event.editMessageEmbeds(new EmbedBuilder()
				.setDescription("Removed **" + removed + "** player"
						+ (removed == 1 ? "" : "s") + " from "
						+ (grade == null ? "the tierlist" : "**" + grade.label() + "**"))
				.setColor(grade == null ? Color.RED : new Color(grade.color()))
				.build()).setComponents().queue();
	}

	/**
	 * Check the caller may grade, refusing ephemerally if not.
	 *
	 * @return true if the command should go ahead
	 */
	private boolean authorised(SlashCommandInteractionEvent event) {
		if (graders.isGrader(event.getUser().getId())) {
			return true;
		}
		// Identical whether or not graders are configured: a caller learns
		// nothing about the setup from being refused.
		event.reply("You do not have permission to set tiers").setEphemeral(true).queue();
		return false;
	}

	/**
	 * Resolve a name to a UUID, replying with the reason if it cannot be done.
	 *
	 * @return the UUID, or null if the caller has already been answered
	 */
	private UUID resolve(SlashCommandInteractionEvent event, String name) {
		try {
			UUID id = names.idFor(name);
			if (id == null) {
				event.getHook().sendMessage("No Minecraft account called **" + name + "**")
						.setEphemeral(true).queue();
				return null;
			}
			return id;
		} catch (MojangNames.Unavailable e) {
			LOG.warn("mojang lookup for '{}' failed ({})", name, e.toString());
			event.getHook().sendMessage("Could not reach Mojang to look up **" + name
					+ "**. Try again in a moment.").setEphemeral(true).queue();
			return null;
		}
	}

	/**
	 * The player's face, for the embed thumbnail.
	 *
	 * <p>Undashed and with no query string on purpose: minotar 301-redirects a
	 * dashed UUID, and Discord's image proxy is unreliable with redirects, so
	 * the thumbnail silently fails to load.
	 */
	private static String head(UUID id) {
		return "https://minotar.net/helm/" + id.toString().replace("-", "") + "/128.png";
	}
}
