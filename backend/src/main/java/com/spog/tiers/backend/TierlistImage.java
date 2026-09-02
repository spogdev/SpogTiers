package com.spog.tiers.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Draws the tierlist as the familiar tier-maker grid: one coloured row per
 * tier, players' faces laid out along it.
 *
 * <p>A picture reads far faster than a list of names -- the whole standing is
 * one glance rather than a scroll -- and it is what people already expect a
 * tierlist to look like.
 *
 * <p>Faces are fetched concurrently and cached. A face that will not load is
 * drawn as a blank head rather than skipped, so a row never silently loses a
 * player to someone else's outage.
 */
public final class TierlistImage {
	private static final Logger LOG = LoggerFactory.getLogger(TierlistImage.class);

	/** Each face cell, matching the source image's proportions. */
	private static final int FACE = 64;
	private static final int GAP = 4;
	/** Width of the coloured tier label down the left. */
	private static final int LABEL_WIDTH = 88;
	private static final int PADDING = 8;
	/** Faces per row before wrapping to a second line within the same tier. */
	private static final int PER_ROW = 12;

	private static final Color BACKGROUND = new Color(0x14, 0x16, 0x1A);
	private static final Color ROW_FILL = new Color(0x1E, 0x21, 0x26);
	private static final Color GRID = new Color(0x2C, 0x31, 0x38);
	/** Tier labels are dark text on the tier's own colour, as in a tier maker. */
	private static final Color LABEL_TEXT = new Color(0x11, 0x11, 0x11);

	/** How long a fetched face is reused. Skins change rarely. */
	private static final long FACE_TTL_MILLIS = 60 * 60 * 1000L;

	private record CachedFace(BufferedImage image, long atMillis) {
	}

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private final Map<UUID, CachedFace> faces = new HashMap<>();

	/**
	 * Render the given tiers to a PNG.
	 *
	 * @param rows tiers in display order, each with the players on it; a tier
	 *     with nobody on it is still drawn, so the ladder reads as a whole
	 * @return PNG bytes, or null if the image could not be produced
	 */
	public byte[] render(List<Map.Entry<Grade, List<GradeStore.Record>>> rows) {
		if (rows.isEmpty()) {
			return null;
		}

		// Fetch every face first, in parallel: doing it inline while drawing
		// would serialise a few dozen round trips into one long wait.
		List<GradeStore.Record> everyone = new ArrayList<>();
		for (Map.Entry<Grade, List<GradeStore.Record>> row : rows) {
			everyone.addAll(row.getValue());
		}
		Map<UUID, BufferedImage> resolved = fetchAll(everyone);

		int[] heights = new int[rows.size()];
		int total = PADDING;
		for (int i = 0; i < rows.size(); i++) {
			int players = rows.get(i).getValue().size();
			int lines = Math.max(1, (players + PER_ROW - 1) / PER_ROW);
			heights[i] = lines * FACE + (lines + 1) * GAP;
			total += heights[i];
		}
		int width = PADDING * 2 + LABEL_WIDTH + PER_ROW * (FACE + GAP) + GAP;
		int height = total + PADDING;

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		// Faces are 64x64 pixel art scaled to the same size, so nearest
		// neighbour keeps them crisp rather than smearing them.
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

		g.setColor(BACKGROUND);
		g.fillRect(0, 0, width, height);

		int y = PADDING;
		for (int i = 0; i < rows.size(); i++) {
			drawRow(g, rows.get(i).getKey(), rows.get(i).getValue(), resolved,
					PADDING, y, width - PADDING * 2, heights[i]);
			y += heights[i];
		}
		g.dispose();

		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		} catch (IOException e) {
			LOG.error("could not encode the tierlist image", e);
			return null;
		}
	}

	private void drawRow(Graphics2D g, Grade tier, List<GradeStore.Record> players,
			Map<UUID, BufferedImage> faces, int x, int y, int width, int height) {
		// The label block, in the tier's own colour.
		g.setColor(new Color(tier.color()));
		g.fillRect(x, y, LABEL_WIDTH, height);

		g.setColor(ROW_FILL);
		g.fillRect(x + LABEL_WIDTH, y, width - LABEL_WIDTH, height);

		g.setColor(GRID);
		g.drawRect(x, y, width - 1, height - 1);
		g.drawLine(x + LABEL_WIDTH, y, x + LABEL_WIDTH, y + height - 1);

		// The tier's name, centred in its block.
		g.setColor(LABEL_TEXT);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
		FontMetrics metrics = g.getFontMetrics();
		String label = tier.label();
		g.drawString(label,
				x + (LABEL_WIDTH - metrics.stringWidth(label)) / 2,
				y + (height - metrics.getHeight()) / 2 + metrics.getAscent());

		int faceX = x + LABEL_WIDTH + GAP;
		int faceY = y + GAP;
		int column = 0;
		for (GradeStore.Record player : players) {
			if (column == PER_ROW) {
				column = 0;
				faceX = x + LABEL_WIDTH + GAP;
				faceY += FACE + GAP;
			}
			BufferedImage face = faces.get(player.uuid());
			if (face != null) {
				g.drawImage(face, faceX, faceY, FACE, FACE, null);
			} else {
				// A face that would not load still gets a cell, so the row
				// never quietly drops a player because of someone's outage.
				g.setColor(GRID);
				g.fillRect(faceX, faceY, FACE, FACE);
			}
			faceX += FACE + GAP;
			column++;
		}
	}

	/** Fetch every player's face at once, reusing anything already cached. */
	private Map<UUID, BufferedImage> fetchAll(List<GradeStore.Record> players) {
		Map<UUID, CompletableFuture<BufferedImage>> pending = new HashMap<>();
		long now = System.currentTimeMillis();

		for (GradeStore.Record player : players) {
			UUID id = player.uuid();
			if (pending.containsKey(id)) {
				continue;
			}
			synchronized (faces) {
				CachedFace hit = faces.get(id);
				if (hit != null && now - hit.atMillis() < FACE_TTL_MILLIS) {
					pending.put(id, CompletableFuture.completedFuture(hit.image()));
					continue;
				}
			}
			pending.put(id, CompletableFuture.supplyAsync(() -> face(id)));
		}

		Map<UUID, BufferedImage> out = new HashMap<>();
		for (Map.Entry<UUID, CompletableFuture<BufferedImage>> entry : pending.entrySet()) {
			try {
				BufferedImage image = entry.getValue().get();
				if (image != null) {
					out.put(entry.getKey(), image);
					synchronized (faces) {
						faces.put(entry.getKey(), new CachedFace(image, now));
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				LOG.debug("could not load a face for {}", entry.getKey(), e);
			}
		}
		return out;
	}

	/** One player's face, or null if it could not be fetched. */
	private BufferedImage face(UUID id) {
		String url = "https://minotar.net/helm/" + id.toString().replace("-", "")
				+ "/" + FACE + ".png";
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.header("User-Agent", "DoorSMP-Backend/1.0")
					.timeout(Duration.ofSeconds(10))
					.GET()
					.build();
			HttpResponse<InputStream> response =
					http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				return null;
			}
			try (InputStream body = response.body()) {
				return ImageIO.read(body);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			LOG.debug("face fetch failed for {}", id, e);
			return null;
		}
	}
}
