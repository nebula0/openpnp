package org.openpnp.bedmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

public class BedMapTest {
    @Test
    public void centersCoverRangeWithOverlap() {
        double fov = 30;
        double step = fov * (1 - BedMapScanner.DEFAULT_OVERLAP);
        double[] c = BedMapScanner.centers(0, 400, fov, step, -1000, 1000);
        // Frames reach both ends.
        assertEquals(15, c[0], 1e-9);
        assertEquals(385, c[c.length - 1], 1e-9);
        // Neighbours are no further apart than the step, so frames overlap.
        for (int i = 1; i < c.length; i++) {
            assertTrue(c[i] - c[i - 1] <= step + 1e-9);
        }
    }

    @Test
    public void centersStayReachable() {
        double[] c = BedMapScanner.centers(-50, 50, 30, 24, 0, 20);
        for (double v : c) {
            assertTrue(v >= 0 && v <= 20);
        }
    }

    @Test
    public void narrowRegionGivesOneFrame() {
        double[] c = BedMapScanner.centers(10, 20, 30, 24, -1000, 1000);
        assertEquals(1, c.length);
        assertEquals(15, c[0], 1e-9);
    }

    @Test
    public void ownedAreasTileTheRegion() {
        double[] c = BedMapScanner.centers(0, 100, 30, 24, -1000, 1000);
        double[] e = BedMapScanner.edges(c, 0, 100);
        assertEquals(0, e[0], 1e-9);
        assertEquals(100, e[e.length - 1], 1e-9);
        for (int i = 0; i < c.length; i++) {
            // Each frame owns an area around its center that it fully sees.
            assertTrue(e[i] <= c[i] && c[i] <= e[i + 1]);
            assertTrue(c[i] - e[i] <= 15 + 1e-9 && e[i + 1] - c[i] <= 15 + 1e-9);
        }
    }

    @Test
    public void pixelAndMachineCoordinatesRoundTrip() {
        BedMap map = BedMap.create(new File("unused"), new Rectangle2D.Double(-10, 20, 100, 50), 0.1);
        assertEquals(1000, map.getInfo().width);
        assertEquals(500, map.getInfo().height);
        // Top left pixel is at the left edge and the top (max y) of the area.
        Point2D p = map.toPixel(-10, 70);
        assertEquals(0, p.getX(), 1e-9);
        assertEquals(0, p.getY(), 1e-9);
        // Machine y grows upwards, image y grows downwards.
        p = map.toPixel(0, 60);
        assertEquals(100, p.getX(), 1e-9);
        assertEquals(100, p.getY(), 1e-9);
        Point2D m = map.toMachine(p.getX(), p.getY());
        assertEquals(0, m.getX(), 1e-9);
        assertEquals(60, m.getY(), 1e-9);
        Rectangle r = map.toPixelRectangle(new Rectangle2D.Double(0, 50, 10, 10));
        assertEquals(new Rectangle(100, 100, 100, 100), r);
    }

    @Test
    public void renderTakesEachPixelFromTheNearestFrame() throws Exception {
        File directory = Files.createTempDirectory("bedmap")
                              .toFile();
        File tiles = new File(directory, BedMapScanner.TILES_DIRECTORY);
        tiles.mkdirs();
        // Two 10 x 10 mm frames at 0.1 mm per pixel, centered at x 5 and 13.
        List<BedMapScanner.TileRecord> records = new ArrayList<>();
        records.add(tile(tiles, "a.png", 5, 5, Color.red));
        records.add(tile(tiles, "b.png", 13, 5, Color.blue));
        Files.write(new File(tiles, BedMapScanner.TILES_INDEX_FILE).toPath(),
                new Gson().toJson(records)
                          .getBytes(StandardCharsets.UTF_8));

        // Finer than the frames, to check the coordinates rather than the resampling.
        BufferedImage image = BedMapScanner.render(directory,
                new Rectangle2D.Double(0, 0, 18, 10), 0.05, null, null);
        assertEquals(360, image.getWidth());
        assertEquals(200, image.getHeight());
        // The border between the frames is half way between their centers, at x 9.
        assertEquals(Color.red.getRGB(), image.getRGB((int) (8.9 / 0.05), 100));
        assertEquals(Color.blue.getRGB(), image.getRGB((int) (9.1 / 0.05), 100));
        assertEquals(Color.blue.getRGB(), image.getRGB((int) (17.9 / 0.05), 199));

        assertNull(BedMapScanner.render(directory, new Rectangle2D.Double(50, 50, 10, 10), 0.1,
                null, null));
    }

    private static BedMapScanner.TileRecord tile(File tiles, String file, double x, double y,
            Color color) throws Exception {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 100, 100);
        g.dispose();
        ImageIO.write(image, "png", new File(tiles, file));
        BedMapScanner.TileRecord r = new BedMapScanner.TileRecord();
        r.file = file;
        r.x = x;
        r.y = y;
        r.mmPerPixelX = 0.1;
        r.mmPerPixelY = 0.1;
        r.width = 100;
        r.height = 100;
        return r;
    }
}
