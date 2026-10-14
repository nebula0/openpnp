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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.apache.commons.io.IOUtils;
import org.opencv.core.Mat;
import org.opencv.core.RotatedRect;
import org.opencv.imgproc.Imgproc;
import org.openpnp.bedmap.BedMap;
import org.openpnp.bedmap.BedMapScanner;
import org.openpnp.gui.FeedersPanel;
import org.openpnp.gui.MainFrame;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.feeder.ReferenceLoosePartFeeder;
import org.openpnp.machine.reference.feeder.ReferenceStripFeeder;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Job;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.model.PlacementsHolderLocation;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Head;
import org.openpnp.spi.JobProcessor;
import org.openpnp.spi.JobProcessor.JobProcessorException;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.NozzleTip;
import org.openpnp.util.Cycles;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.Utils2D;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.ImageCapture;
import org.openpnp.vision.pipeline.stages.Threshold;
import org.pmw.tinylog.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A small HTTP/JSON server that lets an external AI assistant (via the ai-bridge MCP server) read
 * machine state and perform a limited set of operations. It binds to 127.0.0.1 only, is disabled
 * by default, and requires a token that is written to the configuration directory on startup.
 *
 * Enable with -DaiBridge.port=8999 on the java command line.
 *
 * Any operation that moves the machine requires "confirm": true in the request. Without it, the
 * server answers with status "needs_confirmation" and a description of what it would do, so that
 * the assistant can ask the user first. Motion is refused while the machine is disabled, not
 * homed, busy, or a job is active.
 */
public class AiBridgeServer {
    public static final String PORT_PROPERTY = "aiBridge.port";
    public static final String TOKEN_HEADER = "X-AI-Bridge-Token";

    private static final long MACHINE_TASK_TIMEOUT_S = 180;
    /** Longest way down a Z probe may go, mm. */
    private static final double MAX_PROBE_RANGE_MM = 4;
    /** Speed factor for the Z probe steps. */
    private static final double PROBE_SPEED = 0.2;
    /**
     * Placing a few parts, with fiducials and vision, takes longer than other machine tasks.
     */
    private static final long JOB_TIMEOUT_S = 900;
    /**
     * How many picked parts in a row may fail, e.g. in bottom vision, before place_from_job
     * stops instead of picking the next one.
     */
    private static final int DEFAULT_MAX_PART_FAILURES = 5;
    /**
     * Inspection: how far a part may be off and turned, and how far to look for it.
     */
    private static final double DEFAULT_PLACEMENT_TOLERANCE_MM = 0.25;
    private static final double DEFAULT_MAX_ROTATION_ERROR = 5;
    private static final double PLACEMENT_SEARCH_MM = 1;
    private static final int MAX_IMAGE_DIMENSION = 800;
    private static final int MAX_BED_MAP_IMAGE_DIMENSION = 1600;
    private static final int MIN_INSPECT_IMAGE_DIMENSION = 600;

    private static AiBridgeServer instance;

    private final HttpServer server;
    private final String token;
    private final File bridgeDirectory;
    private final Gson gson = new GsonBuilder().serializeNulls()
                                               .setPrettyPrinting()
                                               .create();
    private final AtomicBoolean bedMapScanCancel = new AtomicBoolean();
    /**
     * State of the current or last bed map scan, null if there was none. Guarded by this.
     */
    private JsonObject bedMapScanState;

    /**
     * Starts the server if the aiBridge.port system property is set. Call once the Configuration
     * and MainFrame are available.
     */
    public static synchronized void startIfEnabled(Configuration configuration) {
        String portString = System.getProperty(PORT_PROPERTY);
        if (portString == null || portString.trim()
                                            .isEmpty()
                || instance != null) {
            return;
        }
        try {
            instance = new AiBridgeServer(configuration, Integer.parseInt(portString.trim()));
        }
        catch (Exception e) {
            Logger.error(e, "AI bridge failed to start on port {}", portString);
        }
    }

