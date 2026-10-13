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

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Finds the body of a placed part in a top camera image: a dark rectangle of about the expected
 * size near the expected location, as an IC body on a lighter board. Parts that do not look like
 * that, such as most passives, are not found rather than measured wrongly.
 *
 * Works in image pixels. Angles are counterclockwise in degrees with y up, as machine angles,
 * although image y grows downwards.
 */
public class BodyLocator {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    /**
     * How far the found size may differ from the expected one, as a fraction.
     */
    public static final double SIZE_TOLERANCE = 0.25;

    public static class Result {
        public final RotatedRect rect;
        /**
         * Center of the body in image pixels.
         */
        public final Point center;
        /**
         * Direction of the body's long side, counterclockwise from the image x axis, with y up.
         */
        public final double angle;
        /**
         * Found long and short side over the expected ones.
         */
        public final double lengthRatio;
        public final double widthRatio;

        Result(RotatedRect rect, double angle, double lengthRatio, double widthRatio) {
            this.rect = rect;
            this.center = rect.center;
            this.angle = angle;
            this.lengthRatio = lengthRatio;
            this.widthRatio = widthRatio;
        }
    }

    /**
     * @param gray 8 bit gray image.
     * @param expectedCenter where the body should be, in pixels.
     * @param length the expected long side of the body, in pixels.
     * @param width the expected short side of the body, in pixels.
     * @return the body, or null if no dark rectangle of about that size is near the expected
     *         location.
     */
    public static Result locate(Mat gray, Point expectedCenter, double length, double width) {
        // Search within one body length around the expected center.
        int reach = (int) Math.ceil(length);
        Rectangle roi = new Rectangle((int) Math.floor(expectedCenter.x - reach),
                (int) Math.floor(expectedCenter.y - reach), 2 * reach, 2 * reach)
                        .intersection(new Rectangle(0, 0, gray.cols(), gray.rows()));
        if (roi.isEmpty()) {
            return null;
        }
        Mat sub = gray.submat(roi.y, roi.y + roi.height, roi.x, roi.x + roi.width);
        Mat blurred = new Mat();
        Mat binary = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Imgproc.GaussianBlur(sub, blurred, new Size(5, 5), 0);
            // Dark body on a lighter board: Otsu picks the level between them.
            Imgproc.threshold(blurred, binary, 0, 255,
                    Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);
            // Thin dark traces may touch the body, opening cuts them off.
            int k = Math.max(3, (int) Math.round(width / 20)) | 1;
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN,
                    Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(k, k)));
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE);

            Result best = null;
            double bestScore = Double.MAX_VALUE;
            for (MatOfPoint contour : contours) {
                MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
                RotatedRect rect = Imgproc.minAreaRect(points);
                points.release();
                double a = Math.max(rect.size.width, rect.size.height);
                double b = Math.min(rect.size.width, rect.size.height);
                double lengthRatio = a / length;
                double widthRatio = b / width;
                if (Math.abs(lengthRatio - 1) > SIZE_TOLERANCE
                        || Math.abs(widthRatio - 1) > SIZE_TOLERANCE) {
                    continue;
                }
                Point center = new Point(rect.center.x + roi.x, rect.center.y + roi.y);
                double distance = Math.hypot(center.x - expectedCenter.x,
                        center.y - expectedCenter.y) / length;
                double score = Math.abs(lengthRatio - 1) + Math.abs(widthRatio - 1) + distance;
                if (score < bestScore) {
                    bestScore = score;
                    RotatedRect found = new RotatedRect(center, rect.size, rect.angle);
                    best = new Result(found, longSideAngle(found), lengthRatio, widthRatio);
                }
            }
            return best;
        }
        finally {
            for (MatOfPoint contour : contours) {
                contour.release();
            }
            sub.release();
            blurred.release();
            binary.release();
            hierarchy.release();
        }
    }

    /**
     * @return the direction of the long side of the rectangle, counterclockwise with y up, in
     *         (-90, 90].
     */
    static double longSideAngle(RotatedRect rect) {
        Point[] p = new Point[4];
        rect.points(p);
        double dx1 = p[1].x - p[0].x;
        double dy1 = p[1].y - p[0].y;
        double dx2 = p[2].x - p[1].x;
        double dy2 = p[2].y - p[1].y;
        boolean firstLonger = Math.hypot(dx1, dy1) >= Math.hypot(dx2, dy2);
        double dx = firstLonger ? dx1 : dx2;
        double dy = firstLonger ? dy1 : dy2;
        // Image y grows downwards.
        return normalizeHalfTurn(Math.toDegrees(Math.atan2(-dy, dx)));
    }

    /**
     * @return the angle in (-90, 90], since a rectangle looks the same turned by 180 degrees.
     */
    public static double normalizeHalfTurn(double angle) {
        angle = angle % 180;
        if (angle > 90) {
            angle -= 180;
        }
        else if (angle <= -90) {
            angle += 180;
        }
        return angle;
    }
}
