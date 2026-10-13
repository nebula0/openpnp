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
import java.util.Comparator;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Finds a cut tape strip in one top camera image: the sprocket holes, the line through them, the
 * parts in the pockets, and from those the reference and last hole as a ReferenceStripFeeder
 * wants them.
 *
 * ReferenceStripFeeder expects the parts to the left of the line from the reference hole to the
 * last hole, and the first part 2 mm (half a hole pitch) before the reference hole, i.e. on the
 * side away from the last hole. The feed direction therefore follows from the side the parts
 * are on.
 *
 * All locations are machine millimeters, with y up.
 */
public class StripTapeLocator {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    public static final double HOLE_DIAMETER = 1.5;
    public static final double HOLE_PITCH = 4;
    public static final double REFERENCE_HOLE_TO_PART_LINEAR = 2;
    /**
     * How far measured holes may be off the ideal line and pitch.
     */
    public static final double TOLERANCE = 0.3;

    public static class Result {
        public final List<Point> holes = new ArrayList<>();
        public final List<Point> parts = new ArrayList<>();
        /**
         * Unit vector of the feed direction.
         */
        public Point direction;
        public Point referenceHole;
        public Point lastHole;
        public Point firstPart;
        /**
         * Part pitch from the parts found, or the hole pitch if fewer than two were found.
         */
        public double partPitch;
        /**
         * Largest distance between a part found and where the feeder would expect one.
         */
        public double maxPartResidual;
    }

    /**
     * Converts image pixels to machine millimeters.
     */
    public interface PixelToMachine {
        Point toMachine(double px, double py);
    }

    /**
     * @param gray 8 bit gray image.
     * @param tapeWidth in mm, 8 for 8 mm tape.
     * @param mmPerPixel the camera scale at the tape surface.
     * @throws Exception if no strip can be made out, with the reason.
     */
    public static Result locate(Mat gray, double tapeWidth, double mmPerPixel,
            PixelToMachine toMachine) throws Exception {
        Result r = new Result();
        List<Point> holes = findHoles(gray, mmPerPixel, toMachine);
        List<Point> line = bestLine(holes);
        if (line.size() < 2) {
            throw new Exception("Found " + holes.size() + " hole candidates but not two "
                    + HOLE_PITCH + " mm apart in a line. Make sure at least two sprocket holes "
                    + "are in view.");
        }
        r.holes.addAll(line);
        Point u = unit(line.get(0), line.get(line.size() - 1));
        Point origin = line.get(0);
        double lateral = tapeWidth / 2 - 0.5;

        // Parts: anything not white on the paper, about the lateral distance off the hole line.
        for (Point p : findBlobs(gray, mmPerPixel, toMachine)) {
            double across = cross(u, sub(p, origin));
            if (Math.abs(Math.abs(across) - lateral) < 0.6) {
                r.parts.add(p);
            }
        }
        if (r.parts.isEmpty()) {
            throw new Exception("Found the holes but no parts " + lateral
                    + " mm beside them. Make sure at least one part is in view.");
        }
        // The side of the parts, all parts must agree.
        double side = Math.signum(cross(u, sub(r.parts.get(0), origin)));
        for (Point p : r.parts) {
            if (Math.signum(cross(u, sub(p, origin))) != side) {
                throw new Exception("Parts were found on both sides of the hole line.");
            }
        }
        // The parts must be on the left of the feed direction d, left(d) = (-d.y, d.x), so
        // cross(d, part) > 0.
        r.direction = side > 0 ? u : new Point(-u.x, -u.y);
        Point d = r.direction;
        Point left = new Point(-d.y, d.x);

        r.parts.sort(Comparator.comparingDouble(p -> dot(d, p)));
        r.holes.sort(Comparator.comparingDouble(p -> dot(d, p)));
        r.firstPart = r.parts.get(0);
        // The first part is half a pitch before its reference hole.
        Point expected = add(sub(r.firstPart, scale(left, lateral)),
                scale(d, REFERENCE_HOLE_TO_PART_LINEAR));
        r.referenceHole = nearest(r.holes, expected);
        if (distance(r.referenceHole, expected) > TOLERANCE * 2) {
            throw new Exception(String.format(
                    "No hole next to the first part, expected one at (%.3f, %.3f).", expected.x,
                    expected.y));
        }
        r.lastHole = r.holes.get(r.holes.size() - 1);
        if (dot(d, sub(r.lastHole, r.referenceHole)) < HOLE_PITCH / 2) {
            throw new Exception("The first part is at the far end of the holes in view. Move the "
                    + "camera so that at least one more hole beyond the first part is visible.");
        }

        r.partPitch = HOLE_PITCH;
        if (r.parts.size() >= 2) {
            double step = Double.MAX_VALUE;
            for (int i = 1; i < r.parts.size(); i++) {
                step = Math.min(step, dot(d, sub(r.parts.get(i), r.parts.get(i - 1))));
            }
            // Standard part pitches are multiples of 2 mm.
            r.partPitch = Math.max(2, Math.round(step / 2) * 2);
        }
        // How well do the parts fit where the feeder would put them?
        r.maxPartResidual = 0;
        for (Point p : r.parts) {
            double along = dot(d, sub(p, r.firstPart));
            double n = Math.round(along / r.partPitch);
            Point ideal = add(r.firstPart, scale(d, n * r.partPitch));
            r.maxPartResidual = Math.max(r.maxPartResidual, distance(p, ideal));
        }
        return r;
    }

