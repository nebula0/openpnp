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

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import org.openpnp.CameraListener;
import org.openpnp.gui.MainFrame;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Job;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Camera;
import org.openpnp.util.Utils2D;
import org.pmw.tinylog.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Read-only endpoints for the AI bridge web UI: live camera frames, the job's board layout and the
 * machine status. Unlike {@link AiBridgeServer}, which runs requests one at a time so that machine
 * tasks never overlap, this server answers on its own threads, so the camera keeps streaming while
 * a long machine task is running. Nothing here moves the machine or changes the configuration.
 *
 * Binds to 127.0.0.1 and takes the same token as the AI bridge.
 */
class AiBridgeViewServer {
    private static final int DEFAULT_STREAM_FPS = 8;
    private static final int MAX_STREAM_FPS = 15;
    private static final String BOUNDARY = "aibridgeframe";

    private final AiBridgeServer bridge;
    private final String token;
    private final Gson gson = new GsonBuilder().serializeNulls()
                                               .create();
    /** Latest frame per camera, by camera id. */
    private final Map<String, Frames> frames = new ConcurrentHashMap<>();

    AiBridgeViewServer(AiBridgeServer bridge, String token, int port) throws IOException {
        this.bridge = bridge;
        this.token = token;
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "AiBridgeViewServer");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        Logger.info("AI bridge view server listening on 127.0.0.1:{}", port);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!token.equals(exchange.getRequestHeaders()
                                      .getFirst(AiBridgeServer.TOKEN_HEADER))) {
                sendJson(exchange, 401, AiBridgeServer.error(
                        "Missing or invalid " + AiBridgeServer.TOKEN_HEADER + " header."));
                return;
            }
            if (!exchange.getRequestMethod()
                         .equals("GET")) {
                sendJson(exchange, 404, AiBridgeServer.error("Only GET is served here."));
                return;
            }
            Map<String, String> query = AiBridgeServer.parseQuery(exchange.getRequestURI()
                                                                          .getRawQuery());
            switch (exchange.getRequestURI()
                            .getPath()) {
                case "/status":
                    JsonObject status = bridge.getStatus();
                    status.add("nozzles", bridge.getNozzles()
                                                .get("nozzles"));
                    sendJson(exchange, 200, status);
                    return;
                case "/cameras":
                    sendJson(exchange, 200, getCameras());
                    return;
                case "/camera/frame":
                    sendFrame(exchange, query);
                    return;
                case "/camera/stream":
                    streamFrames(exchange, query);
                    return;
                case "/job/layout":
                    sendJson(exchange, 200, getJobLayout(query.get("file")));
                    return;
            }
            sendJson(exchange, 404, AiBridgeServer.error("Unknown endpoint GET "
                    + exchange.getRequestURI()
                              .getPath()));
        }
        catch (AiBridgeServer.BadRequest e) {
            sendJson(exchange, 400, AiBridgeServer.error(e.getMessage()));
        }
        catch (IOException e) {
            // The browser went away, e.g. the camera stream was closed.
            throw e;
        }
        catch (Exception e) {
            Logger.warn(e, "AI bridge view request failed");
            sendJson(exchange, 500, AiBridgeServer.error(AiBridgeServer.describe(e)));
        }
        finally {
            exchange.close();
        }
    }

    private void sendJson(HttpExchange exchange, int code, JsonObject o) throws IOException {
        byte[] body = gson.toJson(o)
                          .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cameras
    // ---------------------------------------------------------------------------------------

    private static JsonObject getCameras() {
        JsonArray cameras = new JsonArray();
        for (Camera camera : Configuration.get()
                                          .getMachine()
                                          .getAllCameras()) {
            JsonObject c = new JsonObject();
            c.addProperty("id", camera.getId());
            c.addProperty("name", camera.getName());
            c.addProperty("looking", camera.getLooking()
                                           .toString());
            cameras.add(c);
        }
        JsonObject o = AiBridgeServer.ok();
        o.add("cameras", cameras);
        return o;
    }

    private Frames framesFor(String name) throws AiBridgeServer.BadRequest {
        if (name == null) {
            throw new AiBridgeServer.BadRequest("Give camera (name or id).");
        }
        for (Camera camera : Configuration.get()
                                          .getMachine()
                                          .getAllCameras()) {
            if (camera.getName()
                      .equals(name)
                    || camera.getId()
                             .equals(name)) {
                return frames.computeIfAbsent(camera.getId(), id -> new Frames(camera));
            }
        }
        throw new AiBridgeServer.BadRequest("No camera " + name + ".");
    }

    private void sendFrame(HttpExchange exchange, Map<String, String> query) throws Exception {
        byte[] jpeg = framesFor(query.get("camera")).next(-1, 3000).jpeg;
        if (jpeg == null) {
            sendJson(exchange, 503, AiBridgeServer.error("No frame from the camera yet."));
            return;
        }
        exchange.getResponseHeaders()
                .set("Content-Type", "image/jpeg");
        exchange.getResponseHeaders()
                .set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, jpeg.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(jpeg);
        }
    }

    /**
     * MJPEG stream, as multipart/x-mixed-replace that an img element plays directly. Runs until
     * the client closes the connection.
     */
    private void streamFrames(HttpExchange exchange, Map<String, String> query) throws Exception {
        Frames source = framesFor(query.get("camera"));
        int fps = DEFAULT_STREAM_FPS;
        if (query.get("fps") != null) {
            fps = Math.max(1, Math.min(MAX_STREAM_FPS, Integer.parseInt(query.get("fps"))));
        }
        long interval = 1000 / fps;
        exchange.getResponseHeaders()
                .set("Content-Type", "multipart/x-mixed-replace; boundary=" + BOUNDARY);
        exchange.getResponseHeaders()
                .set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            long sequence = -1;
            while (true) {
                long start = System.currentTimeMillis();
                // Resend the last frame now and then, so that a still camera does not look dead.
                Frame frame = source.next(sequence, 2000);
                if (frame.jpeg != null) {
                    sequence = frame.sequence;
                    os.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: "
                            + frame.jpeg.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    os.write(frame.jpeg);
                    os.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    os.flush();
                }
                long wait = interval - (System.currentTimeMillis() - start);
                if (wait > 0) {
                    Thread.sleep(wait);
                }
            }
        }
    }

    private static class Frame {
        final long sequence;
        final byte[] jpeg;

        Frame(long sequence, byte[] jpeg) {
            this.sequence = sequence;
            this.jpeg = jpeg;
        }
    }

    /**
     * Listens to a camera like the camera view in the GUI does, and keeps its latest frame. The
     * JPEG is only made when someone asks for a frame, once per frame.
     */
    private static class Frames implements CameraListener {
        private BufferedImage image;
        private long sequence;
        private Frame encoded = new Frame(-1, null);

        Frames(Camera camera) {
            camera.startContinuousCapture(this);
        }

        @Override
        public synchronized void frameReceived(BufferedImage img) {
            image = img;
            sequence++;
            notifyAll();
        }

        /**
         * @return the latest frame if it is newer than the given sequence, else waits up to
         *         timeoutMs for one and returns the latest frame anyway.
         */
        synchronized Frame next(long after, long timeoutMs) throws Exception {
            long end = System.currentTimeMillis() + timeoutMs;
            while (sequence <= after || image == null) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                wait(left);
            }
            if (image != null && encoded.sequence != sequence) {
                encoded = new Frame(sequence, toJpeg(image));
            }
            return encoded;
        }

        private static byte[] toJpeg(BufferedImage img) throws IOException {
            if (img.getType() != BufferedImage.TYPE_INT_RGB
                    && img.getType() != BufferedImage.TYPE_3BYTE_BGR) {
                BufferedImage rgb =
                        new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.drawImage(img, 0, 0, null);
                g.dispose();
                img = rgb;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", out);
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Job layout
    // ---------------------------------------------------------------------------------------

    /**
     * The boards of a job with their placements and the footprints of the parts, in mm, for
     * drawing. Without a file, the job open in the Job tab, with its current placed status.
     */
    private static JsonObject getJobLayout(String file) throws Exception {
        if (file != null && !file.trim()
                                 .isEmpty()) {
            Job job = AiBridgeServer.loadJob(file);
            return layoutOf(job);
        }
        // The GUI changes the open job, so read it on the Swing thread.
        return AiBridgeServer.onSwing(() -> layoutOf(MainFrame.get()
                                                                   .getJobTab()
                                                                   .getJob()));
    }

    private static JsonObject layoutOf(Job job) {
        Map<String, JsonObject> packages = new LinkedHashMap<>();
        JsonArray boards = new JsonArray();
        for (BoardLocation boardLocation : job.getBoardLocations()) {
            JsonObject b = new JsonObject();
            b.addProperty("boardId", boardLocation.getUniqueId());
            b.addProperty("name", boardLocation.getBoard()
                                               .getName());
            b.addProperty("enabled", boardLocation.isEnabled());
            b.addProperty("sideUp", boardLocation.getGlobalSide()
                                                 .toString());
            b.add("location", AiBridgeServer.toJson(boardLocation.getGlobalLocation()
                                                                 .convertToUnits(
                                                                         LengthUnit.Millimeters)));
            Location dimensions = boardLocation.getBoard()
                                               .getDimensions()
                                               .convertToUnits(LengthUnit.Millimeters);
            b.addProperty("width", AiBridgeServer.round(dimensions.getX()));
            b.addProperty("height", AiBridgeServer.round(dimensions.getY()));
            JsonArray placements = new JsonArray();
            for (Placement placement : boardLocation.getBoard()
                                                    .getPlacements()) {
                JsonObject p = new JsonObject();
                p.addProperty("placementId", placement.getId());
                p.addProperty("type", placement.getType()
                                               .toString());
                p.addProperty("side", placement.getSide()
                                               .toString());
                p.addProperty("enabled", placement.isEnabled());
                p.addProperty("placed", job.retrievePlacedStatus(boardLocation, placement.getId()));
                Location l = placement.getLocation()
                                      .convertToUnits(LengthUnit.Millimeters);
                p.addProperty("x", AiBridgeServer.round(l.getX()));
                p.addProperty("y", AiBridgeServer.round(l.getY()));
                p.addProperty("rotation", AiBridgeServer.round(l.getRotation()));
                p.add("machineLocation", AiBridgeServer.toJson(
                        Utils2D.calculateBoardPlacementLocation(boardLocation, placement)
                               .convertToUnits(LengthUnit.Millimeters)));
                Part part = placement.getPart();
                p.addProperty("partId", part == null ? null : part.getId());
                Package pkg = part == null ? null : part.getPackage();
                p.addProperty("packageId", pkg == null ? null : pkg.getId());
                if (pkg != null && !packages.containsKey(pkg.getId())) {
                    packages.put(pkg.getId(), footprintOf(pkg.getFootprint()));
                }
                placements.add(p);
            }
            b.add("placements", placements);
            boards.add(b);
        }
        JsonObject footprints = new JsonObject();
        packages.forEach(footprints::add);
        JsonObject o = AiBridgeServer.ok();
        o.addProperty("file", job.getFile() == null ? null
                : job.getFile()
                     .getAbsolutePath());
        o.add("boards", boards);
        o.add("footprints", footprints);
        return o;
    }

    private static JsonObject footprintOf(Footprint footprint) {
        JsonObject f = new JsonObject();
        if (footprint == null) {
            return f;
        }
        double mm = new Length(1, footprint.getUnits()).convertToUnits(LengthUnit.Millimeters)
                                                       .getValue();
        f.addProperty("bodyWidth", AiBridgeServer.round(footprint.getBodyWidth() * mm));
        f.addProperty("bodyHeight", AiBridgeServer.round(footprint.getBodyHeight() * mm));
        JsonArray pads = new JsonArray();
        for (Footprint.Pad pad : footprint.getPads()) {
            JsonObject p = new JsonObject();
            p.addProperty("name", pad.getName());
            p.addProperty("x", AiBridgeServer.round(pad.getX() * mm));
            p.addProperty("y", AiBridgeServer.round(pad.getY() * mm));
            p.addProperty("width", AiBridgeServer.round(pad.getWidth() * mm));
            p.addProperty("height", AiBridgeServer.round(pad.getHeight() * mm));
            p.addProperty("rotation", AiBridgeServer.round(pad.getRotation()));
            p.addProperty("roundness", AiBridgeServer.round(pad.getRoundness()));
            pads.add(p);
        }
        f.add("pads", pads);
        return f;
    }
}
