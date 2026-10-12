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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Date;

import javax.imageio.ImageIO;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * A stitched top view of the machine bed. The image is axis aligned with the machine: x grows to
 * the right and y grows upwards, so the top left pixel is at (left, top) in machine coordinates.
 * All lengths are in millimeters.
 *
 * Stored as bedmap.png and bedmap.json in its directory.
 */
public class BedMap {
    public static final double DEFAULT_MM_PER_PIXEL = 0.1;
    public static final String IMAGE_FILE = "bedmap.png";
    public static final String INFO_FILE = "bedmap.json";

    /**
     * What goes into bedmap.json. Kept separate from the image so Gson does not see it.
     */
    public static class Info {
        public double left;
        public double top;
        public double mmPerPixel;
        public int width;
        public int height;
        public String cameraId;
        public String cameraName;
        /**
         * The Z of the plane the camera scale (units per pixel) refers to. Objects at other
         * heights appear slightly scaled and shifted.
         */
        public double viewingPlaneZ;
        public Date updated;
    }

    private final File directory;
    private final Info info;
    private final BufferedImage image;

    private BedMap(File directory, Info info, BufferedImage image) {
        this.directory = directory;
        this.info = info;
        this.image = image;
    }

    /**
     * Creates an empty bed map covering the given machine area.
     */
    public static BedMap create(File directory, Rectangle2D bounds, double mmPerPixel) {
        Info info = new Info();
        info.left = bounds.getMinX();
        info.top = bounds.getMaxY();
        info.mmPerPixel = mmPerPixel;
        info.width = (int) Math.ceil(bounds.getWidth() / mmPerPixel);
        info.height = (int) Math.ceil(bounds.getHeight() / mmPerPixel);
        BufferedImage image =
                new BufferedImage(info.width, info.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.black);
        g.fillRect(0, 0, info.width, info.height);
        g.dispose();
        return new BedMap(directory, info, image);
    }

    /**
     * @return the bed map stored in directory, or null if there is none.
     */
    public static BedMap load(File directory) throws IOException {
        File infoFile = new File(directory, INFO_FILE);
        File imageFile = new File(directory, IMAGE_FILE);
        if (!infoFile.exists() || !imageFile.exists()) {
            return null;
        }
        Info info = gson().fromJson(
                new String(Files.readAllBytes(infoFile.toPath()), StandardCharsets.UTF_8),
                Info.class);
        BufferedImage stored = ImageIO.read(imageFile);
        // Draw into an RGB image, PNG decoding may give another type.
        BufferedImage image =
                new BufferedImage(stored.getWidth(), stored.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.drawImage(stored, 0, 0, null);
        g.dispose();
        info.width = image.getWidth();
        info.height = image.getHeight();
        return new BedMap(directory, info, image);
    }

    public synchronized void save() throws IOException {
        directory.mkdirs();
        info.updated = new Date();
        // Write to temporary files first so a crash cannot leave a half written map.
        File imageTmp = new File(directory, IMAGE_FILE + ".tmp");
        File infoTmp = new File(directory, INFO_FILE + ".tmp");
        ImageIO.write(image, "png", imageTmp);
        Files.write(infoTmp.toPath(), gson().toJson(info)
                                            .getBytes(StandardCharsets.UTF_8));
        replace(imageTmp, new File(directory, IMAGE_FILE));
        replace(infoTmp, new File(directory, INFO_FILE));
    }

    private static void replace(File from, File to) throws IOException {
        Files.move(from.toPath(), to.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static Gson gson() {
        return new GsonBuilder().setPrettyPrinting()
                                .create();
    }

    public Info getInfo() {
        return info;
    }

    public File getDirectory() {
        return directory;
    }

    /**
     * The image itself, not a copy. Synchronize on this BedMap while drawing into it.
     */
    public BufferedImage getImage() {
        return image;
    }

    public double getMmPerPixel() {
        return info.mmPerPixel;
    }

    /**
     * @return the covered machine area in mm.
     */
    public Rectangle2D getBounds() {
        return new Rectangle2D.Double(info.left, info.top - info.height * info.mmPerPixel,
                info.width * info.mmPerPixel, info.height * info.mmPerPixel);
    }

    /**
     * Converts machine coordinates (mm) to image pixel coordinates.
     */
    public Point2D toPixel(double x, double y) {
        return new Point2D.Double((x - info.left) / info.mmPerPixel,
                (info.top - y) / info.mmPerPixel);
    }

    /**
     * Converts image pixel coordinates to machine coordinates (mm).
     */
    public Point2D toMachine(double px, double py) {
        return new Point2D.Double(info.left + px * info.mmPerPixel,
                info.top - py * info.mmPerPixel);
    }

    /**
     * Converts a machine area (mm) to the image pixel rectangle it covers, clipped to the image.
     */
    public Rectangle toPixelRectangle(Rectangle2D area) {
        Point2D topLeft = toPixel(area.getMinX(), area.getMaxY());
        Point2D bottomRight = toPixel(area.getMaxX(), area.getMinY());
        int x0 = (int) Math.floor(topLeft.getX());
        int y0 = (int) Math.floor(topLeft.getY());
        int x1 = (int) Math.ceil(bottomRight.getX());
        int y1 = (int) Math.ceil(bottomRight.getY());
        return new Rectangle(x0, y0, x1 - x0, y1 - y0)
                .intersection(new Rectangle(0, 0, info.width, info.height));
    }

    /**
     * @return a copy of the given machine area (mm), or null if it does not overlap the map.
     */
    public synchronized BufferedImage crop(Rectangle2D area) {
        Rectangle r = toPixelRectangle(area);
        if (r.isEmpty()) {
            return null;
        }
        BufferedImage out = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(image, -r.x, -r.y, null);
        g.dispose();
        return out;
    }
}