    /**
     * Dark round blobs of about the hole diameter. Several thresholds are tried, since the
     * surroundings of the tape may be dark too, and the most holes found wins.
     */
    static List<Point> findHoles(Mat gray, double mmPerPixel, PixelToMachine toMachine) {
        List<Point> best = new ArrayList<>();
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(gray, blurred, new Size(9, 9), 0);
        for (int threshold : new int[] {40, 60, 80, 100, 130}) {
            List<Point> found = new ArrayList<>();
            Mat binary = new Mat();
            Imgproc.threshold(blurred, binary, threshold, 255, Imgproc.THRESH_BINARY_INV);
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE);
            for (MatOfPoint contour : contours) {
                MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
                Point center = new Point();
                float[] radius = new float[1];
                Imgproc.minEnclosingCircle(points, center, radius);
                points.release();
                double diameter = 2 * radius[0] * mmPerPixel;
                double roundness =
                        Imgproc.contourArea(contour) / (Math.PI * radius[0] * radius[0]);
                if (Math.abs(diameter - HOLE_DIAMETER) < 0.35 && roundness > 0.75) {
                    found.add(toMachine.toMachine(center.x, center.y));
                }
                contour.release();
            }
            binary.release();
            hierarchy.release();
            if (bestLine(found).size() > bestLine(best).size()) {
                best = found;
            }
        }
        blurred.release();
        return best;
    }

    /**
     * Blobs clearly darker than the paper, of a size between a 0201 and a large IC. The paper
     * level is taken from the brightest part of the image, since the light varies.
     */
    static List<Point> findBlobs(Mat gray, double mmPerPixel, PixelToMachine toMachine) {
        List<Point> found = new ArrayList<>();
        Mat binary = new Mat();
        double paper = paperLevel(gray);
        double threshold = 0.75 * paper;
        Imgproc.threshold(gray, binary, threshold, 255, Imgproc.THRESH_BINARY_INV);
        // Join the dark outline, light terminals and body of a part into one blob.
        int k = Math.max(3, (int) Math.round(0.25 / mmPerPixel)) | 1;
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(k, k)));
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        // Not only the outer contours: the dark surroundings of the tape may enclose the whole
        // image, with the parts inside the paper as holes in it.
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE);
        List<RotatedRect> rects = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
            RotatedRect rect = Imgproc.minAreaRect(points);
            points.release();
            double length = Math.max(rect.size.width, rect.size.height) * mmPerPixel;
            double width = Math.min(rect.size.width, rect.size.height) * mmPerPixel;
            // A part lies on the paper, so a frame around it must be about as light as paper.
            // That rules out the texture of the dark surroundings.
            if (length > 0.4 && length < 6 && width > 0.2
                    && frameLevel(gray, Imgproc.boundingRect(contour),
                            (int) Math.ceil(0.3 / mmPerPixel)) > 0.85 * paper) {
                rects.add(rect);
            }
            contour.release();
        }
        binary.release();
        hierarchy.release();
        // A part also gives contours inside it, e.g. around its terminals. Keep the largest.
        rects.sort((a, b) -> Double.compare(b.size.area(), a.size.area()));
        List<RotatedRect> kept = new ArrayList<>();
        for (RotatedRect rect : rects) {
            boolean inside = false;
            for (RotatedRect other : kept) {
                if (distance(rect.center, other.center) < Math.max(other.size.width,
                        other.size.height) / 2) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                kept.add(rect);
                found.add(toMachine.toMachine(rect.center.x, rect.center.y));
            }
        }
        return found;
    }

    /**
     * @return the mean gray level of a frame of the given margin around the box, or 0 if it does
     *         not fit into the image.
     */
    static double frameLevel(Mat gray, org.opencv.core.Rect box, int margin) {
        org.opencv.core.Rect outer = new org.opencv.core.Rect(box.x - margin, box.y - margin,
                box.width + 2 * margin, box.height + 2 * margin);
        if (outer.x < 0 || outer.y < 0 || outer.x + outer.width > gray.cols()
                || outer.y + outer.height > gray.rows()) {
            return 0;
        }
        double outerSum = Core.sumElems(gray.submat(outer)).val[0];
        double innerSum = Core.sumElems(gray.submat(box)).val[0];
        return (outerSum - innerSum) / (outer.area() - box.area());
    }

    /**
     * @return the gray level of the paper tape, as the level only 15% of the image is brighter
     *         than; the tape covers more than that of an image framing it.
     */
    static double paperLevel(Mat gray) {
        byte[] data = new byte[(int) gray.total()];
        Mat continuous = gray.isContinuous() ? gray : gray.clone();
        continuous.get(0, 0, data);
        int[] histogram = new int[256];
        for (byte b : data) {
            histogram[b & 0xff]++;
        }
        long brighter = 0;
        for (int level = 255; level >= 0; level--) {
            brighter += histogram[level];
            if (brighter >= 0.15 * data.length) {
                return level;
            }
        }
        return 255;
    }

    /**
     * @return the largest set of points on a line with spacings that are multiples of the hole
     *         pitch, sorted along the line.
     */
    static List<Point> bestLine(List<Point> points) {
        List<Point> best = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                Point a = points.get(i);
                Point b = points.get(j);
                double length = distance(a, b);
                double pitches = Math.round(length / HOLE_PITCH);
                if (pitches < 1 || Math.abs(length - pitches * HOLE_PITCH) > TOLERANCE) {
                    continue;
                }
                Point u = unit(a, b);
                List<Point> line = new ArrayList<>();
                for (Point p : points) {
                    Point ap = sub(p, a);
                    double along = dot(u, ap);
                    double off = Math.abs(cross(u, ap));
                    double n = Math.round(along / HOLE_PITCH);
                    if (off < TOLERANCE && Math.abs(along - n * HOLE_PITCH) < TOLERANCE) {
                        line.add(p);
                    }
                }
                if (line.size() > best.size()) {
                    line.sort(Comparator.comparingDouble(p -> dot(u, sub(p, a))));
                    best = line;
                }
            }
        }
        return best;
    }

    static Point nearest(List<Point> points, Point to) {
        Point best = null;
        for (Point p : points) {
            if (best == null || distance(p, to) < distance(best, to)) {
                best = p;
            }
        }
        return best;
    }

    static Point unit(Point a, Point b) {
        double l = distance(a, b);
        return new Point((b.x - a.x) / l, (b.y - a.y) / l);
    }

    static Point add(Point a, Point b) {
        return new Point(a.x + b.x, a.y + b.y);
    }

    static Point sub(Point a, Point b) {
        return new Point(a.x - b.x, a.y - b.y);
    }

    static Point scale(Point a, double s) {
        return new Point(a.x * s, a.y * s);
    }

    static double dot(Point a, Point b) {
        return a.x * b.x + a.y * b.y;
    }

    static double cross(Point a, Point b) {
        return a.x * b.y - a.y * b.x;
    }

    static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }
}
