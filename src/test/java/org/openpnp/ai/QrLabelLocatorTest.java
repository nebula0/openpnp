package org.openpnp.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.QRCodeEncoder;

public class QrLabelLocatorTest {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    /**
     * A dark tape holder with white labels, each with a QR code of about 800 pixels, as an 8 mm
     * code at 0.01 mm per pixel, blurred as the camera is focused on the board below.
     */
    private static Mat holder(String[] texts, int[] centersX, int centerY, int module) {
        Mat image = new Mat(1083, 1923, CvType.CV_8UC1, new Scalar(25));
        for (int i = 0; i < texts.length; i++) {
            Mat code = new Mat();
            QRCodeEncoder.create()
                         .encode(texts[i], code);
            Mat big = new Mat();
            Imgproc.resize(code, big, new Size(), module, module, Imgproc.INTER_NEAREST);
            int x = centersX[i] - big.cols() / 2;
            int y = centerY - big.rows() / 2;
            Imgproc.rectangle(image, new Point(x - 2 * module, y - 2 * module),
                    new Point(x + big.cols() + 2 * module, y + big.rows() + 2 * module),
                    new Scalar(230), -1);
            big.copyTo(image.submat(new Rect(x, y, big.cols(), big.rows())));
        }
        Imgproc.GaussianBlur(image, image, new Size(0, 0), 6);
        return image;
    }

    @Test
    public void readsOneLabel() {
        Mat image = holder(new String[] {"C0603-1uF"}, new int[] {980}, 540, 25);
        List<QrLabelLocator.Label> labels = QrLabelLocator.find(image);
        assertEquals(1, labels.size());
        assertEquals("C0603-1uF", labels.get(0).text);
        assertEquals(980, labels.get(0).center.x, 6);
        assertEquals(540, labels.get(0).center.y, 6);
        assertEquals(0, labels.get(0).angle, 2);
    }

    @Test
    public void readsTwoLabels() {
        Mat image = holder(new String[] {"C0603-10nF", "C0603-100nF"}, new int[] {480, 1450},
                540, 20);
        List<QrLabelLocator.Label> labels = QrLabelLocator.find(image);
        labels.sort(Comparator.comparingDouble(l -> l.center.x));
        assertEquals(2, labels.size());
        assertEquals("C0603-10nF", labels.get(0).text);
        assertEquals("C0603-100nF", labels.get(1).text);
        assertEquals(480, labels.get(0).center.x, 6);
        assertEquals(1450, labels.get(1).center.x, 6);
    }

    /**
     * Labels of 2026-10-22: smaller codes stuck on turned by 90 degrees.
     */
    @Test
    public void readsTurnedSmallLabel() {
        Mat image = holder(new String[] {"C0603-1uF"}, new int[] {980}, 540, 15);
        Core.rotate(image, image, Core.ROTATE_90_COUNTERCLOCKWISE);
        Mat wide = new Mat(1083, 1923, CvType.CV_8UC1, new Scalar(25));
        image.submat(new Rect((image.cols() - 1083) / 2, (image.rows() - 1083) / 2, 1083, 1083))
             .copyTo(wide.submat(new Rect(420, 0, 1083, 1083)));
        List<QrLabelLocator.Label> labels = QrLabelLocator.find(wide);
        assertEquals(1, labels.size());
        assertEquals("C0603-1uF", labels.get(0).text);
        assertEquals(960, labels.get(0).center.x, 8);
        assertEquals(522, labels.get(0).center.y, 8);
        assertEquals(90, labels.get(0).angle, 3);
    }

    @Test
    public void findsNothingWithoutLabels() {
        Mat image = new Mat(1083, 1923, CvType.CV_8UC1, new Scalar(25));
        Imgproc.rectangle(image, new Point(700, 300), new Point(1200, 800), new Scalar(230), -1);
        assertTrue(QrLabelLocator.find(image)
                                 .isEmpty());
    }
}
