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

package org.openpnp.bedmap;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;

import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Locatable.LocationOption;
import org.openpnp.spi.Machine;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.pmw.tinylog.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Builds a {@link BedMap} by moving a down looking camera over a grid, capturing a frame at each
 * position and drawing it into the map at its known machine location. No image matching is
 * needed, since the machine knows where each frame was taken.
 *
 * Neighbouring frames overlap. Each map pixel is taken from the frame whose center is nearest,
 * so only the middle of each frame is used, where lens distortion and vignetting are lowest.
 *
 * The original frames are kept in the tiles subdirectory for full resolution views.
 *
 * All lengths are in millimeters.
 */
public class BedMapScanner {
    public static final double DEFAULT_OVERLAP = 0.2;
    public static final String TILES_DIRECTORY = "tiles";
    public static final String TILES_INDEX_FILE = "index.json";

    private static final long TILE_TIMEOUT_S = 60;
    /**
     * Stands in for "infinitely far" when asking the motion planner for the soft limits.
     */
    private static final double FAR_AWAY = 1e6;

    public interface Listener {
        /**
         * Called on the scanning thread after each frame was drawn into the map.
         */
        void tileDone(BedMap map, int done, int total);
    }

    /**
     * One camera position and the part of the map it is responsible for.
     */
    public static class Tile {
        public final double x;
        public final double y;
        public final Rectangle2D owned;

        Tile(double x, double y, Rectangle2D owned) {
            this.x = x;
            this.y = y;
            this.owned = owned;
        }
    }

    public static class Plan {
        public final Rectangle2D region;
        public final boolean full;
        public final int columns;
        public final int rows;
        public final List<Tile> tiles;

        Plan(Rectangle2D region, boolean full, int columns, int rows, List<Tile> tiles) {
            this.region = region;
            this.full = full;
            this.columns = columns;
            this.rows = rows;
            this.tiles = tiles;
        }
    }

    /**
     * An original frame as stored in the tiles directory.
     */
    public static class TileRecord {
        public String file;
        public double x;
        public double y;
        public double mmPerPixelX;
        public double mmPerPixelY;
        public int width;
        public int height;
        /**
         * The Z of the plane mmPerPixelX/Y refer to, or null for frames stored before this was
         * recorded.
         */
        public Double viewingPlaneZ;
        public Date captured;
    }

    private final Machine machine;
    private final Camera camera;
    private final File directory;
    private double mmPerPixel = BedMap.DEFAULT_MM_PER_PIXEL;
    private double overlap = DEFAULT_OVERLAP;

    public BedMapScanner(Machine machine, Camera camera, File directory) {
        this.machine = machine;
        this.camera = camera;
        this.directory = directory;
    }

    public static File getDefaultDirectory() {
        return new File(Configuration.get()
                                     .getConfigurationDirectory(),
                "bedmap");
    }

    public Camera getCamera() {
        return camera;
    }

    public File getDirectory() {
        return directory;
    }

    public void setMmPerPixel(double mmPerPixel) {
        this.mmPerPixel = mmPerPixel;
    }

    public void setOverlap(double overlap) {
        this.overlap = overlap;
    }

    /**
     * @return the camera scale in mm per pixel at the camera's default working plane.
     */
    public Location getUnitsPerPixel() {
        return camera.getUnitsPerPixel(camera.getDefaultZ())
                     .convertToUnits(LengthUnit.Millimeters);
    }

    /**
     * @return the size of one camera frame on the bed, in mm.
     */
    public Point2D getFieldOfView() {
        Location upp = getUnitsPerPixel();
        return new Point2D.Double(camera.getWidth() * upp.getX(),
                camera.getHeight() * upp.getY());
    }

    /**
     * @return the area the camera center can reach, from the X and Y soft limits, or null if the
     *         soft limits are not enabled.
     */
    public Rectangle2D getReachableArea() throws Exception {
        Location current = camera.getLocation();
        Location low = camera.getApproximativeLocation(current,
                current.derive(-FAR_AWAY, -FAR_AWAY, null, null), LocationOption.ApplySoftLimits)
                             .convertToUnits(LengthUnit.Millimeters);
        Location high = camera.getApproximativeLocation(current,
                current.derive(FAR_AWAY, FAR_AWAY, null, null), LocationOption.ApplySoftLimits)
                              .convertToUnits(LengthUnit.Millimeters);
        if (Math.abs(low.getX()) > FAR_AWAY / 2 || Math.abs(low.getY()) > FAR_AWAY / 2
                || Math.abs(high.getX()) > FAR_AWAY / 2 || Math.abs(high.getY()) > FAR_AWAY / 2) {
            return null;
        }
        double x0 = Math.min(low.getX(), high.getX());
        double y0 = Math.min(low.getY(), high.getY());
        return new Rectangle2D.Double(x0, y0, Math.max(low.getX(), high.getX()) - x0,
                Math.max(low.getY(), high.getY()) - y0);
    }

