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
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Finds a placed part by comparing a top camera image taken before placing with one taken after,
 * both with the camera over the placement. Whatever changed near the expected location is the
 * part, whatever its color: a part on pads of the same brightness still changes the image at its
 * edges, its body and the pads it covers.
 *
 * The two images are aligned first, since the camera does not return to exactly the same spot,
 * then the difference is thresholded and cleaned of thin edge residues. Changes far from the
 * part are reported too, they may be a neighbor that was pushed away.
 *
 * Works in image pixels. Angles are counterclockwise in degrees with y up, as machine angles,
 * although image y grows downwards.
 */
public class PlacementDiff {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    /**
     * Gray level difference that counts as a change.
     */
    public static final double MIN_DIFFERENCE = 30;
    /**
     * Part of the body area that has to change for the part to count as found.
     */
    public static final double MIN_CHANGED_FRACTION = 0.2;

    public static class Result {
        /**
         * Shift of the after image against the before image, in pixels.
         */
        public final Point shift;
        /**
         * Changed pixels near the expected location.
         */
        public final double changedArea;
        /**
         * Changed pixels away from the part, e.g. a neighbor that moved.
         */
        public final double otherChangedArea;
        /**
         * Center of the changed area near the expected location, or null if too little changed.
         */
        public final Point center;
        /**
         * Direction of the long side of the changed area, counterclockwise from the image x axis
         * with y up, in (-90, 90].
         */
        public final double angle;
        public final double length;
        public final double width;
        public final RotatedRect rect;
        /**
         * The changed pixels (255), for drawing.
         */
        public final Mat mask;

        Result(Point shift, double changedArea, double otherChangedArea, RotatedRect rect,
                Mat mask) {
            this.shift = shift;
            this.changedArea = changedArea;
            this.otherChangedArea = otherChangedArea;
            this.rect = rect;
            this.mask = mask;
            if (rect == null) {
                center = null;
                angle = 0;
                length = 0;
                width = 0;
            }
            else {
                center = rect.center;
                // OpenCV angles turn clockwise in image coordinates.
                boolean wide = rect.size.width >= rect.size.height;
                length = wide ? rect.size.width : rect.size.height;
                width = wide ? rect.size.height : rect.size.width;
                angle = BodyLocator.normalizeHalfTurn(wide ? -rect.angle : -rect.angle - 90);
            }
        }

        public boolean isFound() {
            return rect != null;
        }
    }

    /**
     * @param before 8 bit gray image without the part.
     * @param after 8 bit gray image of the same size, with the part.
     * @param expectedCenter where the part should be, in pixels.
     * @param length the long side of the part body, in pixels.
     * @param width the short side of the part body, in pixels.
     * @param searchRadius how far off the part may be, in pixels.
     */
    public static Result measure(Mat before, Mat after, Point expectedCenter, double length,
            double width, double searchRadius) {
        List<Mat> temp = new ArrayList<>();
        try {
            Mat b = track(temp, new Mat());
            Mat a = track(temp, new Mat());
            before.convertTo(b, CvType.CV_32F);
            after.convertTo(a, CvType.CV_32F);

            // Align: the part is small against the frame, so the board dominates the shift.
            Mat window = track(temp, new Mat());
            Imgproc.createHanningWindow(window, a.size(), CvType.CV_32F);
            Point shift = Imgproc.phaseCorrelate(b, a, window);
            Mat m = track(temp, new Mat(2, 3, CvType.CV_64F));
            m.put(0, 0, 1, 0, shift.x, 0, 1, shift.y);
            Mat aligned = track(temp, new Mat());
            Imgproc.warpAffine(b, aligned, m, a.size(), Imgproc.INTER_LINEAR,
                    Core.BORDER_REPLICATE);

            double radius = Math.hypot(length, width) / 2 + searchRadius;
            Mat near = track(temp, Mat.zeros(a.size(), CvType.CV_8U));
            Imgproc.circle(near, expectedCenter, (int) Math.round(radius), new Scalar(255), -1);
            Mat outside = track(temp, new Mat());
            Core.bitwise_not(near, outside);

            // Match the brightness, in case the exposure changed a little. Only away from the
            // part, which changes the brightness near it by itself.
            MatOfDouble meanA = track(temp, new MatOfDouble());
            MatOfDouble stdA = track(temp, new MatOfDouble());
            MatOfDouble meanB = track(temp, new MatOfDouble());
            MatOfDouble stdB = track(temp, new MatOfDouble());
            Core.meanStdDev(a, meanA, stdA, outside);
            Core.meanStdDev(aligned, meanB, stdB, outside);
            double gain = stdB.get(0, 0)[0] > 1e-6 ? stdA.get(0, 0)[0] / stdB.get(0, 0)[0] : 1;
            Core.subtract(aligned, new Scalar(meanB.get(0, 0)[0]), aligned);
            Core.multiply(aligned, new Scalar(gain), aligned);
            Core.add(aligned, new Scalar(meanA.get(0, 0)[0]), aligned);

            Mat diff = track(temp, new Mat());
            Core.absdiff(a, aligned, diff);
            Imgproc.GaussianBlur(diff, diff, new Size(0, 0), 1.5);
            Mat mask = new Mat();
            Imgproc.threshold(diff, diff, MIN_DIFFERENCE, 255, Imgproc.THRESH_BINARY);
            diff.convertTo(mask, CvType.CV_8U);
            // Thin residues along strong edges remain where the alignment is not perfect.
            int open = Math.max(3, (int) Math.round(width * 0.08)) | 1;
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN,
                    Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(open, open)));
            // The border of the aligned image is made up.
            int margin = (int) Math.ceil(Math.max(Math.abs(shift.x), Math.abs(shift.y))) + 2;
            Imgproc.rectangle(mask, new Point(0, 0), new Point(mask.cols() - 1, mask.rows() - 1),
                    new Scalar(0), 2 * margin);

            Mat part = track(temp, new Mat());
            Core.bitwise_and(mask, near, part);
            Mat far = track(temp, Mat.zeros(mask.size(), CvType.CV_8U));
            Imgproc.circle(far, expectedCenter, (int) Math.round(radius + width), new Scalar(255),
                    -1);
            Core.bitwise_not(far, far);
            Core.bitwise_and(mask, far, far);

            double changed = Core.countNonZero(part);
            double other = Core.countNonZero(far);
            RotatedRect rect = null;
            if (changed >= MIN_CHANGED_FRACTION * length * width) {
                MatOfPoint points = new MatOfPoint();
                Core.findNonZero(part, points);
                MatOfPoint2f points2f = new MatOfPoint2f(points.toArray());
                rect = Imgproc.minAreaRect(points2f);
                points.release();
                points2f.release();
            }
            return new Result(shift, changed, other, rect, mask);
        }
        finally {
            for (Mat t : temp) {
                t.release();
            }
        }
    }

    private static <T extends Mat> T track(List<Mat> temp, T m) {
        temp.add(m);
        return m;
    }
}
