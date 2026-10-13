package org.openpnp.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.file.Files;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.openpnp.machine.reference.feeder.ReferenceStripFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

public class StripTapeLocatorTest {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    private static final double MM_PER_PIXEL = 0.01;

    /**
     * ReferenceStripFeeder needs a configuration.
     */
    @BeforeAll
    public static void loadConfiguration() throws Exception {
        File directory = new File(Files.createTempDirectory("strip")
                                       .toFile(),
                ".openpnp");
        for (String name : new String[] {"machine.xml", "packages.xml", "parts.xml"}) {
            FileUtils.copyURLToFile(
                    ClassLoader.getSystemResource("config/BasicJobTest/" + name),
                    new File(directory, name));
        }
        Configuration.initialize(directory);
        Configuration.get()
                     .load();
    }

    /**
     * Image pixel to machine mm, with y up.
     */
    private static Point toMachine(double px, double py) {
        return new Point(px * MM_PER_PIXEL, -py * MM_PER_PIXEL);
    }

    private static Point toPixel(double x, double y) {
        return new Point(x / MM_PER_PIXEL, -y / MM_PER_PIXEL);
    }

    /**
     * Draws an 8 mm tape on dark ground: holes along the line from start in direction u (unit,
     * machine coordinates), parts on the given side of it.
     */
    private static Mat tape(Point start, Point u, int holes, int parts, double side) {
        Mat image = new Mat(1100, 1900, CvType.CV_8UC1, new Scalar(60));
        Point n = new Point(-u.y * side, u.x * side);
        // The paper, from 1.75 mm outside the holes to 6.25 mm on the parts side.
        Point a = new Point(start.x - u.x * 10 - n.x * 1.75, start.y - u.y * 10 - n.y * 1.75);
        Point b = new Point(start.x + u.x * 30 - n.x * 1.75, start.y + u.y * 30 - n.y * 1.75);
        Point c = new Point(b.x + n.x * 8, b.y + n.y * 8);
        Point d = new Point(a.x + n.x * 8, a.y + n.y * 8);
        Imgproc.fillConvexPoly(image, new MatOfPoint(toPixel(a.x, a.y), toPixel(b.x, b.y),
                toPixel(c.x, c.y), toPixel(d.x, d.y)), new Scalar(250));
        for (int i = 0; i < holes; i++) {
            Imgproc.circle(image, toPixel(start.x + u.x * 4 * i, start.y + u.y * 4 * i), 75,
                    new Scalar(20), -1);
        }
        double angle = Math.toDegrees(Math.atan2(u.y, u.x));
        for (int i = 0; i < parts; i++) {
            double along = 2 + 4 * i;
            Point center = toPixel(start.x + u.x * along + n.x * 3.5,
                    start.y + u.y * along + n.y * 3.5);
            Point[] p = new Point[4];
            new RotatedRect(center, new Size(80, 160), -angle).points(p);
            Imgproc.fillConvexPoly(image, new MatOfPoint(p), new Scalar(130));
        }
        return image;
    }

    /**
     * The real feeder must pick where the parts are.
     */
    private static void assertFeederPicksParts(StripTapeLocator.Result r) throws Exception {
        ReferenceStripFeeder feeder = new ReferenceStripFeeder();
        feeder.setReferenceHoleLocation(
                new Location(LengthUnit.Millimeters, r.referenceHole.x, r.referenceHole.y, 0, 0));
        feeder.setLastHoleLocation(
                new Location(LengthUnit.Millimeters, r.lastHole.x, r.lastHole.y, 0, 0));
        feeder.setPartPitch(new Length(r.partPitch, LengthUnit.Millimeters));
        for (int i = 0; i < r.parts.size(); i++) {
            feeder.setFeedCount(i + 1);
            Location pick = feeder.getPickLocation()
                                  .convertToUnits(LengthUnit.Millimeters);
            assertEquals(r.parts.get(i).x, pick.getX(), 0.05, "part " + (i + 1) + " x");
            assertEquals(r.parts.get(i).y, pick.getY(), 0.05, "part " + (i + 1) + " y");
        }
    }

    @Test
    public void verticalTapeWithPartsOnPlusX() throws Exception {
        // Like the real strip: holes going down the image, parts to the right (+x).
        Mat image = tape(new Point(4, -2), new Point(0, -1), 3, 2, 1);
        StripTapeLocator.Result r =
                StripTapeLocator.locate(image, 8, MM_PER_PIXEL, StripTapeLocatorTest::toMachine);
        assertEquals(3, r.holes.size());
        assertEquals(2, r.parts.size());
        assertEquals(4, r.partPitch, 1e-9);
        // Parts on +x means feeding towards -y.
        assertEquals(-1, r.direction.y, 1e-3);
        assertFeederPicksParts(r);
    }

    @Test
    public void tiltedTapeWithPartsOnTheOtherSide() throws Exception {
        double a = Math.toRadians(10);
        Mat image = tape(new Point(3, -4), new Point(Math.cos(a), Math.sin(a)), 3, 2, -1);
        StripTapeLocator.Result r =
                StripTapeLocator.locate(image, 8, MM_PER_PIXEL, StripTapeLocatorTest::toMachine);
        assertFeederPicksParts(r);
    }

    @Test
    public void needsHoles() {
        Mat image = new Mat(1100, 1900, CvType.CV_8UC1, new Scalar(250));
        assertThrows(Exception.class, () -> StripTapeLocator.locate(image, 8, MM_PER_PIXEL,
                StripTapeLocatorTest::toMachine));
    }
}