    /**
     * @return the whole area the camera can see: the reachable area plus half a frame around it,
     *         or null if the soft limits are not enabled.
     */
    public Rectangle2D getFullArea() throws Exception {
        Rectangle2D reach = getReachableArea();
        if (reach == null) {
            return null;
        }
        Point2D fov = getFieldOfView();
        return new Rectangle2D.Double(reach.getMinX() - fov.getX() / 2,
                reach.getMinY() - fov.getY() / 2, reach.getWidth() + fov.getX(),
                reach.getHeight() + fov.getY());
    }

    /**
     * Plans the camera positions for a region.
     *
     * @param region the machine area to scan in mm, or null for everything the camera can see.
     *        Without X and Y soft limits only a region can be scanned, and the camera is kept
     *        within it.
     */
    public Plan plan(Rectangle2D region) throws Exception {
        Rectangle2D full = getFullArea();
        Rectangle2D reach = getReachableArea();
        boolean isFull = region == null;
        if (isFull) {
            if (full == null) {
                throw new Exception("Scanning the whole bed needs the soft limits of the X and Y "
                        + "axes to be enabled, so that the machine size is known. Scan a region instead.");
            }
            region = full;
        }
        else if (full != null) {
            region = region.createIntersection(full);
            if (region.isEmpty()) {
                throw new Exception("The region is outside the area the camera can see, "
                        + format(full) + ".");
            }
        }
        if (reach == null) {
            reach = region;
        }
        Point2D fov = getFieldOfView();
        if (fov.getX() <= 0 || fov.getY() <= 0) {
            throw new Exception("Camera " + camera.getName()
                    + " has no units per pixel calibration or no image size yet.");
        }
        double[] xs = centers(region.getMinX(), region.getMaxX(), fov.getX(),
                fov.getX() * (1 - overlap), reach.getMinX(), reach.getMaxX());
        double[] ys = centers(region.getMinY(), region.getMaxY(), fov.getY(),
                fov.getY() * (1 - overlap), reach.getMinY(), reach.getMaxY());
        double[] xEdges = edges(xs, region.getMinX(), region.getMaxX());
        double[] yEdges = edges(ys, region.getMinY(), region.getMaxY());

        List<Tile> tiles = new ArrayList<>();
        // Rows from the top down, alternating direction, so the camera never travels back empty.
        for (int row = 0; row < ys.length; row++) {
            int j = ys.length - 1 - row;
            for (int col = 0; col < xs.length; col++) {
                int i = (row % 2 == 0) ? col : xs.length - 1 - col;
                Rectangle2D owned = new Rectangle2D.Double(xEdges[i], yEdges[j],
                        xEdges[i + 1] - xEdges[i], yEdges[j + 1] - yEdges[j]);
                tiles.add(new Tile(xs[i], ys[j], owned));
            }
        }
        return new Plan(region, isFull, xs.length, ys.length, tiles);
    }

