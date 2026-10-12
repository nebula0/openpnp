package org.openpnp.bedmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;

import org.junit.jupiter.api.Test;

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
}
