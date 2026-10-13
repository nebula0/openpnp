package org.openpnp.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public class BodyLocatorTest {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    /**
     * A light board with a dark body, rotated by angle (counterclockwise, y up).
     */
    private static Mat board(Point center, double length, double width, double angle) {
        Mat image = new Mat(600, 800, CvType.CV_8UC1, new Scalar(200));
        // A dark trace touching the body, which must not change the result.
        Imgproc.line(image, new Point(0, center.y), center, new Scalar(30), 3);
        // OpenCV angles are clockwise in image coordinates.
        Point[] p = new Point[4];
        new RotatedRect(center, new Size(length, width), -angle).points(p);
        Imgproc.fillConvexPoly(image, new MatOfPoint(p), new Scalar(40));
        return image;
    }

    @Test
    public void findsShiftedAndRotatedBody() {
        Mat image = board(new Point(420, 290), 300, 120, 3);
        BodyLocator.Result r = BodyLocator.locate(image, new Point(400, 300), 300, 120);
        assertNotNull(r);
        assertEquals(420, r.center.x, 1.5);
        assertEquals(290, r.center.y, 1.5);
        assertEquals(3, r.angle, 0.5);
    }

    @Test
    public void findsUprightBody() {
        Mat image = board(new Point(400, 300), 300, 120, 90);
        BodyLocator.Result r = BodyLocator.locate(image, new Point(400, 300), 300, 120);
        assertNotNull(r);
        assertEquals(90, Math.abs(r.angle), 0.5);
    }

    @Test
    public void ignoresWrongSize() {
        Mat image = board(new Point(400, 300), 150, 60, 0);
        assertNull(BodyLocator.locate(image, new Point(400, 300), 300, 120));
    }

    @Test
    public void normalizesHalfTurns() {
        assertEquals(10, BodyLocator.normalizeHalfTurn(190), 1e-9);
        assertEquals(90, BodyLocator.normalizeHalfTurn(-90), 1e-9);
        assertEquals(-80, BodyLocator.normalizeHalfTurn(100), 1e-9);
    }
}
