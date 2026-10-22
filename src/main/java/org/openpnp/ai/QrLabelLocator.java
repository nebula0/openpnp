/*
 * Copyright (C) 2026 OpenPnP contributors
 *
 * This file is part of OpenPnP.
 *
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.ai;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.QRCodeDetector;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;

/**
 * Finds and reads the QR code labels in a top camera image, e.g. the labels at the start of cut
 * tape strips that name their part. The labels sit higher than the board the camera is focused
 * on, so they are blurred; QR codes still read, and better on a reduced image, which also makes
 * detection faster. OpenCV finds but cannot decode codes with smaller modules (about 6.5 mm
 * codes), ZXing reads those, so both are used.
 *
 * Works in image pixels. Angles are counterclockwise in degrees with y up, as machine angles,
 * although image y grows downwards.
 */
public class QrLabelLocator {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    /**
     * Widths the image is reduced to before reading, one after the other.
     */
    public static final int[] WORKING_WIDTHS = {1000, 700, 1400};

    /**
     * The same for ZXing, which reads the blurred 6.5 mm codes of 2026-10-22 between about 500
     * and 1000 pixels wide, not at the full 1923.
     */
    public static final int[] ZXING_WIDTHS = {800, 1000, 600};

    public static class Label {
        public final String text;
        /**
         * Center of the code in image pixels.
         */
        public final Point center;
        /**
         * Direction of the code's top edge, from its top left to its top right corner.
         */
        public final double angle;
        /**
         * Length of the code's top edge in pixels.
         */
        public final double size;

        Label(String text, Point[] corners) {
            this(text, corners, Math.hypot(corners[1].x - corners[0].x,
                    corners[1].y - corners[0].y));
        }

        /**
         * @param corners top left and top right first, then any points around the center.
         */
        Label(String text, Point[] corners, double size) {
            this.text = text;
            double x = 0;
            double y = 0;
            for (Point p : corners) {
                x += p.x;
                y += p.y;
            }
            center = new Point(x / corners.length, y / corners.length);
            angle = Math.toDegrees(
                    Math.atan2(-(corners[1].y - corners[0].y), corners[1].x - corners[0].x));
            this.size = size;
        }
    }

    /**
     * @param gray 8 bit gray image.
     * @return the labels that could be read, possibly none.
     */
    public static List<Label> find(Mat gray) {
        // The detector misses codes now and then at one size and finds them at another.
        List<Label> labels = new ArrayList<>();
        for (int width : WORKING_WIDTHS) {
            merge(labels, find(gray, width));
        }
        for (int width : ZXING_WIDTHS) {
            merge(labels, findZxing(gray, width));
        }
        return labels;
    }

    private static void merge(List<Label> labels, List<Label> more) {
        for (Label l : more) {
            if (labels.stream()
                      .noneMatch(o -> o.text.equals(l.text)
                              && Math.hypot(o.center.x - l.center.x,
                                      o.center.y - l.center.y) < l.size / 2)) {
                labels.add(l);
            }
        }
    }

    private static List<Label> findZxing(Mat gray, int width) {
        double scale = Math.min(1.0, (double) width / gray.cols());
        Mat small = new Mat();
        List<Label> labels = new ArrayList<>();
        try {
            Imgproc.resize(gray, small, new Size(), scale, scale, Imgproc.INTER_AREA);
            if (small.type() != CvType.CV_8UC1 || !small.isContinuous()) {
                return labels;
            }
            byte[] pixels = new byte[small.cols() * small.rows()];
            small.get(0, 0, pixels);
            // The Y plane of YUV is just the gray image.
            BinaryBitmap bitmap = new BinaryBitmap(
                    new HybridBinarizer(new PlanarYUVLuminanceSource(pixels, small.cols(),
                            small.rows(), 0, 0, small.cols(), small.rows(), false)));
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            Result[] results;
            try {
                results = new QRCodeMultiReader().decodeMultiple(bitmap, hints);
            }
            catch (NotFoundException e) {
                return labels;
            }
            for (Result r : results) {
                ResultPoint[] p = r.getResultPoints();
                if (r.getText() == null || r.getText()
                                            .isEmpty()
                        || p == null || p.length < 3) {
                    continue;
                }
                // The centers of the finder patterns: bottom left, top left, top right. The
                // center of the code is halfway between bottom left and top right.
                Point bottomLeft = new Point(p[0].getX() / scale, p[0].getY() / scale);
                Point topLeft = new Point(p[1].getX() / scale, p[1].getY() / scale);
                Point topRight = new Point(p[2].getX() / scale, p[2].getY() / scale);
                Point bottomRight = new Point(topRight.x + bottomLeft.x - topLeft.x,
                        topRight.y + bottomLeft.y - topLeft.y);
                // A finder center is 3.5 modules in from the edge: a little smaller than the
                // code, which is fine for telling codes apart.
                labels.add(new Label(r.getText(),
                        new Point[] {topLeft, topRight, bottomRight, bottomLeft},
                        Math.hypot(topRight.x - topLeft.x, topRight.y - topLeft.y)));
            }
            return labels;
        }
        finally {
            small.release();
        }
    }

    private static List<Label> find(Mat gray, int width) {
        double scale = Math.min(1.0, (double) width / gray.cols());
        Mat small = new Mat();
        Mat points = new Mat();
        List<Label> labels = new ArrayList<>();
        try {
            Imgproc.resize(gray, small, new Size(), scale, scale, Imgproc.INTER_AREA);
            QRCodeDetector detector = new QRCodeDetector();
            List<String> texts = new ArrayList<>();
            if (detector.detectAndDecodeMulti(small, texts, points) && points.rows() > 0) {
                for (int i = 0; i < texts.size() && i < points.rows(); i++) {
                    add(labels, texts.get(i), points, i, scale);
                }
            }
            if (labels.isEmpty()) {
                // The multi detector misses a single code now and then.
                String text = detector.detectAndDecode(small, points);
                if (text != null && points.rows() > 0) {
                    add(labels, text, points, 0, scale);
                }
            }
            return labels;
        }
        finally {
            small.release();
            points.release();
        }
    }

    private static void add(List<Label> labels, String text, Mat points, int row,
            double scale) {
        if (text == null || text.isEmpty()) {
            // Found but not readable.
            return;
        }
        Point[] corners = new Point[4];
        for (int k = 0; k < 4; k++) {
            // The points come as one row per code with four 2 channel columns, or as one
            // column of eight values, depending on the call.
            double[] p = points.cols() >= 4 ? points.get(row, k)
                    : new double[] {points.get(row * 4 + k, 0)[0],
                            points.get(row * 4 + k, 0)[1]};
            corners[k] = new Point(p[0] / scale, p[1] / scale);
        }
        labels.add(new Label(text, corners));
    }
}
