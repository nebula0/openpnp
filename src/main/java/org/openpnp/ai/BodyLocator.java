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

import org.opencv.core.Core;
import org.opencv.core.Core.MinMaxLocResult;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Finds the body of a placed part in a top camera image, as an IC body on a lighter board: a
 * rectangle of the expected size that is darker than a thin ring around it.
 *
 * The rectangle is slid over the image near the expected location, at a few angles around the
 * expected one, and the position with the largest contrast between the ring and the inside wins.
 * Averaging over the whole inside makes it robust against markings and reflections on the body,
 * which break up thresholded shapes.
 *
 * Works in image pixels. Angles are counterclockwise in degrees with y up, as machine angles,
 * although image y grows downwards.
 */
public class BodyLocator {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    /**
     * The smallest contrast, in gray levels, between the ring and the inside of a body.
     */
    public static final double MIN_CONTRAST = 25;
    public static final double ANGLE_RANGE = 5;
    public static final double ANGLE_STEP = 0.5;

    public static class Result {
        /**
         * Center of the body in image pixels.
         */
        public final Point center;
        /**
         * Direction of the body's long side, counterclockwise from the image x axis, with y up.
         */
        public final double angle;
        /**
         * Mean gray level of the ring around the body minus that of the body.
         */
        public final double contrast;
        public final RotatedRect rect;

        Result(Point center, double angle, double contrast, double length, double width) {
            this.center = center;
            this.angle = angle;
            this.contrast = contrast;
            // OpenCV angles turn clockwise in image coordinates.
            this.rect = new RotatedRect(center, new Size(length, width), -angle);
        }
    }

    /**
     * @param gray 8 bit gray image.
     * @param expectedCenter where the body should be, in pixels.
     * @param expectedAngle the expected direction of the body's long side, in degrees.
     * @param length the long side of the body, in pixels.
     * @param width the short side of the body, in pixels.
     * @return the body, or null if nothing near the expected location looks like it.
     */
    public static Result locate(Mat gray, Point expectedCenter, double expectedAngle,
            double length, double width) {
        int l = (int) Math.round(length);
        int w = (int) Math.round(width);
        int ring = Math.max(3, (int) Math.round(width * 0.15));
        // The body may be off by up to its width in any direction.
        int half = (int) Math.ceil(length / 2 + width + ring);
        Rectangle roi = new Rectangle((int) Math.floor(expectedCenter.x - half),
                (int) Math.floor(expectedCenter.y - half), 2 * half, 2 * half)
                        .intersection(new Rectangle(0, 0, gray.cols(), gray.rows()));
        if (roi.width < l + 2 * ring + 2 || roi.height < w + 2 * ring + 2) {
            return null;
        }
        Mat sub = gray.submat(roi.y, roi.y + roi.height, roi.x, roi.x + roi.width);
        Mat image = new Mat();
        sub.convertTo(image, CvType.CV_32F);
        sub.release();
        Point roiCenter = new Point(roi.width / 2.0, roi.height / 2.0);
        double innerArea = (double) l * w;
        double outerArea = (double) (l + 2 * ring) * (w + 2 * ring);

        Result best = null;
        Mat rotation = null;
        Mat rotated = new Mat();
        Mat inner = new Mat();
        Mat outer = new Mat();
        Mat score = new Mat();
        try {
            for (double a = -ANGLE_RANGE; a <= ANGLE_RANGE + 1e-9; a += ANGLE_STEP) {
                double angle = expectedAngle + a;
                // Turn the image so that a body at this angle lies level.
                rotation = Imgproc.getRotationMatrix2D(roiCenter, -angle, 1.0);
                Imgproc.warpAffine(image, rotated, rotation, image.size(), Imgproc.INTER_LINEAR,
                        Core.BORDER_REPLICATE);
                Imgproc.boxFilter(rotated, inner, -1, new Size(l, w));
                Imgproc.boxFilter(rotated, outer, -1, new Size(l + 2 * ring, w + 2 * ring));
                // Mean of the ring = (sum of outer - sum of inner) / ring area.
                Core.multiply(outer, new Scalar(outerArea), outer);
                Core.multiply(inner, new Scalar(innerArea), score);
                Core.subtract(outer, score, outer);
                Core.multiply(outer, new Scalar(1 / (outerArea - innerArea)),
                        outer);
                Core.subtract(outer, inner, score);
                // Only where the whole ring lies inside the image, the border is made up.
                int mx = (l + 2 * ring) / 2 + 1;
                int my = (w + 2 * ring) / 2 + 1;
                if (score.cols() <= 2 * mx || score.rows() <= 2 * my) {
                    rotation.release();
                    continue;
                }
                Mat valid = score.submat(my, score.rows() - my, mx, score.cols() - mx);
                MinMaxLocResult m = Core.minMaxLoc(valid);
                valid.release();
                if (best == null || m.maxVal > best.contrast) {
                    // Turn the found position back into the original image.
                    Mat inverse = new Mat();
                    Imgproc.invertAffineTransform(rotation, inverse);
                    double x = m.maxLoc.x + mx;
                    double y = m.maxLoc.y + my;
                    double ox = inverse.get(0, 0)[0] * x + inverse.get(0, 1)[0] * y
                            + inverse.get(0, 2)[0];
                    double oy = inverse.get(1, 0)[0] * x + inverse.get(1, 1)[0] * y
                            + inverse.get(1, 2)[0];
                    inverse.release();
                    best = new Result(new Point(ox + roi.x, oy + roi.y), normalizeHalfTurn(angle),
                            m.maxVal, length, width);
                }
                rotation.release();
            }
        }
        finally {
            image.release();
            rotated.release();
            inner.release();
            outer.release();
            score.release();
        }
        return best == null || best.contrast < MIN_CONTRAST ? null : best;
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
