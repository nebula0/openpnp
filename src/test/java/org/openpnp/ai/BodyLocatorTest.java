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
     * A light board with a dark trace.
     */
    private static Mat board() {
        Mat image = new Mat(600, 800, CvType.CV_8UC1, new Scalar(200));
        Imgproc.line(image, new Point(0, 300), new Point(800, 320), new Scalar(30), 3);
        return image;
    }

    /**
     * Draws a dark body, rotated by angle (counterclockwise, y up), with bright markings on it.
     */
    private static void body(Mat image, Point center, double length, double width,
            double angle) {
        Point[] p = new Point[4];
        // OpenCV angles are clockwise in image coordinates.
        new RotatedRect(center, new Size(length, width), -angle).points(p);
        Imgproc.fillConvexPoly(image, new MatOfPoint(p), new Scalar(60));
        Imgproc.putText(image, "AD608", new Point(center.x - 60, center.y + 10),
                Imgproc.FONT_HERSHEY_SIMPLEX, 1.2, new Scalar(180), 3);
    }

    @Test
    public void findsShiftedAndRotatedBody() {
        Mat image = board();
        body(image, new Point(420, 290), 300, 120, 3);
        BodyLocator.Result r =
                BodyLocator.locate(image, new Point(400, 300), 0, 300, 120);
        assertNotNull(r);
        assertEquals(420, r.center.x, 2);
        assertEquals(290, r.center.y, 2);
        assertEquals(3, r.angle, 0.6);
    }

    @Test
    public void findsUprightBody() {
        Mat image = board();
        body(image, new Point(390, 310), 300, 120, 89);
        BodyLocator.Result r =
                BodyLocator.locate(image, new Point(400, 300), 90, 300, 120);
        assertNotNull(r);
        assertEquals(390, r.center.x, 2);
        assertEquals(310, r.center.y, 2);
        assertEquals(89, r.angle, 0.6);
    }

    @Test
    public void findsNothingWithoutBody() {
        assertNull(BodyLocator.locate(board(), new Point(400, 300), 0, 300, 120));
    }

    @Test
    public void normalizesHalfTurns() {
        assertEquals(10, BodyLocator.normalizeHalfTurn(190), 1e-9);
        assertEquals(90, BodyLocator.normalizeHalfTurn(-90), 1e-9);
        assertEquals(-80, BodyLocator.normalizeHalfTurn(100), 1e-9);
    }
}