    private AiBridgeServer(Configuration configuration, int port) throws Exception {
        bridgeDirectory = new File(configuration.getConfigurationDirectory(), "ai-bridge");
        bridgeDirectory.mkdirs();

        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        token = sb.toString();

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::handle);
        // A single thread keeps requests serialized; long machine tasks block other requests,
        // which is what we want.
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AiBridgeServer");
            t.setDaemon(true);
            return t;
        }));
        server.start();

        JsonObject info = new JsonObject();
        info.addProperty("port", port);
        info.addProperty("token", token);
        Files.write(new File(bridgeDirectory, "bridge.json").toPath(),
                gson.toJson(info)
                    .getBytes(StandardCharsets.UTF_8));
        Logger.info("AI bridge listening on 127.0.0.1:{}", port);
    }

    private void handle(HttpExchange exchange) throws IOException {
        JsonObject response;
        int code = 200;
        try {
            if (!token.equals(exchange.getRequestHeaders()
                                      .getFirst(TOKEN_HEADER))) {
                code = 401;
                response = error("Missing or invalid " + TOKEN_HEADER + " header.");
            }
            else {
                response = route(exchange);
                if (response == null) {
                    code = 404;
                    response = error("Unknown endpoint " + exchange.getRequestMethod() + " "
                            + exchange.getRequestURI()
                                      .getPath());
                }
            }
        }
        catch (BadRequest e) {
            code = 400;
            response = error(e.getMessage());
        }
        catch (Exception e) {
            Logger.warn(e, "AI bridge request failed");
            code = 500;
            response = error(describe(e));
        }
        byte[] body = gson.toJson(response)
                          .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private JsonObject route(HttpExchange exchange) throws Exception {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI()
                              .getPath();
        Map<String, String> query = parseQuery(exchange.getRequestURI()
                                                       .getRawQuery());
        if (method.equals("GET")) {
            switch (path) {
                case "/status":
                    return getStatus();
                case "/feeders":
                    return getFeeders(query.get("partId"));
                case "/nozzles":
                    return getNozzles();
                case "/bedmap":
                    return getBedMap(query);
                case "/bedmap/scan":
                    return getBedMapScanState();
                case "/job/placements":
                    return getJobPlacements(query);
            }
        }
        else if (method.equals("POST")) {
            JsonObject body = readBody(exchange);
            switch (path) {
                case "/feeder/inspect":
                    return inspectFeeder(body);
                case "/feeder/pick":
                    return pickFeeder(body);
                case "/machine/home":
                    return homeMachine(body);
                case "/nozzle/discard":
                    return discardPart(body);
                case "/bedmap/scan":
                    return scanBedMap(body);
                case "/bedmap/scan/cancel":
                    return cancelBedMapScan();
                case "/job/place":
                    return placeFromJob(body);
                case "/job/inspect":
                    return inspectPlacements(body);
                case "/camera/capture":
                    return captureCamera(body);
                case "/feeder/strip/setup":
                    return setupStripFeeder(body);
                case "/camera/move":
                    return moveCamera(body);
                case "/feeder/probe-z":
                    return probeFeederZ(body);
                case "/feeder/update":
                    return updateFeeder(body);
                case "/vision/bottom/test":
                    return testBottomVision(body);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Read-only endpoints
    // ---------------------------------------------------------------------------------------

    private JsonObject getStatus() throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        JsonObject o = ok();
        o.addProperty("enabled", machine.isEnabled());
        o.addProperty("homed", machine.isHomed());
        o.addProperty("busy", machine.isBusy());
        o.addProperty("jobActive", isJobActive());
        Nozzle selected = onSwing(() -> MainFrame.get()
                                                 .getMachineControls()
                                                 .getSelectedNozzle());
        o.addProperty("selectedNozzleId", selected == null ? null : selected.getId());
        String refusal = motionRefusal(machine);
        o.addProperty("motionAllowed", refusal == null);
        if (refusal != null) {
            o.addProperty("motionRefusedBecause", refusal);
        }
        return o;
    }

    private JsonObject getFeeders(String partId) {
        Machine machine = Configuration.get()
                                       .getMachine();
        JsonArray feeders = new JsonArray();
        for (Feeder feeder : machine.getFeeders()) {
            Part part = feeder.getPart();
            if (partId != null && (part == null || !part.getId()
                                                        .equalsIgnoreCase(partId))) {
                continue;
            }
            JsonObject f = new JsonObject();
            f.addProperty("id", feeder.getId());
            f.addProperty("name", feeder.getName());
            f.addProperty("class", feeder.getClass()
                                         .getSimpleName());
            f.addProperty("enabled", feeder.isEnabled());
            f.addProperty("partId", part == null ? null : part.getId());
            f.addProperty("packageId", part == null || part.getPackage() == null ? null
                    : part.getPackage()
                          .getId());
            try {
                f.add("pickLocation", toJson(feeder.getPickLocation()));
            }
            catch (Exception e) {
                f.addProperty("pickLocation", (String) null);
            }
            f.addProperty("canTakeBackPart", feeder.canTakeBackPart());
            f.addProperty("supportsInspect", feeder instanceof ReferenceLoosePartFeeder);
            feeders.add(f);
        }
        JsonObject o = ok();
        o.add("feeders", feeders);
        return o;
    }

    private JsonObject getNozzles() throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        Nozzle selected = onSwing(() -> MainFrame.get()
                                                 .getMachineControls()
                                                 .getSelectedNozzle());
        JsonArray nozzles = new JsonArray();
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                JsonObject n = new JsonObject();
                n.addProperty("id", nozzle.getId());
                n.addProperty("name", nozzle.getName());
                n.addProperty("headId", head.getId());
                n.addProperty("selected", nozzle == selected);
                n.addProperty("nozzleTip", nozzle.getNozzleTip() == null ? null
                        : nozzle.getNozzleTip()
                                .getName());
                n.addProperty("partOnNozzle", nozzle.getPart() == null ? null
                        : nozzle.getPart()
                                .getId());
                // The Z OpenPnP last commanded. The steppers have no encoders, so after a crash
                // or lost steps this may differ from reality until the machine is homed again.
                Length z = nozzle.getLocation()
                                 .getLengthZ();
                n.addProperty("z", round(z.convertToUnits(LengthUnit.Millimeters)
                                          .getValue()));
                n.addProperty("atSafeZ", nozzle.isInSafeZZone(z));
                nozzles.add(n);
            }
        }
        JsonObject o = ok();
        o.add("nozzles", nozzles);
        return o;
    }

    // ---------------------------------------------------------------------------------------
    // Motion endpoints (always require confirm=true)
    // ---------------------------------------------------------------------------------------

    /**
     * Moves the head's default (down looking) camera over the feeder and runs the feeder's vision
     * pipeline without picking. Reports how many parts were found and returns images.
     *
     * Body: {feederId, confirm, allStages (optional, default false)}
     */
    private JsonObject inspectFeeder(JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        Feeder feeder = requireFeeder(machine, body);
        if (!(feeder instanceof ReferenceLoosePartFeeder)) {
            throw new BadRequest("Inspect is only supported for ReferenceLoosePartFeeder, "
                    + feeder.getName() + " is a " + feeder.getClass()
                                                          .getSimpleName());
        }
        ReferenceLoosePartFeeder looseFeeder = (ReferenceLoosePartFeeder) feeder;
        boolean allStages = getBoolean(body, "allStages", false);
        Location feederLocation = looseFeeder.getLocation();

        JsonObject gate = gateMotion(machine, body,
                "Move the top camera to feeder " + feeder.getName() + " at "
                        + format(feederLocation) + " (at safe Z) and run its vision pipeline. "
                        + "No pick.");
        if (gate != null) {
            return gate;
        }

        return runLogged("feeder/inspect", body, machine, () -> {
            Head head = machine.getDefaultHead();
            Camera camera = head.getDefaultCamera();
            Nozzle nozzle = onSwing(() -> MainFrame.get()
                                                   .getMachineControls()
                                                   .getSelectedNozzle());
            MovableUtils.moveToLocationAtSafeZ(camera, feederLocation);
            JsonObject o = ok();
            o.addProperty("feederId", feeder.getId());
            o.addProperty("feederName", feeder.getName());
            o.addProperty("cameraName", camera.getName());
            try (CvPipeline pipeline = looseFeeder.getPipeline()) {
                pipeline.setProperty("camera", camera);
                pipeline.setProperty("nozzle", nozzle);
                pipeline.setProperty("feeder", feeder);
                try {
                    pipeline.process();
                }
                catch (Exception e) {
                    o.addProperty("pipelineError", describe(e));
                }
                JsonArray parts = new JsonArray();
                CvStage.Result results = pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME);
                if (results != null && results.getModel() instanceof List) {
                    for (Object model : (List<?>) results.getModel()) {
                        if (!(model instanceof RotatedRect)) {
                            continue;
                        }
                        RotatedRect rect = (RotatedRect) model;
                        Location location =
                                VisionUtils.getPixelLocation(camera, rect.center.x, rect.center.y);
                        JsonObject p = new JsonObject();
                        p.add("location", toJson(location));
                        p.addProperty("distanceFromFeederMm",
                                round(location.convertToUnits(feederLocation.getUnits())
                                              .getLinearDistanceTo(feederLocation)));
                        p.addProperty("widthPx", round(rect.size.width));
                        p.addProperty("heightPx", round(rect.size.height));
                        p.addProperty("angle", round(rect.angle));
                        parts.add(p);
                    }
                }
                o.addProperty("partsFound", parts.size());
                o.add("parts", parts);
                o.add("images", collectImages(pipeline, allStages));
                Mat working = pipeline.getWorkingImage();
                if (working != null) {
                    BufferedImage image = OpenCvUtils.toBufferedImage(working);
                    SwingUtilities.invokeLater(() -> MainFrame.get()
                                                              .getCameraViews()
                                                              .getCameraView(camera)
                                                              .showFilteredImage(image, 2000));
                }
            }
            return o;
        });
    }

    /**
     * Feeds and picks a part from the feeder like the "Pick" button in the Feeders tab, then
     * optionally puts it back like the "Recycle" button.
     *
     * Body: {feederId, confirm, nozzleId (optional), returnPart (optional, default true)}
     */
    private JsonObject pickFeeder(JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        Feeder feeder = requireFeeder(machine, body);
        boolean returnPart = getBoolean(body, "returnPart", true);
        Nozzle nozzle = null;
        String nozzleId = getString(body, "nozzleId");
        if (nozzleId != null) {
            nozzle = findNozzle(machine, nozzleId);
            if (nozzle == null) {
                throw new BadRequest("No nozzle with id " + nozzleId);
            }
        }
        if (feeder.getPart() == null) {
            throw new BadRequest("Feeder " + feeder.getName() + " has no part set.");
        }
        if (returnPart && !(feeder instanceof ReferenceLoosePartFeeder)) {
            // Other feeders may support take back, but only loose part feeders are tested so far.
            throw new BadRequest("returnPart is only supported for ReferenceLoosePartFeeder so far.");
        }

        JsonObject gate = gateMotion(machine, body,
                "Feed and pick one " + feeder.getPart()
                                             .getId()
                        + " from feeder " + feeder.getName() + " with nozzle "
                        + (nozzle == null ? "(currently selected)" : nozzle.getName())
                        + (returnPart ? ", then put it back where it was picked."
                                : ", and keep it on the nozzle."));
        if (gate != null) {
            return gate;
        }

        if (nozzle != null) {
            final Nozzle n = nozzle;
            onSwing(() -> {
                MainFrame.get()
                         .getMachineControls()
                         .setSelectedTool(n);
                return null;
            });
        }

        return runLogged("feeder/pick", body, machine, () -> {
            JsonObject o = ok();
            o.addProperty("feederId", feeder.getId());
            o.addProperty("feederName", feeder.getName());
            o.addProperty("partId", feeder.getPart()
                                          .getId());

            FeedersPanel.pickFeeder(feeder);
            Nozzle used = MainFrame.get()
                                   .getMachineControls()
                                   .getSelectedNozzle();
            o.addProperty("nozzleId", used.getId());
            o.addProperty("nozzleName", used.getName());
            o.addProperty("picked", true);
            o.add("pickLocation", toJson(feeder.getPickLocation()));
            o.addProperty("vacuumCheckedAfterPick",
                    used.isPartOnEnabled(Nozzle.PartOnStep.AfterPick));

            if (returnPart) {
                Map<String, Object> globals = new HashMap<>();
                globals.put("nozzle", used);
                globals.put("feeder", feeder);
                globals.put("part", feeder.getPart());
                Configuration.get()
                             .getScripting()
                             .on("Feeder.BeforeTakeBack", globals);
                feeder.takeBackPart(used);
                Configuration.get()
                             .getScripting()
                             .on("Feeder.AfterTakeBack", globals);
                o.addProperty("returned", true);
                o.addProperty("vacuumCheckedAfterReturn",
                        used.isPartOffEnabled(Nozzle.PartOffStep.AfterPlace));
            }
            o.addProperty("partOnNozzle", used.getPart() == null ? null
                    : used.getPart()
                          .getId());
            return o;
        });
    }

    /**
     * Enables the machine if needed and homes it, like the power and home buttons.
     *
     * Body: {confirm}
     */
    private JsonObject homeMachine(JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        JsonObject gate = gate(homeRefusal(machine), body,
                (machine.isEnabled() ? "" : "Enable the machine, then home it. ")
                        + "Homing moves all axes to their home positions or switches. "
                        + "Make sure nothing is in the way of the head before confirming.");
        if (gate != null) {
            return gate;
        }
        if (!machine.isEnabled()) {
            // Machine tasks refuse to run on a disabled machine, except those that enable it.
            try {
                machine.submit(() -> {
                    machine.setEnabled(true);
                    return null;
                }, null, true)
                       .get(MACHINE_TASK_TIMEOUT_S, TimeUnit.SECONDS);
            }
            catch (java.util.concurrent.ExecutionException e) {
                JsonObject result = error("Could not enable the machine: "
                        + describe(e.getCause() == null ? e : e.getCause()));
                logAction("machine/home", body, result);
                return result;
            }
        }
        return runLogged("machine/home", body, machine, () -> {
            machine.home();
            JsonObject o = ok();
            o.addProperty("enabled", machine.isEnabled());
            o.addProperty("homed", machine.isHomed());
            return o;
        });
    }

    /**
     * Drops whatever is on a nozzle at the machine's discard location, like the discard button.
     * It does so even if OpenPnP thinks the nozzle is empty, since after a failure it may not
     * know.
     *
     * Body: {confirm, nozzleId (optional, default the selected nozzle)}
     */
    private JsonObject discardPart(JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        Nozzle nozzle;
        String nozzleId = getString(body, "nozzleId");
        if (nozzleId != null) {
            nozzle = findNozzle(machine, nozzleId);
            if (nozzle == null) {
                throw new BadRequest("No nozzle with id " + nozzleId);
            }
        }
        else {
            nozzle = onSwing(() -> MainFrame.get()
                                            .getMachineControls()
                                            .getSelectedNozzle());
            if (nozzle == null) {
                throw new BadRequest("No nozzle is selected, give nozzleId.");
            }
        }
        Location discardLocation = machine.getDiscardLocation();
        JsonObject gate = gateMotion(machine, body,
                "Move nozzle " + nozzle.getName() + " to the discard location "
                        + format(discardLocation) + " and release whatever it holds ("
                        + (nozzle.getPart() == null ? "OpenPnP thinks it is empty"
                                : "OpenPnP thinks it holds " + nozzle.getPart()
                                                                     .getId())
                        + ").");
        if (gate != null) {
            return gate;
        }
        return runLogged("nozzle/discard", body, machine, () -> {
            String part = nozzle.getPart() == null ? null
                    : nozzle.getPart()
                            .getId();
            Cycles.discardAlways(nozzle);
            JsonObject o = ok();
            o.addProperty("nozzleId", nozzle.getId());
            o.addProperty("nozzleName", nozzle.getName());
            o.addProperty("discardedPart", part);
            o.add("discardLocation", toJson(discardLocation));
            o.addProperty("vacuumCheckedAfterDiscard",
                    nozzle.isPartOffEnabled(Nozzle.PartOffStep.AfterPlace));
            o.addProperty("partOnNozzle", nozzle.getPart() == null ? null
                    : nozzle.getPart()
                            .getId());
            return o;
        });
    }

    /**
     * Takes a photo with the top camera where it is, without moving the machine, and returns it
     * with the camera location and scale, so that pixels can be converted to machine coordinates.
     * The original frame is kept in the captures directory.
     *
     * Body: {z (optional, mm): the height of what is looked at, for the scale; default the
     * camera's default Z}
     */
    private JsonObject captureCamera(JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        Camera camera = machine.getDefaultHead()
                               .getDefaultCamera();
        JsonElement zElement = body.get("z");
        Length z = zElement == null || zElement.isJsonNull() ? camera.getDefaultZ()
                : new Length(zElement.getAsDouble(), LengthUnit.Millimeters);
        BufferedImage frame = runOnMachine(machine, () -> camera.lightSettleAndCapture());
        Location location = camera.getLocation()
                                  .convertToUnits(LengthUnit.Millimeters);
        Location upp = camera.getUnitsPerPixel(z)
                             .convertToUnits(LengthUnit.Millimeters);
        File directory = new File(bridgeDirectory, "captures");
        directory.mkdirs();
        File file = new File(directory,
                new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".png");
        ImageIO.write(frame, "png", file);

        JsonObject o = ok();
        o.addProperty("cameraName", camera.getName());
        o.add("cameraLocation", toJson(location));
        o.addProperty("viewingPlaneZ", round(z.convertToUnits(LengthUnit.Millimeters)
                                              .getValue()));
        o.addProperty("mmPerPixelX", upp.getX());
        o.addProperty("mmPerPixelY", upp.getY());
        o.addProperty("frameWidth", frame.getWidth());
        o.addProperty("frameHeight", frame.getHeight());
        double scale = Math.min(1.0,
                (double) MAX_BED_MAP_IMAGE_DIMENSION / Math.max(frame.getWidth(),
                        frame.getHeight()));
        o.addProperty("imageScale", scale);
        o.addProperty("coordinates", "Image pixel (px, py) is at machine x = cameraLocation.x + "
                + "(px / imageScale - frameWidth / 2) * mmPerPixelX, y = cameraLocation.y - "
                + "(py / imageScale - frameHeight / 2) * mmPerPixelY. Up in the image is +y.");
        o.addProperty("frameFile", file.getAbsolutePath());
        JsonArray images = new JsonArray();
        JsonObject i = new JsonObject();
        i.addProperty("name", "capture");
        i.addProperty("mimeType", "image/png");
        i.addProperty("data", toPngBase64(frame, MAX_BED_MAP_IMAGE_DIMENSION));
        images.add(i);
        o.add("images", images);
        return o;
    }

    /**
     * Finds the pick Z of a feeder by touching the next part with a nozzle: from above the
     * expected Z it steps down with the vacuum on until the vacuum sensor shows that the nozzle
     * tip is sealed on the part, never below floorZ. At contact the vacuum is turned off before
     * lifting, so that the part stays in the tape. With apply, a strip feeder gets the measured Z.
     *
     * Body: {feederId, nozzleId (optional, default a nozzle with a fitting tip), startZ (default
     * the feeder's Z + 1.5), floorZ (default the feeder's Z - 1), step (mm, default 0.05),
     * settleMs (default 150), apply (default false), confirm}
     */
    private JsonObject probeFeederZ(JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        Feeder feeder = requireFeeder(machine, body);
        Part part = feeder.getPart();
        if (part == null) {
            throw new BadRequest("Feeder " + feeder.getName() + " has no part set.");
        }
        ReferenceNozzle nozzle = probeNozzle(machine, body, part);
        if (nozzle.getPart() != null) {
            throw new BadRequest("Nozzle " + nozzle.getName() + " holds a part, discard it "
                    + "first.");
        }
        ReferenceNozzleTip tip = (ReferenceNozzleTip) nozzle.getNozzleTip();
        Location pick = nextPickLocation(feeder).convertToUnits(LengthUnit.Millimeters);
        double expectedZ = pick.getZ();
        double startZ = getDouble(body, "startZ", expectedZ + 1.5);
        double floorZ = getDouble(body, "floorZ", expectedZ - 1);
        double step = getDouble(body, "step", 0.05);
        int settleMs = (int) getDouble(body, "settleMs", 150);
        double drop = getDouble(body, "contactDrop", Double.NaN);
        Double contactDrop = Double.isNaN(drop) ? null : drop;
        boolean apply = getBoolean(body, "apply", false);
        if (contactDrop != null && (contactDrop < 2 || contactDrop > 100)) {
            throw new BadRequest("contactDrop must be between 2 and 100.");
        }
        if (step < 0.01 || step > 0.2) {
            throw new BadRequest("step must be between 0.01 and 0.2 mm.");
        }
        if (startZ <= floorZ || startZ - floorZ > MAX_PROBE_RANGE_MM) {
            throw new BadRequest("startZ must be above floorZ, by at most " + MAX_PROBE_RANGE_MM
                    + " mm.");
        }
        if (settleMs < 0 || settleMs > 2000) {
            throw new BadRequest("settleMs must be between 0 and 2000.");
        }
        if (apply && !(feeder instanceof ReferenceStripFeeder)) {
            throw new BadRequest("apply is only supported for ReferenceStripFeeder so far.");
        }

        JsonObject gate = gateMotion(machine, body, "Move nozzle " + nozzle.getName() + " (tip "
                + tip.getName() + ") over the next " + part.getId() + " of feeder "
                + feeder.getName() + " at X " + round(pick.getX()) + ", Y " + round(pick.getY())
                + ", down to Z " + round(startZ) + ", turn its vacuum on and step down by "
                + round(step) + " mm until the vacuum shows contact, at most down to Z "
                + round(floorZ) + " (the feeder's Z is now " + round(expectedZ) + "). At contact "
                + "the vacuum is turned off before lifting, so the part stays in the tape. "
                + (apply ? "Then the feeder's Z is set to the measured Z."
                        : "The feeder is not changed."));
        if (gate != null) {
            return gate;
        }

        return runLogged("feeder/probe-z", body, machine, () -> {
            Actuator valve = nozzle.getExpectedVacuumActuator();
            JsonArray readings = new JsonArray();
            Double contactZ = null;
            double threshold;
            MovableUtils.moveToLocationAtSafeZ(nozzle, pick.derive(null, null, startZ, null));
            nozzle.getHead()
                  .actuatePumpRequest(nozzle, true);
            try {
                valve.actuate(true);
                nozzle.waitForCompletion(CompletionType.WaitForStillstand);
                Thread.sleep(Math.max(settleMs, 300));
                double baseline = nozzle.readVacuumLevel();
                threshold = contactThreshold(tip, baseline, contactDrop);
                readings.add(reading(startZ, baseline));
                if (baseline - threshold < 2) {
                    throw new Exception("The vacuum level " + baseline + " with the tip in the "
                            + "air is too close to the contact threshold " + threshold
                            + " to tell contact. Is the tip blocked or already touching?");
                }
                int steps = (int) Math.floor((startZ - floorZ) / step + 1e-9);
                for (int i = 1; i <= steps && contactZ == null; i++) {
                    double z = startZ - i * step;
                    nozzle.moveTo(pick.derive(null, null, z, null), PROBE_SPEED);
                    nozzle.waitForCompletion(CompletionType.WaitForStillstand);
                    Thread.sleep(settleMs);
                    double level = nozzle.readVacuumLevel();
                    readings.add(reading(z, level));
                    if (level <= threshold) {
                        // Read again, a single low reading may be noise.
                        Thread.sleep(settleMs);
                        level = nozzle.readVacuumLevel();
                        readings.add(reading(z, level));
                        if (level <= threshold) {
                            contactZ = z;
                        }
                    }
                }
            }
            finally {
                // At contact the part may be held: let it go before lifting.
                valve.actuate(false);
                nozzle.getHead()
                      .actuatePumpRequest(nozzle, false);
                Thread.sleep(Math.max(settleMs, 300));
            }
            nozzle.moveToSafeZ();

            JsonObject o;
            if (contactZ == null) {
                o = error("No contact down to Z " + round(floorZ) + ". The tip may have missed "
                        + "the part, or the part is lower: check with the camera before probing "
                        + "deeper.");
            }
            else {
                o = ok();
                o.addProperty("contactZ", round(contactZ));
            }
            o.addProperty("feederId", feeder.getId());
            o.addProperty("feederName", feeder.getName());
            o.addProperty("partId", part.getId());
            o.addProperty("nozzleName", nozzle.getName());
            o.addProperty("nozzleTip", tip.getName());
            o.add("probeLocation", toJson(pick));
            o.addProperty("feederZBefore", round(expectedZ));
            o.addProperty("threshold", threshold);
            o.add("readings", readings);

            // Check that the part did not come up with the nozzle.
            nozzle.getHead()
                  .actuatePumpRequest(nozzle, true);
            valve.actuate(true);
            nozzle.waitForCompletion(CompletionType.WaitForStillstand);
            Thread.sleep(Math.max(settleMs, 300));
            double after = nozzle.readVacuumLevel();
            o.addProperty("vacuumLevelAfterLift", after);
            boolean stuck = after <= threshold;
            o.addProperty("partStuckOnNozzle", stuck);
            if (stuck) {
                // Keep the vacuum on, so that it does not fall somewhere: discard it.
                o.addProperty("warning", "A part seems to be on nozzle " + nozzle.getName()
                        + " (vacuum kept on). Discard it with discard_part.");
            }
            else {
                valve.actuate(false);
                nozzle.getHead()
                      .actuatePumpRequest(nozzle, false);
            }

            if (apply && contactZ != null) {
                ReferenceStripFeeder strip = (ReferenceStripFeeder) feeder;
                Location reference = strip.getReferenceHoleLocation()
                                          .convertToUnits(LengthUnit.Millimeters);
                Location last = strip.getLastHoleLocation()
                                     .convertToUnits(LengthUnit.Millimeters);
                strip.setReferenceHoleLocation(reference.derive(null, null, contactZ, null));
                strip.setLastHoleLocation(last.derive(null, null, contactZ, null));
                o.addProperty("applied", true);
            }
            else {
                o.addProperty("applied", false);
            }
            return o;
        });
    }

    /**
     * Enables or disables a feeder and/or sets its pick Z (strip feeders only). Does not move the
     * machine.
     */
    private JsonObject updateFeeder(JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        Feeder feeder = requireFeeder(machine, body);
        JsonElement enabledElement = body.get("enabled");
        Boolean enabled = enabledElement == null || enabledElement.isJsonNull() ? null
                : enabledElement.getAsBoolean();
        double zValue = getDouble(body, "z", Double.NaN);
        Double z = Double.isNaN(zValue) ? null : zValue;
        if (enabled == null && z == null) {
            throw new BadRequest("Give enabled and/or z.");
        }
        if (z != null && !(feeder instanceof ReferenceStripFeeder)) {
            throw new BadRequest("Setting z is only supported for ReferenceStripFeeder so far.");
        }
        JsonObject o = ok();
        o.addProperty("feederId", feeder.getId());
        o.addProperty("feederName", feeder.getName());
        o.addProperty("enabledBefore", feeder.isEnabled());
        if (feeder instanceof ReferenceStripFeeder) {
            Location reference = ((ReferenceStripFeeder) feeder).getReferenceHoleLocation()
                                                                .convertToUnits(
                                                                        LengthUnit.Millimeters);
            o.addProperty("zBefore", round(reference.getZ()));
        }
        onSwing(() -> {
            if (z != null) {
                ReferenceStripFeeder strip = (ReferenceStripFeeder) feeder;
                Location reference = strip.getReferenceHoleLocation()
                                          .convertToUnits(LengthUnit.Millimeters);
                Location last = strip.getLastHoleLocation()
                                     .convertToUnits(LengthUnit.Millimeters);
                strip.setReferenceHoleLocation(reference.derive(null, null, z, null));
                strip.setLastHoleLocation(last.derive(null, null, z, null));
            }
            if (enabled != null) {
                feeder.setEnabled(enabled);
            }
            MainFrame.get()
                     .getFeedersTab()
                     .updateView();
            return null;
        });
        o.addProperty("enabled", feeder.isEnabled());
        if (z != null) {
            o.addProperty("z", round(z));
        }
        logAction("feeder/update", body, o);
        return o;
    }

    /**
     * Runs the bottom vision of the part on a nozzle once, as the job processor would, but
     * without placing: the nozzle goes over the bottom camera and the part's vision pipeline is
     * processed. Returns what was found and the images of the pipeline, also when nothing was
     * found. The part stays on the nozzle.
     *
     * Body: {nozzleId (optional, default the nozzle holding a part), rotation (degrees, default
     * 0), threshold (optional, overrides the Threshold stages for this run only), confirm}
     */
    private JsonObject testBottomVision(JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        Nozzle nozzle = null;
        String nozzleId = getString(body, "nozzleId");
        if (nozzleId != null) {
            nozzle = findNozzle(machine, nozzleId);
            if (nozzle == null) {
                throw new BadRequest("No nozzle with id or name " + nozzleId);
            }
        }
        else {
            for (Head head : machine.getHeads()) {
                for (Nozzle n : head.getNozzles()) {
                    if (nozzle == null && n.getPart() != null) {
                        nozzle = n;
                    }
                }
            }
            if (nozzle == null) {
                throw new BadRequest("No nozzle holds a part. Pick one first, e.g. with "
                        + "pick_from_feeder and returnPart false.");
            }
        }
        Part part = nozzle.getPart();
        if (part == null) {
            throw new BadRequest("Nozzle " + nozzle.getName() + " holds no part.");
        }
        double rotation = getDouble(body, "rotation", 0);
        double thresholdValue = getDouble(body, "threshold", Double.NaN);
        if (!Double.isNaN(thresholdValue) && (thresholdValue < 0 || thresholdValue > 255)) {
            throw new BadRequest("threshold must be between 0 and 255.");
        }
        ReferenceBottomVision bottomVision = ReferenceBottomVision.getDefault();
        BottomVisionSettings settings = bottomVision.getInheritedVisionSettings(part);
        Camera camera = VisionUtils.getBottomVisionCamera();
        Nozzle visionNozzle = nozzle;

        JsonObject gate = gateMotion(machine, body, "Move nozzle " + nozzle.getName() + " with its "
                + part.getId() + " at Safe Z over the bottom camera " + camera.getName()
                + ", run the bottom vision \"" + settings.getName() + "\""
                + (Double.isNaN(thresholdValue) ? ""
                        : " with threshold " + (int) thresholdValue + " for this run only")
                + " and lift to Safe Z. The part stays on the nozzle, nothing is placed.");
        if (gate != null) {
            return gate;
        }

        return runLogged("vision/bottom/test", body, machine, () -> {
            Location wanted = bottomVision.getCameraLocationAtPartHeight(part, camera,
                    visionNozzle, rotation);
            JsonObject o = ok();
            o.addProperty("nozzleName", visionNozzle.getName());
            o.addProperty("partId", part.getId());
            o.addProperty("packageId", part.getPackage()
                                           .getId());
            o.addProperty("visionSettings", settings.getName());
            o.add("cameraLocationAtPartHeight", toJson(wanted));
            JsonArray images = new JsonArray();
            try (CvPipeline pipeline = settings.getPipeline()) {
                bottomVision.preparePipeline(pipeline, settings.getPipelineParameterAssignments(),
                        camera, part.getPackage(), visionNozzle, visionNozzle.getNozzleTip(),
                        wanted, wanted, settings);
                // The pipeline is the one of the settings, not a copy: undo the override, or
                // OpenPnP would save it.
                Map<Threshold, Integer> originals = new HashMap<>();
                try {
                    JsonArray thresholds = new JsonArray();
                    for (CvStage stage : pipeline.getStages()) {
                        if (stage instanceof Threshold && stage.isEnabled()) {
                            if (!Double.isNaN(thresholdValue)) {
                                originals.put((Threshold) stage,
                                        ((Threshold) stage).getThreshold());
                                ((Threshold) stage).setThreshold((int) thresholdValue);
                            }
                            thresholds.add(new JsonPrimitive(((Threshold) stage).getThreshold()));
                        }
                    }
                    o.add("thresholds", thresholds);
                    Location upp = camera.getUnitsPerPixelAtZ()
                                         .convertToUnits(LengthUnit.Millimeters);
                    JsonArray shots = new JsonArray();
                    int shotIndex = 0;
                    for (CvPipeline.PipelineShot shot : pipeline.getPipelineShots()) {
                        shot.apply();
                        pipeline.process();
                        JsonObject s = new JsonObject();
                        CvStage.Result result =
                                pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME);
                        Object model = result == null ? null : result.model;
                        s.addProperty("found", model instanceof RotatedRect);
                        if (model instanceof RotatedRect) {
                            RotatedRect rect = (RotatedRect) model;
                            Location offsets =
                                    VisionUtils.getPixelCenterOffsets(camera, rect.center.x,
                                            rect.center.y)
                                               .convertToUnits(LengthUnit.Millimeters);
                            s.addProperty("offsetX", round(offsets.getX()));
                            s.addProperty("offsetY", round(offsets.getY()));
                            s.addProperty("angle", round(VisionUtils.getPixelAngle(camera,
                                    rect.angle)));
                            s.addProperty("widthMm", round(rect.size.width * upp.getX()));
                            s.addProperty("heightMm", round(rect.size.height * upp.getY()));
                        }
                        shots.add(s);
                        for (CvStage stage : pipeline.getStages()) {
                            boolean source = stage instanceof ImageCapture;
                            boolean threshold = stage instanceof Threshold;
                            CvStage.Result r = pipeline.getResult(stage);
                            if ((source || threshold) && stage.isEnabled() && r != null
                                    && r.image != null) {
                                images.add(namedImage("shot" + shotIndex + "-" + stage.getName(),
                                        OpenCvUtils.toBufferedImage(r.image)));
                            }
                        }
                        images.add(namedImage("shot" + shotIndex + "-final",
                                OpenCvUtils.toBufferedImage(pipeline.getWorkingImage())));
                        shotIndex++;
                    }
                    o.add("shots", shots);
                    o.addProperty("mmPerPixel", upp.getX());
                }
                finally {
                    for (Map.Entry<Threshold, Integer> e : originals.entrySet()) {
                        e.getKey()
                         .setThreshold(e.getValue());
                    }
                }
            }
            o.add("images", images);
            o.addProperty("legend", "Images per shot: the camera image, the Threshold stage "
                    + "output (white = above the threshold) and the final image with the "
                    + "rectangle found, if any.");
            return o;
        });
    }

    private static JsonObject namedImage(String name, BufferedImage image) throws IOException {
        JsonObject i = new JsonObject();
        i.addProperty("name", name);
        i.addProperty("mimeType", "image/png");
        i.addProperty("data", toPngBase64(image, 800));
        return i;
    }

    /**
     * The vacuum level at or below which the tip counts as sealed: contactDrop below the level
     * in the air if given. Otherwise between the tip's part on and part off ranges, if they are
     * set and the level in the air is in the part off range (on some machines they are unused
     * defaults that do not fit the tip), else 10 below the level in the air.
     */
    private static double contactThreshold(ReferenceNozzleTip tip, double baseline,
            Double contactDrop) {
        if (contactDrop != null) {
            return baseline - contactDrop;
        }
        double onHigh = tip.getVacuumLevelPartOnHigh();
        double offLow = tip.getVacuumLevelPartOffLow();
        double offHigh = tip.getVacuumLevelPartOffHigh();
        if (onHigh > 0 && offLow > onHigh && baseline >= offLow && baseline <= offHigh) {
            return (onHigh + offLow) / 2;
        }
        return baseline - 10;
    }

    private static JsonObject reading(double z, double level) {
        JsonObject r = new JsonObject();
        r.addProperty("z", round(z));
        r.addProperty("level", level);
        return r;
    }

    /**
     * The nozzle given by nozzleId, or else the first one with a tip that fits the part.
     */
    private static ReferenceNozzle probeNozzle(Machine machine, JsonObject body, Part part)
            throws BadRequest {
        String nozzleId = getString(body, "nozzleId");
        Nozzle nozzle = null;
        if (nozzleId != null) {
            nozzle = findNozzle(machine, nozzleId);
            if (nozzle == null) {
                throw new BadRequest("No nozzle with id " + nozzleId);
            }
        }
        else if (part.getPackage() != null) {
            Set<NozzleTip> tips = part.getPackage()
                                      .getCompatibleNozzleTips();
            for (Head head : machine.getHeads()) {
                for (Nozzle n : head.getNozzles()) {
                    if (nozzle == null && n.getNozzleTip() != null
                            && tips.contains(n.getNozzleTip())) {
                        nozzle = n;
                    }
                }
            }
            if (nozzle == null) {
                throw new BadRequest("No nozzle has a tip that fits " + part.getId()
                        + ", give nozzleId.");
            }
        }
        else {
            throw new BadRequest("Part " + part.getId() + " has no package, give nozzleId.");
        }
        if (!(nozzle instanceof ReferenceNozzle)
                || !(nozzle.getNozzleTip() instanceof ReferenceNozzleTip)) {
            throw new BadRequest("Nozzle " + nozzle.getName() + " has no tip loaded or does not "
                    + "support vacuum sensing.");
        }
        return (ReferenceNozzle) nozzle;
    }

    /**
     * Where the feeder's next part is, without feeding. For a strip feeder that is the part after
     * the last one fed.
     */
    private static Location nextPickLocation(Feeder feeder) throws Exception {
        if (feeder instanceof ReferenceStripFeeder) {
            ReferenceStripFeeder strip = (ReferenceStripFeeder) feeder;
            int feedCount = strip.getFeedCount();
            if (feedCount == 0) {
                // Before the first feed the pick location is already that of the first part.
                return strip.getPickLocation();
            }
            strip.setFeedCount(feedCount + 1);
            try {
                return strip.getPickLocation();
            }
            finally {
                strip.setFeedCount(feedCount);
            }
        }
        return feeder.getPickLocation();
    }

    /**
     * Moves the top camera to a machine X, Y at Safe Z, e.g. back over a feeder after homing.
     *
     * Body: {x, y (mm), confirm}
     */
    private JsonObject moveCamera(JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        JsonElement x = body.get("x");
        JsonElement y = body.get("y");
        if (x == null || y == null || x.isJsonNull() || y.isJsonNull()) {
            throw new BadRequest("Give x and y in mm.");
        }
        Camera camera = machine.getDefaultHead()
                               .getDefaultCamera();
        Location target = camera.getLocation()
                                .convertToUnits(LengthUnit.Millimeters)
                                .derive(x.getAsDouble(), y.getAsDouble(), null, null);
        JsonObject gate = gateMotion(machine, body,
                "Lift all nozzles to Safe Z and move camera " + camera.getName() + " to X "
                        + round(target.getX()) + ", Y " + round(target.getY()) + ".");
        if (gate != null) {
            return gate;
        }
        return runLogged("camera/move", body, machine, () -> {
            MovableUtils.moveToLocationAtSafeZ(camera, target);
            JsonObject o = ok();
            o.add("cameraLocation", toJson(camera.getLocation()
                                                 .convertToUnits(LengthUnit.Millimeters)));
            return o;
        });
    }

    /**
     * Sets up a ReferenceStripFeeder for a cut tape strip from one photo where the camera is now,
     * without moving the machine: finds the sprocket holes and parts, and from those the
     * reference hole, last hole, feed direction and part pitch. Without create it only returns
     * the result and an annotated photo; with create it adds the feeder, disabled until its Z
     * has been checked.
     *
     * Body: {partId, tapeWidth (mm, default 8), z (mm, the tape surface; default the camera's
     * default Z), rotationInTape (degrees, default 0), name (optional), create (default
     * false)}
     */
    private JsonObject setupStripFeeder(JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        String partId = getString(body, "partId");
        Part part = partId == null ? null
                : Configuration.get()
                               .getPart(partId);
        if (part == null) {
            throw new BadRequest("No part " + partId + ".");
        }
        JsonElement widthElement = body.get("tapeWidth");
        double tapeWidth = widthElement == null || widthElement.isJsonNull() ? 8
                : widthElement.getAsDouble();
        Camera camera = machine.getDefaultHead()
                               .getDefaultCamera();
        JsonElement zElement = body.get("z");
        boolean zGiven = zElement != null && !zElement.isJsonNull();
        double z = zGiven ? zElement.getAsDouble()
                : camera.getDefaultZ()
                        .convertToUnits(LengthUnit.Millimeters)
                        .getValue();

        BufferedImage frame = runOnMachine(machine, () -> camera.lightSettleAndCapture());
        File captures = new File(bridgeDirectory, "captures");
        captures.mkdirs();
        ImageIO.write(frame, "png", new File(captures,
                new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + "-strip.png"));
        Location cameraLocation = camera.getLocation()
                                        .convertToUnits(LengthUnit.Millimeters);
        Location upp = camera.getUnitsPerPixel(new Length(z, LengthUnit.Millimeters))
                             .convertToUnits(LengthUnit.Millimeters);
        double cx = frame.getWidth() / 2.0;
        double cy = frame.getHeight() / 2.0;
        StripTapeLocator.PixelToMachine toMachine =
                (px, py) -> new org.opencv.core.Point(
                        cameraLocation.getX() + (px - cx) * upp.getX(),
                        cameraLocation.getY() - (py - cy) * upp.getY());
        Mat color = OpenCvUtils.toMat(frame);
        Mat gray = new Mat();
        StripTapeLocator.Result tape;
        try {
            Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY);
            tape = StripTapeLocator.locate(gray, tapeWidth, upp.getX(), toMachine);
        }
        catch (Exception e) {
            JsonObject o = error(describe(e));
            o.add("images", singleImage("capture", frame, MAX_BED_MAP_IMAGE_DIMENSION));
            return o;
        }
        finally {
            color.release();
            gray.release();
        }

        ReferenceStripFeeder feeder = new ReferenceStripFeeder();
        String name = getString(body, "name");
        feeder.setName(name == null ? part.getId() + " strip" : name);
        feeder.setPart(part);
        feeder.setTapeWidth(new Length(tapeWidth, LengthUnit.Millimeters));
        feeder.setPartPitch(new Length(tape.partPitch, LengthUnit.Millimeters));
        feeder.setReferenceHoleLocation(new Location(LengthUnit.Millimeters,
                tape.referenceHole.x, tape.referenceHole.y, z, 0));
        feeder.setLastHoleLocation(new Location(LengthUnit.Millimeters, tape.lastHole.x,
                tape.lastHole.y, z, 0));
        feeder.setFeedCount(0);
        feeder.setEnabled(false);
        // The feeder's own location only carries the rotation of the part in the tape.
        JsonElement rotationElement = body.get("rotationInTape");
        double rotationInTape = rotationElement == null || rotationElement.isJsonNull() ? 0
                : rotationElement.getAsDouble();
        feeder.setLocation(new Location(LengthUnit.Millimeters, 0, 0, 0, rotationInTape));

        JsonArray picks = new JsonArray();
        List<Location> pickLocations = new ArrayList<>();
        for (int n = 1; n <= tape.parts.size(); n++) {
            feeder.setFeedCount(n);
            Location pick = feeder.getPickLocation()
                                  .convertToUnits(LengthUnit.Millimeters);
            pickLocations.add(pick);
            JsonObject p = toJson(pick).getAsJsonObject();
            org.opencv.core.Point found = tape.parts.get(n - 1);
            p.addProperty("partFoundX", round(found.x));
            p.addProperty("partFoundY", round(found.y));
            p.addProperty("error", round(Math.hypot(pick.getX() - found.x,
                    pick.getY() - found.y)));
            picks.add(p);
        }
        feeder.setFeedCount(0);

        JsonObject o = ok();
        o.addProperty("partId", part.getId());
        o.addProperty("holesFound", tape.holes.size());
        o.addProperty("partsFound", tape.parts.size());
        o.add("referenceHole", toJson(feeder.getReferenceHoleLocation()));
        o.add("lastHole", toJson(feeder.getLastHoleLocation()));
        o.addProperty("feedDirectionDegrees",
                round(Math.toDegrees(Math.atan2(tape.direction.y, tape.direction.x))));
        o.addProperty("partPitch", tape.partPitch);
        o.addProperty("rotationInTape", rotationInTape);
        o.addProperty("tapeWidth", tapeWidth);
        o.add("pickLocations", picks);
        o.addProperty("z", z);
        if (!zGiven) {
            o.addProperty("zWarning", "Z was not given, the camera default Z was used for the "
                    + "scale and the pick height. Check it before enabling the feeder.");
        }
        o.addProperty("legend", "Holes green, reference hole red, line through the holes "
                + "yellow, parts found magenta, the feeder's pick locations cyan crosses with "
                + "their numbers.");
        o.add("images", singleImage("strip",
                drawStrip(frame, tape, pickLocations, cameraLocation, upp),
                MAX_BED_MAP_IMAGE_DIMENSION));

        if (getBoolean(body, "create", false)) {
            onSwing(() -> {
                machine.addFeeder(feeder);
                MainFrame.get()
                         .getFeedersTab()
                         .updateView();
                return null;
            });
            o.addProperty("created", true);
            o.addProperty("feederId", feeder.getId());
            o.addProperty("feederName", feeder.getName());
            o.addProperty("enabled", false);
            logAction("feeder/strip/setup", body, o);
        }
        else {
            o.addProperty("created", false);
            o.addProperty("message",
                    "Nothing was changed. Call again with create=true to add the feeder.");
        }
        return o;
    }

    private static JsonArray singleImage(String name, BufferedImage image, int maxDimension)
            throws IOException {
        JsonArray images = new JsonArray();
        JsonObject i = new JsonObject();
        i.addProperty("name", name);
        i.addProperty("mimeType", "image/png");
        i.addProperty("data", toPngBase64(image, maxDimension));
        images.add(i);
        return images;
    }

    private static BufferedImage drawStrip(BufferedImage frame, StripTapeLocator.Result tape,
            List<Location> picks, Location cameraLocation, Location upp) {
        BufferedImage out =
                new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(frame, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(4));
        double cx = frame.getWidth() / 2.0;
        double cy = frame.getHeight() / 2.0;
        java.util.function.Function<double[], Point2D> px =
                m -> new Point2D.Double(cx + (m[0] - cameraLocation.getX()) / upp.getX(),
                        cy - (m[1] - cameraLocation.getY()) / upp.getY());
        double holeRadius = StripTapeLocator.HOLE_DIAMETER / 2 / upp.getX();
        Point2D first = px.apply(new double[] {tape.holes.get(0).x, tape.holes.get(0).y});
        Point2D last = px.apply(new double[] {tape.holes.get(tape.holes.size() - 1).x,
                tape.holes.get(tape.holes.size() - 1).y});
        g.setColor(Color.yellow);
        g.draw(new Line2D.Double(first, last));
        for (org.opencv.core.Point h : tape.holes) {
            Point2D p = px.apply(new double[] {h.x, h.y});
            boolean reference = h == tape.referenceHole;
            g.setColor(reference ? Color.red : Color.green);
            double r = holeRadius * (reference ? 1.3 : 1.1);
            g.draw(new Ellipse2D.Double(p.getX() - r, p.getY() - r, 2 * r, 2 * r));
        }
        g.setColor(Color.magenta);
        for (org.opencv.core.Point part : tape.parts) {
            Point2D p = px.apply(new double[] {part.x, part.y});
            g.draw(new Ellipse2D.Double(p.getX() - 20, p.getY() - 20, 40, 40));
        }
        g.setColor(Color.cyan);
        g.setFont(g.getFont()
                   .deriveFont(48f));
        for (int i = 0; i < picks.size(); i++) {
            Point2D p = px.apply(new double[] {picks.get(i)
                                                    .getX(),
                    picks.get(i)
                         .getY()});
            g.draw(new Line2D.Double(p.getX() - 40, p.getY(), p.getX() + 40, p.getY()));
            g.draw(new Line2D.Double(p.getX(), p.getY() - 40, p.getX(), p.getY() + 40));
            g.drawString(String.valueOf(i + 1), (float) p.getX() + 50, (float) p.getY() - 20);
        }
        g.dispose();
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // Jobs
    // ---------------------------------------------------------------------------------------

    /**
     * Lists the boards and placements of a job file with their machine locations, without
     * loading it into the Job tab.
     *
     * Query: file (path of the .job.xml)
     */
    private JsonObject getJobPlacements(Map<String, String> query) throws Exception {
        Job job = loadJob(query.get("file"));
        JsonArray boards = new JsonArray();
        for (BoardLocation boardLocation : job.getBoardLocations()) {
            JsonObject b = new JsonObject();
            b.addProperty("boardId", boardLocation.getUniqueId());
            b.addProperty("enabled", boardLocation.isEnabled());
            b.addProperty("sideUp", boardLocation.getGlobalSide()
                                                 .toString());
            b.add("location", toJson(boardLocation.getGlobalLocation()));
            JsonArray placements = new JsonArray();
            for (Placement placement : boardLocation.getBoard()
                                                    .getPlacements()) {
                if (placement.getType() != Placement.Type.Placement) {
                    continue;
                }
                JsonObject p = new JsonObject();
                p.addProperty("placementId", placement.getId());
                p.addProperty("partId",
                        placement.getPart() == null ? null
                                : placement.getPart()
                                           .getId());
                p.addProperty("enabled", placement.isEnabled());
                p.addProperty("side", placement.getSide()
                                               .toString());
                p.addProperty("placed", job.retrievePlacedStatus(boardLocation, placement.getId()));
                p.add("location", toJson(
                        Utils2D.calculateBoardPlacementLocation(boardLocation, placement)));
                placements.add(p);
            }
            b.add("placements", placements);
            boards.add(b);
        }
        JsonObject o = ok();
        o.addProperty("file", job.getFile()
                                 .getAbsolutePath());
        o.add("boards", boards);
        return o;
    }

    /**
     * Places parts of a job file with OpenPnP's own job processor, as if only these placements
     * were enabled and not yet placed: picking, bottom vision, fiducials (where enabled) and
     * placing all work as in a normal job run. The job is loaded on its own and is neither shown
     * in the Job tab nor saved, so the placed status in the file does not change.
     *
     * Body: {file, placements: [{boardId, placementId}], confirm}. boardId is the board's unique
     * id such as "Pnl1⇒Brd1", or its last part "Brd1" if that is unique.
     */
    private JsonObject placeFromJob(JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        Job job = loadJob(getString(body, "file"));
        List<String> warnings = new ArrayList<>();
        Map<BoardLocation, List<Placement>> selected = selectPlacements(job, body, true, warnings);
        StringBuilder plan = new StringBuilder();
        for (Map.Entry<BoardLocation, List<Placement>> entry : selected.entrySet()) {
            for (Placement placement : entry.getValue()) {
                plan.append(describePlacement(machine, entry.getKey(), placement));
            }
        }
        boolean captureBefore = getBoolean(body, "captureBefore", true);
        Set<PlacementsHolderLocation<?>> fiducialLocations = fiducialLocations(selected);
        if (captureBefore) {
            plan.append("First the top camera takes a photo of each placement, for "
                    + "inspect_placements to compare with later. ");
        }
        plan.append("Uses the OpenPnP job processor (pick, bottom vision, place); "
                + "afterwards the head is parked.");

        JsonObject gate = gateMotion(machine, body, plan.toString());
        if (gate != null) {
            if (!warnings.isEmpty()) {
                gate.add("warnings", gson.toJsonTree(warnings));
            }
            return gate;
        }

        // Only the selected placements are left to do; everything else counts as placed, and
        // other boards are disabled so that their fiducials are not checked. This only changes
        // the job loaded here, never the board definitions or the file.
        for (BoardLocation boardLocation : job.getBoardLocations()) {
            if (!selected.containsKey(boardLocation)) {
                boardLocation.setLocallyEnabled(false);
            }
            for (Placement placement : boardLocation.getBoard()
                                                    .getPlacements()) {
                List<Placement> list = selected.get(boardLocation);
                job.storePlacedStatus(boardLocation, placement.getId(),
                        list == null || !list.contains(placement));
            }
        }
        // Stop at the first error instead of deferring it, so that it can be reported.
        job.setErrorHandling(Job.ErrorHandling.Alert);
        JsonElement maxFailures = body.get("maxPartFailures");
        int maxPartFailures = maxFailures == null || maxFailures.isJsonNull()
                ? DEFAULT_MAX_PART_FAILURES
                : Math.max(1, maxFailures.getAsInt());

        JsonObject result = runLogged("job/place", body, machine, JOB_TIMEOUT_S, () -> {
            int beforeFrames = 0;
            if (captureBefore) {
                Camera camera = machine.getDefaultHead()
                                       .getDefaultCamera();
                for (PlacementPhoto photo : photographPlacements(machine, camera, selected,
                        fiducialLocations, new JsonArray())) {
                    File file = beforeFrameFile(job, photo.boardLocation, photo.placement);
                    file.getParentFile()
                        .mkdirs();
                    ImageIO.write(photo.frame, "png", file);
                    beforeFrames++;
                }
            }
            JobProcessor processor = machine.getPnpJobProcessor();
            List<String> disabledFeeders = new ArrayList<>();
            List<String> rejectedParts = new ArrayList<>();
            int failuresInARow = 0;
            String error = null;
            int placedBefore = countPlaced(job, selected);
            while (true) {
                int placedNow = countPlaced(job, selected);
                if (placedNow > placedBefore) {
                    failuresInARow = 0;
                    placedBefore = placedNow;
                }
                processor.initialize(job);
                try {
                    while (processor.next()) {
                        // Each call runs one step of the job.
                    }
                    break;
                }
                catch (Exception e) {
                    try {
                        // Discards whatever was picked and parks the head.
                        processor.abort();
                    }
                    catch (Exception e2) {
                        Logger.warn(e2, "AI bridge could not abort the job");
                    }
                    error = describe(e);
                    Logger.warn(e, "AI bridge job/place step failed");
                    // A feeder that found no part has been disabled by the job processor. Run
                    // the rest of the placements again, which then use the next feeder, until
                    // the placements are done or no feeder is left.
                    Object source = e instanceof JobProcessorException
                            ? ((JobProcessorException) e).getSource()
                            : null;
                    if (source instanceof Feeder && !((Feeder) source).isEnabled()) {
                        disabledFeeders.add(((Feeder) source).getName());
                        if (hasFeedersLeft(machine, job, selected)) {
                            continue;
                        }
                    }
                    // A picked part that failed, e.g. bottom vision found it too far off, has
                    // been discarded by abort(). Pick the next one, unless that keeps failing,
                    // which points at a problem with the machine rather than the parts.
                    else if (source instanceof Part && nozzlesEmpty(machine)) {
                        rejectedParts.add(error);
                        failuresInARow++;
                        if (failuresInARow < maxPartFailures
                                && hasFeedersLeft(machine, job, selected)) {
                            continue;
                        }
                        if (failuresInARow >= maxPartFailures) {
                            error = maxPartFailures + " parts in a row failed, stopped. Last: "
                                    + error;
                        }
                    }
                    break;
                }
            }
            JsonArray placed = new JsonArray();
            int missing = 0;
            for (Map.Entry<BoardLocation, List<Placement>> entry : selected.entrySet()) {
                for (Placement placement : entry.getValue()) {
                    JsonObject p = new JsonObject();
                    p.addProperty("boardId", entry.getKey()
                                                  .getUniqueId());
                    p.addProperty("placementId", placement.getId());
                    boolean done = job.retrievePlacedStatus(entry.getKey(), placement.getId());
                    p.addProperty("placed", done);
                    placed.add(p);
                    missing += done ? 0 : 1;
                }
            }
            JsonObject o;
            if (missing == 0) {
                o = ok();
            }
            else {
                o = error(missing + " placement(s) were not placed. Last error: "
                        + (error == null ? "none, see the OpenPnP log" : error));
            }
            o.add("placements", placed);
            o.addProperty("beforePhotos", beforeFrames);
            o.add("feedersDisabled", gson.toJsonTree(disabledFeeders));
            o.add("partsRejected", gson.toJsonTree(rejectedParts));
            return o;
        });
        if (!"ok".equals(getString(result, "status")) && !result.has("nozzlesAfterFailure")) {
            result.add("nozzlesAfterFailure", getNozzles().get("nozzles"));
        }
        if (!warnings.isEmpty()) {
            result.add("warnings", gson.toJsonTree(warnings));
        }
        return result;
    }

    private static int countPlaced(Job job, Map<BoardLocation, List<Placement>> selected) {
        int n = 0;
        for (Map.Entry<BoardLocation, List<Placement>> entry : selected.entrySet()) {
            for (Placement placement : entry.getValue()) {
                n += job.retrievePlacedStatus(entry.getKey(), placement.getId()) ? 1 : 0;
            }
        }
        return n;
    }

    private static boolean nozzlesEmpty(Machine machine) {
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                if (nozzle.getPart() != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @return true if every placement still to do has an enabled feeder for its part.
     */
    private static boolean hasFeedersLeft(Machine machine, Job job,
            Map<BoardLocation, List<Placement>> selected) {
        for (Map.Entry<BoardLocation, List<Placement>> entry : selected.entrySet()) {
            for (Placement placement : entry.getValue()) {
                if (job.retrievePlacedStatus(entry.getKey(), placement.getId())) {
                    continue;
                }
                boolean found = false;
                for (Feeder feeder : machine.getFeeders()) {
                    if (feeder.isEnabled() && feeder.getPart() == placement.getPart()) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Moves the top camera over placements of a job file and returns a photo of each, with the
     * part's footprint drawn where the job expects it: body outline in green, pads in cyan and
     * pin 1 in red. Offsets, rotation, polarity and missing parts can then be judged by eye.
     *
     * Body: {file, placements: [{boardId, placementId}], confirm}
     */
    private JsonObject inspectPlacements(JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        Job job = loadJob(getString(body, "file"));
        Map<BoardLocation, List<Placement>> selected =
                selectPlacements(job, body, false, new ArrayList<>());
        Camera camera = machine.getDefaultHead()
                               .getDefaultCamera();
        String images = getString(body, "images");
        if (images == null) {
            images = "failures";
        }
        if (!images.equals("all") && !images.equals("failures") && !images.equals("none")) {
            throw new BadRequest("images must be all, failures or none.");
        }
        String imagesWanted = images;
        double tolerance = getDouble(body, "toleranceMm", DEFAULT_PLACEMENT_TOLERANCE_MM);
        double maxRotation = getDouble(body, "maxRotationDegrees", DEFAULT_MAX_ROTATION_ERROR);
        Set<PlacementsHolderLocation<?>> fiducialLocations = fiducialLocations(selected);
        StringBuilder plan = new StringBuilder("Move camera " + camera.getName() + " at Safe Z "
                + (fiducialLocations.isEmpty() ? ""
                        : "over the fiducials of " + fiducialLocations.size()
                                + " board(s)/panel(s), then ")
                + "over each placement and take a photo, no pick or place: ");
        for (Map.Entry<BoardLocation, List<Placement>> entry : selected.entrySet()) {
            for (Placement placement : entry.getValue()) {
                plan.append(entry.getKey()
                                 .getUniqueId())
                    .append(" ")
                    .append(placement.getId())
                    .append(" at ")
                    .append(format(
                            Utils2D.calculateBoardPlacementLocation(entry.getKey(), placement)))
                    .append("; ");
            }
        }
        JsonObject gate = gateMotion(machine, body, plan.toString());
        if (gate != null) {
            return gate;
        }

        return runLogged("job/inspect", body, machine, JOB_TIMEOUT_S, () -> {
            JsonArray fiducialsChecked = new JsonArray();
            List<PlacementPhoto> photos = photographPlacements(machine, camera, selected,
                    fiducialLocations, fiducialsChecked);
            JsonArray results = new JsonArray();
            JsonArray imageArray = new JsonArray();
            Map<String, Integer> verdicts = new LinkedHashMap<>();
            for (PlacementPhoto photo : photos) {
                File frameFile = saveInspectionFrame(photo.frame, photo.boardLocation,
                        photo.placement);
                String name = photo.boardLocation.getUniqueId() + " " + photo.placement.getId();
                JsonObject r = new JsonObject();
                r.addProperty("boardId", photo.boardLocation.getUniqueId());
                r.addProperty("placementId", photo.placement.getId());
                r.addProperty("partId", photo.placement.getPart()
                                                       .getId());
                r.add("expectedLocation", toJson(photo.location));
                File beforeFile = beforeFrameFile(job, photo.boardLocation, photo.placement);
                Measured measured = measurePlacement(photo, beforeFile, tolerance, maxRotation, r);
                String verdict = getString(r, "verdict");
                verdicts.merge(verdict, 1, Integer::sum);
                r.addProperty("frameFile", frameFile == null ? null
                        : frameFile.getAbsolutePath());
                if (imagesWanted.equals("all")
                        || (imagesWanted.equals("failures") && !verdict.equals("ok"))) {
                    r.addProperty("image", name);
                    JsonObject i = new JsonObject();
                    i.addProperty("name", name);
                    i.addProperty("mimeType", "image/png");
                    i.addProperty("data",
                            toPngBase64(drawExpectedFootprint(photo.frame, photo.placement,
                                    photo.location, photo.upp, measured.rect, measured.changed),
                                    MAX_IMAGE_DIMENSION));
                    imageArray.add(i);
                }
                if (measured.changed != null) {
                    measured.changed.release();
                }
                results.add(r);
            }
            JsonObject o = ok();
            o.add("fiducialsChecked", fiducialsChecked);
            o.add("verdicts", gson.toJsonTree(verdicts));
            o.add("placements", results);
            o.addProperty("legend", "verdict: ok, check (see reasons), missing (nothing changed "
                    + "since the photo before placing) or unverified (no photo before placing, "
                    + "judge from the image). Images (per the images option): drawn where the job "
                    + "expects the part, body outline green, pads cyan, pin 1 red dot, image "
                    + "center yellow cross; the part found magenta; what changed since the photo "
                    + "before placing tinted red. measured.offsetX/Y is the found minus the "
                    + "expected center in machine mm, rotationError in degrees "
                    + "counterclockwise.");
            o.add("images", imageArray);
            return o;
        });
    }

    /**
     * A photo of a placement, taken with the camera centered on its expected location.
     */
    private static class PlacementPhoto {
        final BoardLocation boardLocation;
        final Placement placement;
        final Location location;
        final BufferedImage frame;
        final Location upp;

        PlacementPhoto(BoardLocation boardLocation, Placement placement, Location location,
                BufferedImage frame, Location upp) {
            this.boardLocation = boardLocation;
            this.placement = placement;
            this.location = location;
            this.frame = frame;
            this.upp = upp;
        }
    }

    /**
     * Like the job processor, the boards and panels that check fiducials, outer panels before
     * what they contain, so that the expected locations match the real board.
     */
    private static Set<PlacementsHolderLocation<?>> fiducialLocations(
            Map<BoardLocation, List<Placement>> selected) {
        Set<PlacementsHolderLocation<?>> fiducialLocations = new LinkedHashSet<>();
        for (BoardLocation boardLocation : selected.keySet()) {
            List<PlacementsHolderLocation<?>> chain = new ArrayList<>();
            for (PlacementsHolderLocation<?> l = boardLocation; l != null; l = l.getParent()) {
                chain.add(0, l);
            }
            for (PlacementsHolderLocation<?> l : chain) {
                if (l.isCheckFiducials()) {
                    fiducialLocations.add(l);
                }
            }
        }
        return fiducialLocations;
    }

    /**
     * Locates the fiducials, then moves the camera over each placement and takes a photo. Runs on
     * the machine thread.
     */
    private static List<PlacementPhoto> photographPlacements(Machine machine, Camera camera,
            Map<BoardLocation, List<Placement>> selected,
            Set<PlacementsHolderLocation<?>> fiducialLocations, JsonArray fiducialsChecked)
            throws Exception {
        if (!fiducialLocations.isEmpty()) {
            machine.getFiducialLocator()
                   .locateAllPlacementsHolder(new ArrayList<>(fiducialLocations), null);
            for (PlacementsHolderLocation<?> l : fiducialLocations) {
                fiducialsChecked.add(new JsonPrimitive(l.getUniqueId()));
            }
        }
        List<PlacementPhoto> photos = new ArrayList<>();
        for (Map.Entry<BoardLocation, List<Placement>> entry : selected.entrySet()) {
            for (Placement placement : entry.getValue()) {
                Location location =
                        Utils2D.calculateBoardPlacementLocation(entry.getKey(), placement)
                               .convertToUnits(LengthUnit.Millimeters);
                // The camera Z only sets the viewing plane, for the right scale.
                MovableUtils.moveToLocationAtSafeZ(camera,
                        camera.getLocation()
                              .convertToUnits(LengthUnit.Millimeters)
                              .derive(location.getX(), location.getY(), location.getZ(), null));
                BufferedImage frame = camera.lightSettleAndCapture();
                Location upp = camera.getUnitsPerPixelAtZ()
                                     .convertToUnits(LengthUnit.Millimeters);
                photos.add(new PlacementPhoto(entry.getKey(), placement, location, frame, upp));
            }
        }
        return photos;
    }

    /**
     * Where the photo of a placement taken before placing is kept, per job file, board and
     * placement. A new one replaces the old.
     */
    private File beforeFrameFile(Job job, BoardLocation boardLocation, Placement placement) {
        File directory = new File(bridgeDirectory, "inspect/before");
        String jobKey = Integer.toHexString(job.getFile()
                                               .getAbsolutePath()
                                               .hashCode());
        return new File(directory, jobKey + "-" + (boardLocation.getUniqueId() + "-"
                + placement.getId()).replaceAll("[^A-Za-z0-9._-]", "_") + ".png");
    }

    /**
     * The part found in an inspection photo, for drawing.
     */
    private static class Measured {
        final RotatedRect rect;
        final Mat changed;

        Measured(RotatedRect rect, Mat changed) {
            this.rect = rect;
            this.changed = changed;
        }
    }

    /**
     * Measures where the part of a placement is and judges it: by comparing with the photo taken
     * before placing if there is one, else as a dark body (which suits IC bodies only). Adds
     * "measured", "verdict" and "reasons" to r.
     */
    private static Measured measurePlacement(PlacementPhoto photo, File beforeFile,
            double tolerance, double maxRotation, JsonObject r) throws IOException {
        BufferedImage before = beforeFile.exists() ? ImageIO.read(beforeFile) : null;
        if (before == null || before.getWidth() != photo.frame.getWidth()
                || before.getHeight() != photo.frame.getHeight()) {
            BodyLocator.Result body =
                    measureBody(photo.frame, photo.placement, photo.location, photo.upp, r);
            r.getAsJsonObject("measured")
             .addProperty("method", "body");
            r.addProperty("verdict", "unverified");
            JsonArray reasons = new JsonArray();
            reasons.add(new JsonPrimitive("No photo from before placing; the dark body search "
                    + "only suits IC bodies. Judge from the image."));
            r.add("reasons", reasons);
            return new Measured(body == null ? null : body.rect, null);
        }

        JsonObject m = new JsonObject();
        r.add("measured", m);
        m.addProperty("method", "diff");
        Package pkg = photo.placement.getPart()
                                     .getPackage();
        Footprint footprint = pkg == null ? null : pkg.getFootprint();
        double mm = footprint == null ? 1
                : new Length(1, footprint.getUnits()).convertToUnits(LengthUnit.Millimeters)
                                                     .getValue();
        boolean hasBody = footprint != null && footprint.getBodyWidth() > 0
                && footprint.getBodyHeight() > 0;
        // Without a body size, use the pads' extent.
        Rectangle2D extent = footprint == null ? new Rectangle2D.Double(-0.5, -0.5, 1, 1)
                : hasBody ? footprint.getBodyShape()
                                     .getBounds2D()
                        : footprint.getPadsShape()
                                   .getBounds2D();
        double length = Math.max(extent.getWidth(), extent.getHeight()) * mm;
        double width = Math.min(extent.getWidth(), extent.getHeight()) * mm;
        double expectedAxis =
                photo.location.getRotation() + (extent.getWidth() >= extent.getHeight() ? 0 : 90);
        double px = photo.upp.getX();
        Mat beforeMat = OpenCvUtils.toMat(before);
        Mat afterMat = OpenCvUtils.toMat(photo.frame);
        Mat beforeGray = new Mat();
        Mat afterGray = new Mat();
        PlacementDiff.Result d;
        try {
            toGray(beforeMat, beforeGray);
            toGray(afterMat, afterGray);
            org.opencv.core.Point center =
                    new org.opencv.core.Point(photo.frame.getWidth() / 2.0,
                            photo.frame.getHeight() / 2.0);
            d = PlacementDiff.measure(beforeGray, afterGray, center, length / px, width / px,
                    Math.max(PLACEMENT_SEARCH_MM, width) / px);
        }
        finally {
            beforeMat.release();
            afterMat.release();
            beforeGray.release();
            afterGray.release();
        }
        m.addProperty("cameraShiftX", round(d.shift.x * px));
        m.addProperty("cameraShiftY", round(-d.shift.y * photo.upp.getY()));
        m.addProperty("changedArea", round(d.changedArea * px * px));
        m.addProperty("otherChangedArea", round(d.otherChangedArea * px * px));
        JsonArray reasons = new JsonArray();
        String verdict;
        if (!d.isFound()) {
            m.addProperty("found", false);
            verdict = "missing";
            reasons.add(new JsonPrimitive("Hardly anything changed near the expected location "
                    + "since the photo before placing."));
        }
        else {
            // Image y grows downwards, machine y upwards.
            double offsetX = (d.center.x - photo.frame.getWidth() / 2.0) * px;
            double offsetY = -(d.center.y - photo.frame.getHeight() / 2.0) * photo.upp.getY();
            double rotationError = BodyLocator.normalizeHalfTurn(d.angle - expectedAxis);
            double offset = Math.hypot(offsetX, offsetY);
            m.addProperty("found", true);
            m.addProperty("offsetX", round(offsetX));
            m.addProperty("offsetY", round(offsetY));
            m.addProperty("offset", round(offset));
            m.addProperty("rotationError", Math.round(rotationError * 10) / 10.0);
            m.addProperty("length", round(d.length * px));
            m.addProperty("width", round(d.width * px));
            m.addProperty("expectedLength", round(length));
            m.addProperty("expectedWidth", round(width));
            if (offset > tolerance) {
                reasons.add(new JsonPrimitive("Offset " + round(offset)
                        + " mm is over the tolerance of " + round(tolerance) + " mm."));
            }
            // A square part has no long side to tell its rotation from.
            if (length > width * 1.2 && Math.abs(rotationError) > maxRotation) {
                reasons.add(new JsonPrimitive("Rotation error " + round(rotationError)
                        + " degrees is over " + round(maxRotation) + "."));
            }
            double lengthRatio = d.length * px / length;
            double widthRatio = d.width * px / width;
            if (lengthRatio < 0.6 || lengthRatio > 1.6 || widthRatio < 0.5
                    || widthRatio > 2.0) {
                reasons.add(new JsonPrimitive("The changed area of " + round(d.length * px)
                        + " x " + round(d.width * px) + " mm does not match the part's "
                        + round(length) + " x " + round(width)
                        + " mm: wrong part, tombstoned, or something else moved near it."));
            }
            verdict = reasons.size() == 0 ? "ok" : "check";
        }
        if (d.otherChangedArea * px * px > Math.max(0.3 * length * width, 0.1)) {
            reasons.add(new JsonPrimitive("Something else in the photo changed too ("
                    + round(d.otherChangedArea * px * px) + " mm2), e.g. a neighbor that was "
                    + "pushed away."));
            if (verdict.equals("ok")) {
                verdict = "check";
            }
        }
        r.addProperty("verdict", verdict);
        r.add("reasons", reasons);
        return new Measured(d.rect, d.mask);
    }

    private static void toGray(Mat color, Mat gray) {
        if (color.channels() == 1) {
            color.copyTo(gray);
        }
        else {
            Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY);
        }
    }

    /**
     * Draws the footprint of the placement's part where it is expected, on a copy of a frame
     * taken with the camera centered on the expected location, and crops it to the part with
     * some margin.
     */
    /**
     * Keeps the original frame of an inspection, without drawings, for checking or tuning the
     * measurement later.
     *
     * @return the file, or null if it could not be written.
     */
    private File saveInspectionFrame(BufferedImage frame, BoardLocation boardLocation,
            Placement placement) {
        File directory = new File(bridgeDirectory, "inspect");
        directory.mkdirs();
        String name = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + "-"
                + (boardLocation.getUniqueId() + "-" + placement.getId()).replaceAll(
                        "[^A-Za-z0-9._-]", "_")
                + ".png";
        File file = new File(directory, name);
        try {
            ImageIO.write(frame, "png", file);
            return file;
        }
        catch (IOException e) {
            Logger.warn(e, "AI bridge could not save inspection frame {}", file);
            return null;
        }
    }

    /**
     * Looks for the part body near the expected location, as a dark rectangle of the footprint's
     * body size, and adds the measured offset and rotation error to r as "measured".
     *
     * @return the body found, or null.
     */
    private static BodyLocator.Result measureBody(BufferedImage frame, Placement placement,
            Location location, Location upp, JsonObject r) {
        JsonObject m = new JsonObject();
        r.add("measured", m);
        Part part = placement.getPart();
        Footprint footprint = part.getPackage() == null ? null
                : part.getPackage()
                      .getFootprint();
        if (footprint == null || footprint.getBodyWidth() <= 0
                || footprint.getBodyHeight() <= 0) {
            m.addProperty("found", false);
            m.addProperty("reason", "The package footprint has no body size.");
            return null;
        }
        double mm = new Length(1, footprint.getUnits()).convertToUnits(LengthUnit.Millimeters)
                                                       .getValue();
        double bodyWidth = footprint.getBodyWidth() * mm;
        double bodyHeight = footprint.getBodyHeight() * mm;
        double expectedAxis = location.getRotation() + (bodyWidth >= bodyHeight ? 0 : 90);
        Mat color = OpenCvUtils.toMat(frame);
        Mat gray = new Mat();
        BodyLocator.Result body;
        try {
            if (color.channels() == 1) {
                color.copyTo(gray);
            }
            else {
                Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY);
            }
            body = BodyLocator.locate(gray,
                    new org.opencv.core.Point(frame.getWidth() / 2.0, frame.getHeight() / 2.0),
                    expectedAxis, Math.max(bodyWidth, bodyHeight) / upp.getX(),
                    Math.min(bodyWidth, bodyHeight) / upp.getX());
        }
        finally {
            color.release();
            gray.release();
        }
        if (body == null) {
            m.addProperty("found", false);
            m.addProperty("reason", "No body of " + round(Math.max(bodyWidth, bodyHeight))
                    + " x " + round(Math.min(bodyWidth, bodyHeight))
                    + " mm darker than its surroundings near the expected location. The part may "
                    + "be missing, far off, or not dark on a lighter board.");
            return null;
        }
        // Image y grows downwards, machine y upwards.
        double offsetX = (body.center.x - frame.getWidth() / 2.0) * upp.getX();
        double offsetY = -(body.center.y - frame.getHeight() / 2.0) * upp.getY();
        m.addProperty("found", true);
        m.addProperty("offsetX", round(offsetX));
        m.addProperty("offsetY", round(offsetY));
        m.addProperty("offset", round(Math.hypot(offsetX, offsetY)));
        m.addProperty("rotationError",
                Math.round(BodyLocator.normalizeHalfTurn(body.angle - expectedAxis) * 100)
                        / 100.0);
        m.addProperty("contrast", Math.round(body.contrast));
        m.addProperty("note", "The body outline cannot tell pin 1 from the opposite end, check "
                + "polarity in the image.");
        return body;
    }

    /**
     * @param measured the part found, drawn in magenta, or null.
     * @param changed pixels that changed since the photo before placing (255), tinted red, or
     *        null.
     */
    private static BufferedImage drawExpectedFootprint(BufferedImage frame, Placement placement,
            Location location, Location upp, RotatedRect measured, Mat changed) {
        Part part = placement.getPart();
        Footprint footprint =
                part.getPackage() == null ? null : part.getPackage()
                                                       .getFootprint();
        double cx = frame.getWidth() / 2.0;
        double cy = frame.getHeight() / 2.0;
        BufferedImage out =
                new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(frame, 0, 0, null);
        if (changed != null) {
            byte[] mask = new byte[changed.cols() * changed.rows()];
            changed.get(0, 0, mask);
            for (int y = 0; y < out.getHeight(); y++) {
                for (int x = 0; x < out.getWidth(); x++) {
                    if (mask[y * changed.cols() + x] != 0) {
                        int rgb = out.getRGB(x, y);
                        int r = ((rgb >> 16) & 0xff) / 2 + 128;
                        int gr = ((rgb >> 8) & 0xff) / 2;
                        int b = (rgb & 0xff) / 2;
                        out.setRGB(x, y, (r << 16) | (gr << 8) | b);
                    }
                }
            }
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Rectangle2D extent = new Rectangle2D.Double(cx - 1 / upp.getX(), cy - 1 / upp.getY(),
                2 / upp.getX(), 2 / upp.getY());
        if (footprint != null) {
            // Footprint units -> mm, rotated as placed -> image pixels around the image center,
            // which is where the camera, and so the expected part center, is.
            double mm = new Length(1, footprint.getUnits()).convertToUnits(LengthUnit.Millimeters)
                                                           .getValue();
            AffineTransform tx = new AffineTransform();
            tx.translate(cx, cy);
            tx.scale(1 / upp.getX(), -1 / upp.getY());
            tx.rotate(Math.toRadians(location.getRotation()));
            tx.scale(mm, mm);
            Shape bodyShape = tx.createTransformedShape(footprint.getBodyShape());
            Shape padsShape = tx.createTransformedShape(footprint.getPadsShape());
            extent = bodyShape.getBounds2D()
                              .createUnion(padsShape.getBounds2D());
            float stroke =
                    (float) Math.max(2, Math.max(extent.getWidth(), extent.getHeight()) / 300);
            g.setStroke(new BasicStroke(stroke));
            g.setColor(new Color(0, 255, 255, 200));
            g.draw(padsShape);
            g.setColor(new Color(0, 255, 0, 220));
            g.draw(bodyShape);
            for (Footprint.Pad pad : footprint.getPads()) {
                if ("1".equals(pad.getName())) {
                    Point2D p = tx.transform(new Point2D.Double(pad.getX(), pad.getY()), null);
                    double r = stroke * 4;
                    g.setColor(new Color(255, 0, 0, 220));
                    g.fill(new Ellipse2D.Double(p.getX() - r, p.getY() - r, 2 * r, 2 * r));
                }
            }
        }
        double arm = Math.max(extent.getWidth(), extent.getHeight()) / 15;
        g.setStroke(new BasicStroke((float) Math.max(2, arm / 10)));
        g.setColor(new Color(255, 255, 0, 220));
        g.draw(new Line2D.Double(cx - arm, cy, cx + arm, cy));
        g.draw(new Line2D.Double(cx, cy - arm, cx, cy + arm));
        if (measured != null) {
            org.opencv.core.Point[] p = new org.opencv.core.Point[4];
            measured.points(p);
            java.awt.geom.Path2D.Double outline = new java.awt.geom.Path2D.Double();
            outline.moveTo(p[0].x, p[0].y);
            for (int k = 1; k < 4; k++) {
                outline.lineTo(p[k].x, p[k].y);
            }
            outline.closePath();
            g.setColor(new Color(255, 0, 255, 220));
            g.draw(outline);
            g.fill(new Ellipse2D.Double(measured.center.x - arm / 6, measured.center.y - arm / 6,
                    arm / 3, arm / 3));
        }
        g.dispose();

        // Crop around the part, with a margin of half its size, but at least 1.5 mm.
        double margin =
                Math.max(Math.max(extent.getWidth(), extent.getHeight()) / 2, 1.5 / upp.getX());
        Rectangle crop = new Rectangle((int) Math.floor(extent.getMinX() - margin),
                (int) Math.floor(extent.getMinY() - margin),
                (int) Math.ceil(extent.getWidth() + 2 * margin),
                (int) Math.ceil(extent.getHeight() + 2 * margin))
                        .intersection(new Rectangle(0, 0, frame.getWidth(), frame.getHeight()));
        BufferedImage cropped = out.getSubimage(crop.x, crop.y, crop.width, crop.height);
        // Small parts on a low resolution camera give tiny crops, enlarge them to be readable.
        double enlarge = (double) MIN_INSPECT_IMAGE_DIMENSION / Math.max(crop.width, crop.height);
        if (enlarge <= 1) {
            return cropped;
        }
        int w = (int) Math.round(crop.width * enlarge);
        int h = (int) Math.round(crop.height * enlarge);
        BufferedImage enlarged = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = enlarged.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(cropped, 0, 0, w, h, null);
        g2.dispose();
        return enlarged;
    }

    /**
     * Reads the placements: [{boardId, placementId}] of a request.
     *
     * @param forPlacing also require the placements to be enabled, and warn about those marked
     *        as placed.
     */
    private static Map<BoardLocation, List<Placement>> selectPlacements(Job job, JsonObject body,
            boolean forPlacing, List<String> warnings) throws BadRequest {
        JsonElement wanted = body.get("placements");
        if (wanted == null || !wanted.isJsonArray() || wanted.getAsJsonArray()
                                                             .size() == 0) {
            throw new BadRequest("Give placements: [{boardId, placementId}, ...].");
        }
        Map<BoardLocation, List<Placement>> selected = new LinkedHashMap<>();
        for (JsonElement e : wanted.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                throw new BadRequest("Each placement must be {boardId, placementId}.");
            }
            BoardLocation boardLocation =
                    findBoardLocation(job, getString(e.getAsJsonObject(), "boardId"));
            String placementId = getString(e.getAsJsonObject(), "placementId");
            Placement placement = boardLocation.getBoard()
                                               .getPlacements()
                                               .stream()
                                               .filter(p -> p.getId()
                                                             .equals(placementId))
                                               .findFirst()
                                               .orElseThrow(() -> new BadRequest(
                                                       "Board " + boardLocation.getUniqueId()
                                                               + " has no placement "
                                                               + placementId + "."));
            if (placement.getType() != Placement.Type.Placement || placement.getPart() == null) {
                throw new BadRequest("Placement " + placementId + " is not a part placement.");
            }
            if (forPlacing && (!placement.isEnabled() || !boardLocation.isEnabled())) {
                throw new BadRequest("Placement " + placementId + " on "
                        + boardLocation.getUniqueId() + " is disabled in the job.");
            }
            if (placement.getSide() != boardLocation.getGlobalSide()) {
                // The job processor silently skips these, and the top camera cannot see them.
                throw new BadRequest("Placement " + placementId + " is on the "
                        + placement.getSide() + " side, but " + boardLocation.getUniqueId()
                        + " has its " + boardLocation.getGlobalSide() + " side up.");
            }
            if (forPlacing && job.retrievePlacedStatus(boardLocation, placementId)) {
                warnings.add(boardLocation.getUniqueId() + " " + placementId
                        + " is marked as placed in the job file. Make sure the board has no part "
                        + "there yet.");
            }
            selected.computeIfAbsent(boardLocation, k -> new ArrayList<>())
                    .add(placement);
        }
        return selected;
    }

    private String describePlacement(Machine machine, BoardLocation boardLocation,
            Placement placement) {
        Part part = placement.getPart();
        StringBuilder nozzles = new StringBuilder();
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                if (nozzle.getNozzleTip() != null && part.getPackage() != null
                        && part.getPackage()
                               .getCompatibleNozzleTips()
                               .contains(nozzle.getNozzleTip())) {
                    nozzles.append(nozzles.length() == 0 ? "" : ", ")
                           .append(nozzle.getName())
                           .append(" (")
                           .append(nozzle.getNozzleTip()
                                         .getName())
                           .append(")");
                }
            }
        }
        StringBuilder feeders = new StringBuilder();
        for (Feeder feeder : machine.getFeeders()) {
            if (feeder.isEnabled() && feeder.getPart() == part) {
                feeders.append(feeders.length() == 0 ? "" : ", ")
                       .append(feeder.getName());
            }
        }
        return String.format("Place %s (%s) on %s at %s. Nozzles with a fitting tip: %s. "
                + "Enabled feeders with this part: %s. ", placement.getId(), part.getId(),
                boardLocation.getUniqueId(),
                format(Utils2D.calculateBoardPlacementLocation(boardLocation, placement)),
                nozzles.length() == 0 ? "none, the job would need a nozzle tip change" : nozzles,
                feeders.length() == 0 ? "none" : feeders);
    }

    private static Job loadJob(String file) throws Exception {
        if (file == null || file.trim()
                                .isEmpty()) {
            throw new BadRequest("Give the job file.");
        }
        File f = new File(file);
        if (!f.isFile()) {
            throw new BadRequest("No job file " + f.getAbsolutePath() + ".");
        }
        return Configuration.get()
                            .loadJob(f);
    }

    private static BoardLocation findBoardLocation(Job job, String boardId) throws BadRequest {
        if (boardId == null) {
            throw new BadRequest("Give boardId.");
        }
        List<BoardLocation> matches = new ArrayList<>();
        for (BoardLocation boardLocation : job.getBoardLocations()) {
            String id = boardLocation.getUniqueId();
            if (id.equals(boardId)) {
                return boardLocation;
            }
            if (id.endsWith(PlacementsHolderLocation.ID_DELIMITTER + boardId)
                    || boardLocation.getId()
                                    .equals(boardId)) {
                matches.add(boardLocation);
            }
        }
        if (matches.size() != 1) {
            throw new BadRequest((matches.isEmpty() ? "No board " : "More than one board ")
                    + boardId + " in the job. Use the full boardId from list_job_placements.");
        }
        return matches.get(0);
    }

    // ---------------------------------------------------------------------------------------
    // Bed map
    // ---------------------------------------------------------------------------------------

    /**
     * Starts scanning the bed map in the background and returns at once, since a full scan takes
     * longer than a request may. Poll GET /bedmap/scan for progress.
     *
     * Body: {confirm, region (optional): {xMin, yMin, xMax, yMax} in mm}. Without a region the
     * whole map is scanned again.
     */
    private JsonObject scanBedMap(JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        if (isBedMapScanRunning()) {
            throw new BadRequest("A bed map scan is already running.");
        }
        Camera camera = machine.getDefaultHead()
                               .getDefaultCamera();
        BedMapScanner scanner =
                new BedMapScanner(machine, camera, BedMapScanner.getDefaultDirectory());
        Rectangle2D region = getRegion(body.get("region"));
        BedMapScanner.Plan plan = scanner.plan(region);

        JsonObject gate = gateMotion(machine, body,
                String.format(
                        "Move camera %s at Safe Z over %d x %d = %d positions covering %s, "
                                + "capture a frame at each and stitch them into the bed map (%s).",
                        camera.getName(), plan.columns, plan.rows, plan.tiles.size(),
                        format(plan.region),
                        plan.full ? "replaces the whole map" : "updates this region of the map"));
        if (gate != null) {
            return gate;
        }

        JsonObject state = ok();
        state.addProperty("running", true);
        state.addProperty("done", 0);
        state.addProperty("total", plan.tiles.size());
        state.add("region", toJson(plan.region));
        state.addProperty("started", timestamp());
        synchronized (this) {
            bedMapScanState = state;
            bedMapScanCancel.set(false);
        }
        logAction("bedmap/scan", body, state);

        Thread thread = new Thread(() -> runBedMapScan(machine, scanner, plan, body),
                "AiBridgeBedMapScan");
        thread.setDaemon(true);
        thread.start();

        JsonObject o = ok();
        o.addProperty("started", true);
        o.addProperty("total", plan.tiles.size());
        o.add("region", toJson(plan.region));
        o.addProperty("message", "Scanning in the background. Poll the scan state for progress.");
        return o;
    }

    private void runBedMapScan(Machine machine, BedMapScanner scanner, BedMapScanner.Plan plan,
            JsonObject body) {
        JsonObject result;
        try {
            BedMap map = scanner.scan(plan, (m, done, total) -> {
                synchronized (this) {
                    bedMapScanState.addProperty("done", done);
                }
            }, bedMapScanCancel);
            result = ok();
            result.addProperty("cancelled", bedMapScanCancel.get());
            result.add("mapArea", toJson(map.getBounds()));
        }
        catch (Exception e) {
            Logger.warn(e, "AI bridge bed map scan failed");
            result = error(describe(e));
            try {
                result.add("nozzlesAfterFailure", getNozzles().get("nozzles"));
            }
            catch (Exception e2) {
                Logger.warn(e2, "AI bridge could not read nozzles");
            }
        }
        result.add("safeZAfter", raiseToSafeZ(machine));
        synchronized (this) {
            JsonObject state = bedMapScanState;
            for (Map.Entry<String, JsonElement> e : result.entrySet()) {
                state.add(e.getKey(), e.getValue());
            }
            state.addProperty("running", false);
            state.addProperty("finished", timestamp());
        }
        logAction("bedmap/scan/finished", body, result);
    }

    private synchronized boolean isBedMapScanRunning() {
        return bedMapScanState != null && bedMapScanState.get("running")
                                                         .getAsBoolean();
    }

    private synchronized JsonObject getBedMapScanState() {
        if (bedMapScanState == null) {
            JsonObject o = ok();
            o.addProperty("running", false);
            o.addProperty("message", "No bed map scan since OpenPnP started.");
            return o;
        }
        // A copy, since the scan thread keeps changing it. This Gson has no public deepCopy().
        return new JsonParser().parse(bedMapScanState.toString())
                               .getAsJsonObject();
    }

    private JsonObject cancelBedMapScan() {
        JsonObject o = ok();
        o.addProperty("wasRunning", isBedMapScanRunning());
        bedMapScanCancel.set(true);
        o.addProperty("message",
                "The scan stops after the current frame. What was scanned so far is kept.");
        return o;
    }

    /**
     * Returns the bed map, or a part of it, as an image together with what is needed to convert
     * image pixels to machine coordinates.
     *
     * Query: xMin, yMin, xMax, yMax (optional, mm), maxSize (optional, longest image side in
     * pixels).
     */
    private JsonObject getBedMap(Map<String, String> query) throws Exception {
        BedMap map = BedMap.load(BedMapScanner.getDefaultDirectory());
        JsonObject o = ok();
        if (map == null) {
            o.addProperty("exists", false);
            o.addProperty("message", "There is no bed map yet. Scan one first.");
            return o;
        }
        Rectangle2D area = map.getBounds();
        if (query.containsKey("xMin") || query.containsKey("yMin") || query.containsKey("xMax")
                || query.containsKey("yMax")) {
            JsonObject region = new JsonObject();
            for (String key : new String[] {"xMin", "yMin", "xMax", "yMax"}) {
                if (query.get(key) == null) {
                    throw new BadRequest("Give all of xMin, yMin, xMax and yMax, or none.");
                }
                region.addProperty(key, Double.parseDouble(query.get(key)));
            }
            area = getRegion(region);
        }
        int maxSize = MAX_BED_MAP_IMAGE_DIMENSION;
        if (query.get("maxSize") != null) {
            maxSize = Math.max(100,
                    Math.min(MAX_BED_MAP_IMAGE_DIMENSION, Integer.parseInt(query.get("maxSize"))));
        }
        Double z = query.get("z") == null ? null : Double.parseDouble(query.get("z"));
        Double mmPerPixel = query.get("mmPerPixel") == null ? null
                : Double.parseDouble(query.get("mmPerPixel"));
        // Small areas are stitched from the original frames, which have more detail than the map.
        double fitting = Math.max(area.getWidth(), area.getHeight()) / maxSize;
        if (z != null || mmPerPixel != null || fitting < map.getMmPerPixel()) {
            JsonObject detail = getBedMapDetail(map, area, fitting, mmPerPixel, z);
            if (detail != null) {
                return detail;
            }
        }
        Rectangle pixels = map.toPixelRectangle(area);
        BufferedImage crop = map.crop(area);
        if (crop == null) {
            throw new BadRequest("The area does not overlap the bed map " + format(map.getBounds())
                    + ".");
        }
        BedMap.Info info = map.getInfo();
        // The area the image actually shows, after snapping to whole map pixels.
        Point2D topLeft = map.toMachine(pixels.x, pixels.y);
        Point2D bottomRight = map.toMachine(pixels.x + pixels.width, pixels.y + pixels.height);
        Rectangle2D shown = new Rectangle2D.Double(topLeft.getX(), bottomRight.getY(),
                bottomRight.getX() - topLeft.getX(), topLeft.getY() - bottomRight.getY());
        double scale = Math.min(1.0, (double) maxSize / Math.max(crop.getWidth(), crop.getHeight()));
        int width = (int) Math.round(crop.getWidth() * scale);

        return bedMapImage(map, shown, shown.getWidth() / width, "map", info.viewingPlaneZ, crop,
                maxSize);
    }

    /**
     * Stitches an area anew from the original frames of the bed map, or returns null if no stored
     * frame covers it.
     *
     * @param fitting the mm per pixel at which the area fits into the largest image allowed.
     * @param mmPerPixel the wanted resolution, or null for the finest that fits.
     * @param z the Z of the plane to stitch for, or null for the plane of the map.
     */
    private JsonObject getBedMapDetail(BedMap map, Rectangle2D area, double fitting,
            Double mmPerPixel, Double z) throws Exception {
        BedMap.Info info = map.getInfo();
        List<BedMapScanner.TileRecord> records =
                BedMapScanner.loadTileRecords(map.getDirectory());
        double finest = Double.MAX_VALUE;
        for (BedMapScanner.TileRecord r : records) {
            finest = Math.min(finest, Math.min(r.mmPerPixelX, r.mmPerPixelY));
        }
        if (records.isEmpty()) {
            return null;
        }
        double resolution = Math.max(Math.max(fitting, finest),
                mmPerPixel == null ? 0 : mmPerPixel);
        Camera camera = null;
        if (z != null) {
            camera = Configuration.get()
                                  .getMachine()
                                  .getAllCameras()
                                  .stream()
                                  .filter(c -> c.getId()
                                                .equals(info.cameraId))
                                  .findFirst()
                                  .orElseThrow(() -> new BadRequest("Camera " + info.cameraName
                                          + " that scanned the bed map no longer exists."));
        }
        BufferedImage image =
                BedMapScanner.render(map.getDirectory(), area, resolution, camera, z);
        if (image == null) {
            return null;
        }
        Rectangle2D shown = new Rectangle2D.Double(area.getMinX(),
                area.getMaxY() - image.getHeight() * resolution, image.getWidth() * resolution,
                image.getHeight() * resolution);
        return bedMapImage(map, shown, resolution, "frames",
                z == null ? info.viewingPlaneZ : z, image, MAX_BED_MAP_IMAGE_DIMENSION);
    }

    private JsonObject bedMapImage(BedMap map, Rectangle2D shown, double mmPerPixel,
            String source, double viewingPlaneZ, BufferedImage image, int maxSize)
            throws IOException {
        BedMap.Info info = map.getInfo();
        JsonObject o = ok();
        o.addProperty("exists", true);
        o.add("mapArea", toJson(map.getBounds()));
        o.add("imageArea", toJson(shown));
        // More digits than other lengths, since it is multiplied by up to 1600 pixels.
        o.addProperty("imageMmPerPixel", Math.round(mmPerPixel * 1e7) / 1e7);
        o.addProperty("coordinates",
                "Image pixel (px, py) is at machine x = imageArea.xMin + px * imageMmPerPixel, "
                        + "y = imageArea.yMax - py * imageMmPerPixel. Up in the image is +y.");
        o.addProperty("source", source);
        o.addProperty("viewingPlaneZ", round(viewingPlaneZ));
        o.addProperty("cameraName", info.cameraName);
        o.addProperty("updated", info.updated == null ? null
                : new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(info.updated));
        JsonArray images = new JsonArray();
        JsonObject i = new JsonObject();
        i.addProperty("name", "bedmap");
        i.addProperty("mimeType", "image/png");
        i.addProperty("data", toPngBase64(image, maxSize));
        images.add(i);
        o.add("images", images);
        return o;
    }

    /**
     * @return the region {xMin, yMin, xMax, yMax} in mm, or null if e is missing.
     */
    private static Rectangle2D getRegion(JsonElement e) throws BadRequest {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonObject()) {
            throw new BadRequest("region must be an object {xMin, yMin, xMax, yMax} in mm.");
        }
        JsonObject r = e.getAsJsonObject();
        double[] v = new double[4];
        String[] keys = {"xMin", "yMin", "xMax", "yMax"};
        for (int k = 0; k < keys.length; k++) {
            JsonElement value = r.get(keys[k]);
            if (value == null || value.isJsonNull()) {
                throw new BadRequest("region needs xMin, yMin, xMax and yMax in mm.");
            }
            v[k] = value.getAsDouble();
        }
        if (v[2] <= v[0] || v[3] <= v[1]) {
            throw new BadRequest("region needs xMax > xMin and yMax > yMin.");
        }
        return new Rectangle2D.Double(v[0], v[1], v[2] - v[0], v[3] - v[1]);
    }

    // ---------------------------------------------------------------------------------------
    // Safety
    // ---------------------------------------------------------------------------------------

    /**
     * @return null if motion may proceed, otherwise the response to send instead.
     */
    private JsonObject gateMotion(Machine machine, JsonObject body, String plan) throws Exception {
        return gate(motionRefusal(machine), body, plan);
    }

    /**
     * @return null if the operation may proceed, otherwise the response to send instead.
     */
    private static JsonObject gate(String refusal, JsonObject body, String plan) {
        if (refusal != null) {
            JsonObject o = new JsonObject();
            o.addProperty("status", "refused");
            o.addProperty("reason", refusal);
            o.addProperty("plan", plan);
            return o;
        }
        if (!getBoolean(body, "confirm", false)) {
            JsonObject o = new JsonObject();
            o.addProperty("status", "needs_confirmation");
            o.addProperty("plan", plan);
            o.addProperty("message",
                    "This moves the machine. Ask the user, then repeat the request with \"confirm\": true.");
            return o;
        }
        return null;
    }

    private String motionRefusal(Machine machine) throws Exception {
        if (!machine.isEnabled()) {
            return "Machine is not enabled.";
        }
        if (!machine.isHomed()) {
            return "Machine is not homed.";
        }
        return homeRefusal(machine);
    }

    /**
     * Like {@link #motionRefusal(Machine)}, but the machine may be disabled or not homed yet.
     */
    private String homeRefusal(Machine machine) throws Exception {
        if (isJobActive()) {
            return "A job is active.";
        }
        if (machine.isBusy()) {
            return "Machine is busy with another task.";
        }
        if (isBedMapScanRunning()) {
            return "A bed map scan is running.";
        }
        return null;
    }

    private boolean isJobActive() throws Exception {
        return onSwing(() -> MainFrame.get()
                                      .getJobTab()
                                      .isJobActive());
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Runs a machine task and records it in actions.jsonl, whether it succeeds or fails. On
     * failure, the nozzle state is reported too, since a part may have been left on a nozzle.
     */
    private JsonObject runLogged(String action, JsonObject body, Machine machine,
            Callable<JsonObject> callable) throws Exception {
        return runLogged(action, body, machine, MACHINE_TASK_TIMEOUT_S, callable);
    }

    private JsonObject runLogged(String action, JsonObject body, Machine machine, long timeoutS,
            Callable<JsonObject> callable) throws Exception {
        JsonObject result;
        try {
            result = runOnMachine(machine, callable, timeoutS);
        }
        catch (Exception e) {
            Logger.warn(e, "AI bridge {} failed", action);
            result = error(describe(e));
            result.add("nozzlesAfterFailure", getNozzles().get("nozzles"));
        }
        result.add("safeZAfter", raiseToSafeZ(machine));
        logAction(action, body, result);
        return result;
    }

    /**
     * Lifts everything on all heads to Safe Z, so that the next move cannot drag a nozzle across
     * the machine. Runs after every motion endpoint, including failed ones, since a failure may
     * leave a nozzle down.
     */
    private JsonObject raiseToSafeZ(Machine machine) {
        JsonObject o = new JsonObject();
        try {
            runOnMachine(machine, () -> {
                for (Head head : machine.getHeads()) {
                    head.moveToSafeZ();
                }
                return null;
            });
            o.addProperty("raised", true);
        }
        catch (Exception e) {
            Logger.warn(e, "AI bridge could not raise heads to Safe Z");
            o.addProperty("raised", false);
            o.addProperty("error", describe(e));
        }
        return o;
    }

    private <T> T runOnMachine(Machine machine, Callable<T> callable) throws Exception {
        return runOnMachine(machine, callable, MACHINE_TASK_TIMEOUT_S);
    }

    private <T> T runOnMachine(Machine machine, Callable<T> callable, long timeoutS)
            throws Exception {
        try {
            return machine.submit(callable)
                          .get(timeoutS, TimeUnit.SECONDS);
        }
        catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    private static <T> T onSwing(Callable<T> callable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return callable.call();
        }
        Object[] result = new Object[1];
        Exception[] error = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = callable.call();
            }
            catch (Exception e) {
                error[0] = e;
            }
        });
        if (error[0] != null) {
            throw error[0];
        }
        @SuppressWarnings("unchecked")
        T t = (T) result[0];
        return t;
    }

    private JsonArray collectImages(CvPipeline pipeline, boolean allStages) {
        JsonArray images = new JsonArray();
        if (allStages) {
            for (CvStage stage : pipeline.getStages()) {
                CvStage.Result result = pipeline.getResult(stage);
                if (result == null || result.getImage() == null) {
                    continue;
                }
                addImage(images, stage.getName(), result.getImage());
            }
        }
        else if (pipeline.getWorkingImage() != null) {
            addImage(images, "final", pipeline.getWorkingImage());
        }
        return images;
    }

    private void addImage(JsonArray images, String name, Mat mat) {
        try {
            JsonObject i = new JsonObject();
            i.addProperty("name", name);
            i.addProperty("mimeType", "image/png");
            i.addProperty("data", toPngBase64(OpenCvUtils.toBufferedImage(mat)));
            images.add(i);
        }
        catch (Exception e) {
            Logger.warn(e, "AI bridge could not encode image {}", name);
        }
    }

    private static String toPngBase64(BufferedImage image) throws IOException {
        return toPngBase64(image, MAX_IMAGE_DIMENSION);
    }

    private static String toPngBase64(BufferedImage image, int maxDimension) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();
        double scale = Math.min(1.0, (double) maxDimension / Math.max(w, h));
        if (scale < 1.0) {
            int sw = (int) Math.round(w * scale);
            int sh = (int) Math.round(h * scale);
            BufferedImage scaled = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, sw, sh, null);
            g.dispose();
            image = scaled;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return Base64.getEncoder()
                     .encodeToString(out.toByteArray());
    }

    private void logAction(String action, JsonObject request, JsonObject result) {
        try {
            JsonObject entry = new JsonObject();
            entry.addProperty("time",
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date()));
            entry.addProperty("action", action);
            entry.add("request", request);
            // Leave out the images, they would bloat the log.
            JsonObject summary = new JsonObject();
            for (Map.Entry<String, JsonElement> e : result.entrySet()) {
                if (!e.getKey()
                      .equals("images")) {
                    summary.add(e.getKey(), e.getValue());
                }
            }
            entry.add("result", summary);
            Files.write(new File(bridgeDirectory, "actions.jsonl").toPath(),
                    (new Gson().toJson(entry) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (Exception e) {
            Logger.warn(e, "AI bridge could not write action log");
        }
    }

    private static Feeder requireFeeder(Machine machine, JsonObject body) throws BadRequest {
        String feederId = getString(body, "feederId");
        if (feederId == null) {
            throw new BadRequest("feederId is required.");
        }
        Feeder feeder = machine.getFeeder(feederId);
        if (feeder == null) {
            // Allow the name as well, it is what the user sees.
            for (Feeder f : machine.getFeeders()) {
                if (feederId.equals(f.getName())) {
                    if (feeder != null) {
                        throw new BadRequest("More than one feeder is named " + feederId
                                + ", use the id instead.");
                    }
                    feeder = f;
                }
            }
        }
        if (feeder == null) {
            throw new BadRequest("No feeder with id or name " + feederId);
        }
        return feeder;
    }

    private static Nozzle findNozzle(Machine machine, String id) {
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                if (nozzle.getId()
                          .equals(id)
                        || nozzle.getName()
                                 .equals(id)) {
                    return nozzle;
                }
            }
        }
        return null;
    }

    private static JsonObject readBody(HttpExchange exchange) throws IOException, BadRequest {
        try (InputStream in = exchange.getRequestBody()) {
            String s = IOUtils.toString(in, StandardCharsets.UTF_8);
            if (s.trim()
                 .isEmpty()) {
                return new JsonObject();
            }
            JsonElement e = new JsonParser().parse(s);
            if (!e.isJsonObject()) {
                throw new BadRequest("Request body must be a JSON object.");
            }
            return e.getAsJsonObject();
        }
        catch (com.google.gson.JsonParseException e) {
            throw new BadRequest("Invalid JSON: " + e.getMessage());
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) throws IOException {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            String key = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), "UTF-8");
            String value = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), "UTF-8");
            map.put(key, value);
        }
        return map;
    }

    private static String getString(JsonObject body, String name) {
        JsonElement e = body.get(name);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static double getDouble(JsonObject body, String name, double defaultValue) {
        JsonElement e = body.get(name);
        return e == null || e.isJsonNull() ? defaultValue : e.getAsDouble();
    }

    private static boolean getBoolean(JsonObject body, String name, boolean defaultValue) {
        JsonElement e = body.get(name);
        return e == null || e.isJsonNull() ? defaultValue : e.getAsBoolean();
    }

    private static JsonElement toJson(Location l) {
        if (l == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("x", round(l.getX()));
        o.addProperty("y", round(l.getY()));
        o.addProperty("z", round(l.getZ()));
        o.addProperty("rotation", round(l.getRotation()));
        o.addProperty("units", l.getUnits()
                                .getShortName());
        return o;
    }

    private static JsonElement toJson(Rectangle2D r) {
        JsonObject o = new JsonObject();
        o.addProperty("xMin", round(r.getMinX()));
        o.addProperty("yMin", round(r.getMinY()));
        o.addProperty("xMax", round(r.getMaxX()));
        o.addProperty("yMax", round(r.getMaxY()));
        o.addProperty("units", "mm");
        return o;
    }

    private static String format(Rectangle2D r) {
        return String.format("x %.1f to %.1f, y %.1f to %.1f mm", r.getMinX(), r.getMaxX(),
                r.getMinY(), r.getMaxY());
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date());
    }

    private static String format(Location l) {
        return String.format("X %.3f, Y %.3f, Z %.3f, R %.2f %s", l.getX(), l.getY(), l.getZ(),
                l.getRotation(), l.getUnits()
                                  .getShortName());
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass()
                .getSimpleName()
                + (message == null ? "" : ": " + message);
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("status", "ok");
        return o;
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("status", "error");
        o.addProperty("error", message);
        return o;
    }

    private static class BadRequest extends Exception {
        BadRequest(String message) {
            super(message);
        }
    }
}
