package org.openpnp.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public class PlacementDiffTest {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    private static final Point PADS = new Point(400, 300);

    /**
     * A dark board with traces, vias and two shiny pads for an upright chip at PADS.
     */
    private static Mat board() {
        Mat image = new Mat(600, 800, CvType.CV_8UC1, new Scalar(70));
        for (int i = 0; i < 8; i++) {
            Imgproc.line(image, new Point(0, 40 + 70 * i), new Point(800, 60 + 70 * i),
                    new Scalar(110), 6);
            Imgproc.circle(image, new Point(60 + 90 * i, 520), 12, new Scalar(20), -1);
        }
        pad(image, new Point(PADS.x, PADS.y - 75));
        pad(image, new Point(PADS.x, PADS.y + 75));
        // A neighbor, e.g. an IC pin, far from the chip.
        Imgproc.rectangle(image, new Point(620, 150), new Point(680, 330), new Scalar(230), -1);
        return image;
    }

    private static void pad(Mat image, Point center) {
        Imgproc.rectangle(image, new Point(center.x - 50, center.y - 45),
                new Point(center.x + 50, center.y + 45), new Scalar(250), -1);
    }

    /**
     * Draws a chip of 160 x 80 pixels: bright ends a little darker than the pads, a dark body
     * close to the board color.
     */
    private static void chip(Mat image, Point center, double angle) {
        fill(image, new RotatedRect(center, new Size(160, 80), -angle), 200);
        fill(image, new RotatedRect(center, new Size(100, 80), -angle), 80);
    }

    private static void fill(Mat image, RotatedRect rect, double gray) {
        Point[] p = new Point[4];
        // OpenCV angles are clockwise in image coordinates.
        rect.points(p);
        Imgproc.fillConvexPoly(image, new MatOfPoint(p), new Scalar(gray));
    }

    /**
     * The camera did not come back to the same spot: the whole image moved.
     */
    private static Mat shifted(Mat image, double dx, double dy) {
        Mat m = new Mat(2, 3, CvType.CV_64F);
        m.put(0, 0, 1, 0, dx, 0, 1, dy);
        Mat out = new Mat();
        Imgproc.warpAffine(image, out, m, image.size(), Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE);
        return out;
    }

    @Test
    public void findsPlacedChip() {
        Mat before = board();
        Mat after = board();
        // Placed a bit off and turned, upright like the pads.
        chip(after, new Point(PADS.x + 6, PADS.y - 4), 92);
        after = shifted(after, 3.4, -2.2);
        PlacementDiff.Result r = PlacementDiff.measure(before, after, PADS, 160, 80, 60);
        assertTrue(r.isFound());
        assertEquals(3.4, r.shift.x, 0.3);
        assertEquals(-2.2, r.shift.y, 0.3);
        // Measured in the after image, so including the camera shift.
        assertEquals(PADS.x + 6 + 3.4, r.center.x, 3);
        assertEquals(PADS.y - 4 - 2.2, r.center.y, 3);
        assertEquals(160, r.length, 12);
        assertEquals(80, r.width, 12);
        assertEquals(88, Math.abs(r.angle), 2.5);
        assertEquals(0, r.otherChangedArea, 1e-9);
    }

    @Test
    public void findsNothingWithoutPart() {
        Mat before = board();
        Mat after = shifted(board(), -2.6, 1.3);
        PlacementDiff.Result r = PlacementDiff.measure(before, after, PADS, 160, 80, 60);
        assertFalse(r.isFound());
    }

    @Test
    public void toleratesExposureChange() {
        Mat before = board();
        Mat after = board();
        chip(after, PADS, 90);
        after.convertTo(after, -1, 1.08, -6);
        PlacementDiff.Result r = PlacementDiff.measure(before, after, PADS, 160, 80, 60);
        assertTrue(r.isFound());
        assertEquals(PADS.x, r.center.x, 3);
        assertEquals(PADS.y, r.center.y, 3);
        assertEquals(0, r.otherChangedArea, 1e-9);
    }

    @Test
    public void reportsMovedNeighbor() {
        Mat before = board();
        Mat after = board();
        chip(after, PADS, 90);
        // The neighbor was pushed sideways by the nozzle.
        Imgproc.rectangle(after, new Point(620, 150), new Point(680, 330), new Scalar(70), -1);
        Imgproc.rectangle(after, new Point(650, 160), new Point(710, 340), new Scalar(230), -1);
        PlacementDiff.Result r = PlacementDiff.measure(before, after, PADS, 160, 80, 60);
        assertTrue(r.isFound());
        assertTrue(r.otherChangedArea > 1000);
    }
}