    /**
     * Evenly spaced frame centers so that frames of size fov cover [min, max] with at most step
     * between neighbours, kept within what the camera can reach.
     */
    static double[] centers(double min, double max, double fov, double step,
            double reachMin, double reachMax) {
        double lo = clamp(min + fov / 2, reachMin, reachMax);
        double hi = clamp(max - fov / 2, reachMin, reachMax);
        if (hi - lo < 1e-6) {
            return new double[] {clamp((min + max) / 2, reachMin, reachMax)};
        }
        int n = (int) Math.ceil((hi - lo) / step - 1e-9) + 1;
        double[] c = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = lo + (hi - lo) * i / (n - 1);
        }
        return c;
    }

    /**
     * The borders between the areas owned by neighbouring centers: half way between them.
     */
    static double[] edges(double[] centers, double min, double max) {
        double[] e = new double[centers.length + 1];
        e[0] = min;
        e[centers.length] = max;
        for (int i = 1; i < centers.length; i++) {
            e[i] = (centers[i - 1] + centers[i]) / 2;
        }
        return e;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Runs the plan. Must not be called on the machine thread: every frame is its own machine
     * task, so the machine stays responsive and a scan can be cancelled between frames.
     *
     * @return the updated bed map, which is also saved, even if the scan was cancelled or failed
     *         part way.
     */
    public BedMap scan(Plan plan, Listener listener, AtomicBoolean cancel) throws Exception {
        BedMap map = plan.full ? null : BedMap.load(directory);
        if (map == null) {
            Rectangle2D full = getFullArea();
            map = BedMap.create(directory, full == null ? plan.region : full, mmPerPixel);
        }
        Location upp = getUnitsPerPixel();
        BedMap.Info info = map.getInfo();
        info.cameraId = camera.getId();
        info.cameraName = camera.getName();
        info.viewingPlaneZ = camera.getDefaultZ() == null ? 0
                : camera.getDefaultZ()
                        .convertToUnits(LengthUnit.Millimeters)
                        .getValue();

        File tilesDirectory = new File(directory, TILES_DIRECTORY);
        tilesDirectory.mkdirs();
        List<TileRecord> records = loadTileRecords(directory);
        // The new frames replace the old ones in the region.
        for (Iterator<TileRecord> it = records.iterator(); it.hasNext();) {
            TileRecord r = it.next();
            if (plan.region.contains(r.x, r.y)) {
                new File(tilesDirectory, r.file).delete();
                it.remove();
            }
        }

        long stamp = System.currentTimeMillis();
        int done = 0;
        try {
            for (Tile tile : plan.tiles) {
                if (cancel != null && cancel.get()) {
                    break;
                }
                BufferedImage frame = capture(tile);
                draw(map, frame, tile, upp);
                records.add(saveTile(tilesDirectory, stamp, done, tile, frame, upp,
                        info.viewingPlaneZ));
                done++;
                if (listener != null) {
                    listener.tileDone(map, done, plan.tiles.size());
                }
            }
        }
        finally {
            if (done > 0) {
                map.save();
            }
            saveTileRecords(directory, records);
        }
        return map;
    }

    private BufferedImage capture(Tile tile) throws Exception {
        Location target = camera.getLocation()
                                .convertToUnits(LengthUnit.Millimeters)
                                .derive(tile.x, tile.y, null, null);
        try {
            return machine.submit(() -> {
                MovableUtils.moveToLocationAtSafeZ(camera, target);
                return camera.lightSettleAndCapture();
            })
                          .get(TILE_TIMEOUT_S, TimeUnit.SECONDS);
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    /**
     * Scales the frame to the map resolution and draws the part of it this tile owns.
     */
    private static void draw(BedMap map, BufferedImage frame, Tile tile, Location upp) {
        double frameWidth = frame.getWidth() * upp.getX();
        double frameHeight = frame.getHeight() * upp.getY();
        Rectangle2D frameArea = new Rectangle2D.Double(tile.x - frameWidth / 2,
                tile.y - frameHeight / 2, frameWidth, frameHeight);
        Rectangle clip = map.toPixelRectangle(tile.owned.createIntersection(frameArea));
        if (clip.isEmpty()) {
            return;
        }
        int scaledWidth = (int) Math.round(frameWidth / map.getMmPerPixel());
        int scaledHeight = (int) Math.round(frameHeight / map.getMmPerPixel());
        Mat src = OpenCvUtils.toMat(frame);
        Mat dst = new Mat();
        // INTER_AREA averages the pixels instead of skipping them, which avoids aliasing.
        Imgproc.resize(src, dst, new Size(scaledWidth, scaledHeight), 0, 0, Imgproc.INTER_AREA);
        BufferedImage scaled = OpenCvUtils.toBufferedImage(dst);
        src.release();
        dst.release();
        Point2D topLeft = map.toPixel(frameArea.getMinX(), frameArea.getMaxY());
        synchronized (map) {
            Graphics2D g = map.getImage()
                              .createGraphics();
            g.setClip(clip);
            g.drawImage(scaled, (int) Math.round(topLeft.getX()),
                    (int) Math.round(topLeft.getY()), null);
            g.dispose();
        }
    }

    private static TileRecord saveTile(File tilesDirectory, long stamp, int index, Tile tile,
            BufferedImage frame, Location upp, double viewingPlaneZ) throws IOException {
        TileRecord r = new TileRecord();
        r.file = String.format("tile-%d-%04d.jpg", stamp, index);
        r.x = tile.x;
        r.y = tile.y;
        r.mmPerPixelX = upp.getX();
        r.mmPerPixelY = upp.getY();
        r.width = frame.getWidth();
        r.height = frame.getHeight();
        r.viewingPlaneZ = viewingPlaneZ;
        r.captured = new Date();
        // JPEG keeps the many full resolution frames small enough.
        BufferedImage rgb = frame;
        if (frame.getType() != BufferedImage.TYPE_3BYTE_BGR
                && frame.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(frame.getWidth(), frame.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(frame, 0, 0, null);
            g.dispose();
        }
        ImageIO.write(rgb, "jpg", new File(tilesDirectory, r.file));
        return r;
    }

    /**
     * Stitches the stored original frames anew for a machine area, at any resolution up to that
     * of the frames themselves. As in the bed map, each pixel is taken from the frame whose
     * center is nearest. Does not move the machine.
     *
     * @param camera the camera that captured the frames, used for the scale at viewingPlaneZ. May
     *        be null if viewingPlaneZ is null.
     * @param viewingPlaneZ the Z of the plane to stitch for, or null for the plane the frames were
     *        captured for. Objects at this height line up across frame borders.
     * @return the image, or null if no stored frame covers the area.
     */
    public static BufferedImage render(File directory, Rectangle2D area, double mmPerPixel,
            Camera camera, Double viewingPlaneZ) throws IOException {
        int width = (int) Math.ceil(area.getWidth() / mmPerPixel - 1e-9);
        int height = (int) Math.ceil(area.getHeight() / mmPerPixel - 1e-9);
        List<TileRecord> records = new ArrayList<>();
        List<Rectangle2D> frameAreas = new ArrayList<>();
        for (TileRecord r : loadTileRecords(directory)) {
            Point2D scale = getScale(r, camera, viewingPlaneZ);
            double w = r.width * r.mmPerPixelX * scale.getX();
            double h = r.height * r.mmPerPixelY * scale.getY();
            Rectangle2D frameArea = new Rectangle2D.Double(r.x - w / 2, r.y - h / 2, w, h);
            if (frameArea.intersects(area)) {
                records.add(r);
                frameAreas.add(frameArea);
            }
        }
        if (records.isEmpty() || width <= 0 || height <= 0) {
            return null;
        }

        // Which frame each pixel comes from. The distance to a frame center is measured in frame
        // sizes, so that below 0.5 the pixel is inside the frame.
        int[] owner = new int[width * height];
        double[] nearest = new double[width * height];
        Arrays.fill(owner, -1);
        Arrays.fill(nearest, 0.5);
        for (int k = 0; k < records.size(); k++) {
            Rectangle2D frameArea = frameAreas.get(k);
            Rectangle pixels = toPixels(frameArea, area, mmPerPixel, width, height);
            for (int py = pixels.y; py < pixels.y + pixels.height; py++) {
                double y = area.getMaxY() - (py + 0.5) * mmPerPixel;
                double dy = Math.abs(y - frameArea.getCenterY()) / frameArea.getHeight();
                for (int px = pixels.x; px < pixels.x + pixels.width; px++) {
                    double x = area.getMinX() + (px + 0.5) * mmPerPixel;
                    double d = Math.max(Math.abs(x - frameArea.getCenterX()) / frameArea.getWidth(),
                            dy);
                    int i = py * width + px;
                    if (d < nearest[i]) {
                        nearest[i] = d;
                        owner[i] = k;
                    }
                }
            }
        }

        File tilesDirectory = new File(directory, TILES_DIRECTORY);
        int[] rgb = new int[width * height];
        for (int k = 0; k < records.size(); k++) {
            Rectangle2D frameArea = frameAreas.get(k);
            Rectangle pixels = toPixels(frameArea, area, mmPerPixel, width, height);
            if (!owns(owner, k, pixels, width)) {
                continue;
            }
            BufferedImage frame = ImageIO.read(new File(tilesDirectory, records.get(k).file));
            if (frame == null) {
                Logger.warn("Could not read bed map tile {}", records.get(k).file);
                continue;
            }
            int scaledWidth = Math.max(1, (int) Math.round(frameArea.getWidth() / mmPerPixel));
            int scaledHeight = Math.max(1, (int) Math.round(frameArea.getHeight() / mmPerPixel));
            Mat src = OpenCvUtils.toMat(frame);
            Mat dst = new Mat();
            Imgproc.resize(src, dst, new Size(scaledWidth, scaledHeight), 0, 0,
                    scaledWidth < frame.getWidth() ? Imgproc.INTER_AREA : Imgproc.INTER_LINEAR);
            BufferedImage scaled = OpenCvUtils.toBufferedImage(dst);
            src.release();
            dst.release();
            int[] frameRgb = scaled.getRGB(0, 0, scaledWidth, scaledHeight, null, 0, scaledWidth);
            // Where the frame's top left corner is, in output pixels.
            double left = (frameArea.getMinX() - area.getMinX()) / mmPerPixel;
            double top = (area.getMaxY() - frameArea.getMaxY()) / mmPerPixel;
            for (int py = pixels.y; py < pixels.y + pixels.height; py++) {
                int sy = clamp((int) Math.floor(py + 0.5 - top), 0, scaledHeight - 1);
                for (int px = pixels.x; px < pixels.x + pixels.width; px++) {
                    int i = py * width + px;
                    if (owner[i] == k) {
                        int sx = clamp((int) Math.floor(px + 0.5 - left), 0, scaledWidth - 1);
                        rgb[i] = frameRgb[sy * scaledWidth + sx];
                    }
                }
            }
        }
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, width, height, rgb, 0, width);
        return out;
    }

    /**
     * @return the factor from the stored scale of the frame to the scale at viewingPlaneZ.
     */
    private static Point2D getScale(TileRecord r, Camera camera, Double viewingPlaneZ) {
        if (viewingPlaneZ == null || camera == null) {
            return new Point2D.Double(1, 1);
        }
        // Frames stored before the plane was recorded were captured for the default Z.
        Length storedZ = r.viewingPlaneZ == null ? camera.getDefaultZ()
                : new Length(r.viewingPlaneZ, LengthUnit.Millimeters);
        Location stored = camera.getUnitsPerPixel(storedZ)
                                .convertToUnits(LengthUnit.Millimeters);
        Location wanted = camera.getUnitsPerPixel(new Length(viewingPlaneZ, LengthUnit.Millimeters))
                                .convertToUnits(LengthUnit.Millimeters);
        return new Point2D.Double(wanted.getX() / stored.getX(), wanted.getY() / stored.getY());
    }

    /**
     * @return the output pixels a machine area covers, clipped to the output.
     */
    private static Rectangle toPixels(Rectangle2D a, Rectangle2D area, double mmPerPixel,
            int width, int height) {
        int x0 = (int) Math.floor((a.getMinX() - area.getMinX()) / mmPerPixel);
        int y0 = (int) Math.floor((area.getMaxY() - a.getMaxY()) / mmPerPixel);
        int x1 = (int) Math.ceil((a.getMaxX() - area.getMinX()) / mmPerPixel);
        int y1 = (int) Math.ceil((area.getMaxY() - a.getMinY()) / mmPerPixel);
        return new Rectangle(x0, y0, x1 - x0, y1 - y0)
                .intersection(new Rectangle(0, 0, width, height));
    }

    private static boolean owns(int[] owner, int k, Rectangle pixels, int width) {
        for (int py = pixels.y; py < pixels.y + pixels.height; py++) {
            for (int px = pixels.x; px < pixels.x + pixels.width; px++) {
                if (owner[py * width + px] == k) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static List<TileRecord> loadTileRecords(File directory) {
        File file = new File(new File(directory, TILES_DIRECTORY), TILES_INDEX_FILE);
        if (!file.exists()) {
            return new ArrayList<>();
        }
        try {
            List<TileRecord> records = gson().fromJson(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8),
                    new TypeToken<List<TileRecord>>() {}.getType());
            return records == null ? new ArrayList<>() : records;
        }
        catch (Exception e) {
            Logger.warn(e, "Could not read bed map tile index {}", file);
            return new ArrayList<>();
        }
    }

    private static void saveTileRecords(File directory, List<TileRecord> records)
            throws IOException {
        File file = new File(new File(directory, TILES_DIRECTORY), TILES_INDEX_FILE);
        Files.write(file.toPath(), gson().toJson(records)
                                         .getBytes(StandardCharsets.UTF_8));
    }

    private static Gson gson() {
        return new GsonBuilder().setPrettyPrinting()
                                .create();
    }

    private static String format(Rectangle2D r) {
        return String.format("x %.1f to %.1f, y %.1f to %.1f mm", r.getMinX(), r.getMaxX(),
                r.getMinY(), r.getMaxY());
    }
}
