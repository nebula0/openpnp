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
import java.awt.RenderingHints;
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
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.apache.commons.io.IOUtils;
import org.opencv.core.Mat;
import org.opencv.core.RotatedRect;
import org.openpnp.gui.FeedersPanel;
import org.openpnp.gui.MainFrame;
import org.openpnp.machine.reference.feeder.ReferenceLoosePartFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.pmw.tinylog.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    private static final int MAX_IMAGE_DIMENSION = 800;

    private static AiBridgeServer instance;

    private final HttpServer server;
    private final String token;
    private final File bridgeDirectory;
    private final Gson gson = new GsonBuilder().serializeNulls()
                                               .setPrettyPrinting()
                                               .create();

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
            }
        }
        else if (method.equals("POST")) {
            JsonObject body = readBody(exchange);
            switch (path) {
                case "/feeder/inspect":
                    return inspectFeeder(body);
                case "/feeder/pick":
                    return pickFeeder(body);
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

    // ---------------------------------------------------------------------------------------
    // Safety
    // ---------------------------------------------------------------------------------------

    /**
     * @return null if motion may proceed, otherwise the response to send instead.
     */
    private JsonObject gateMotion(Machine machine, JsonObject body, String plan) throws Exception {
        String refusal = motionRefusal(machine);
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
        if (isJobActive()) {
            return "A job is active.";
        }
        if (machine.isBusy()) {
            return "Machine is busy with another task.";
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
        JsonObject result;
        try {
            result = runOnMachine(machine, callable);
        }
        catch (Exception e) {
            Logger.warn(e, "AI bridge {} failed", action);
            result = error(describe(e));
            result.add("nozzlesAfterFailure", getNozzles().get("nozzles"));
        }
        logAction(action, body, result);
        return result;
    }

    private <T> T runOnMachine(Machine machine, Callable<T> callable) throws Exception {
        try {
            return machine.submit(callable)
                          .get(MACHINE_TASK_TIMEOUT_S, TimeUnit.SECONDS);
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
        int w = image.getWidth();
        int h = image.getHeight();
        double scale = Math.min(1.0, (double) MAX_IMAGE_DIMENSION / Math.max(w, h));
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
