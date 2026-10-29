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

import org.openpnp.gui.MainFrame;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion.MotionOption;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Jogging from the web UI: moves the top camera or a nozzle by a small step the way the jog
 * buttons in the GUI do (the same board protection check and jog motion), or lifts the head to
 * Safe Z. A person presses each button, so there is no confirmation step and nothing goes into
 * the action log. The usual motion refusals (not enabled, not homed, busy, job active) apply.
 */
class AiBridgeJog {
    private static final double MAX_XY_MM = 50;
    private static final double MAX_Z_MM = 10;
    private static final double MAX_ROTATION = 180;

    /**
     * Body: {tool: "camera" or a nozzle name or id (default: the tool selected in the GUI), dx,
     * dy, dz (mm), dc (degrees)}, or {tool, safeZ: true}.
     */
    static JsonObject jog(AiBridgeServer bridge, JsonObject body) throws Exception {
        Machine machine = Configuration.get()
                                       .getMachine();
        String refusal = bridge.motionRefusal(machine);
        if (refusal != null) {
            JsonObject o = new JsonObject();
            o.addProperty("status", "refused");
            o.addProperty("reason", refusal);
            return o;
        }
        HeadMountable tool = findTool(machine, body.get("tool"));
        boolean safeZ = body.has("safeZ") && body.get("safeZ")
                                                 .getAsBoolean();
        double dx = step(body, "dx", MAX_XY_MM);
        double dy = step(body, "dy", MAX_XY_MM);
        double dz = step(body, "dz", MAX_Z_MM);
        double dc = step(body, "dc", MAX_ROTATION);
        bridge.runOnMachine(machine, () -> {
            if (safeZ) {
                tool.getHead()
                    .moveToSafeZ();
            }
            else {
                Location l = tool.getLocation()
                                 .convertToUnits(LengthUnit.Millimeters);
                Location target = new Location(LengthUnit.Millimeters, l.getX() + dx,
                        l.getY() + dy, l.getZ() + dz, l.getRotation() + dc);
                MainFrame.get()
                         .getMachineControls()
                         .checkJogMotionSafety(tool, target);
                tool.moveTo(target, MotionOption.JogMotion);
            }
            MovableUtils.fireTargetedUserAction(tool, !safeZ);
            return null;
        });
        JsonObject o = AiBridgeServer.ok();
        o.addProperty("tool", tool.getName());
        o.add("location", AiBridgeServer.toJson(tool.getLocation()
                                                    .convertToUnits(LengthUnit.Millimeters)));
        return o;
    }

    private static HeadMountable findTool(Machine machine, JsonElement name) throws Exception {
        if (name == null || name.isJsonNull() || name.getAsString()
                                                     .isEmpty()) {
            return AiBridgeServer.onSwing(() -> MainFrame.get()
                                                         .getMachineControls()
                                                         .getSelectedTool());
        }
        String id = name.getAsString();
        Head head = machine.getDefaultHead();
        if (id.equalsIgnoreCase("camera")) {
            return head.getDefaultCamera();
        }
        for (Head h : machine.getHeads()) {
            for (Nozzle nozzle : h.getNozzles()) {
                if (nozzle.getId()
                          .equals(id)
                        || nozzle.getName()
                                 .equals(id)) {
                    return nozzle;
                }
            }
            for (Camera camera : h.getCameras()) {
                if (camera.getId()
                          .equals(id)
                        || camera.getName()
                                 .equals(id)) {
                    return camera;
                }
            }
        }
        throw new AiBridgeServer.BadRequest("No camera or nozzle " + id + " on the head.");
    }

    private static double step(JsonObject body, String key, double max)
            throws AiBridgeServer.BadRequest {
        JsonElement e = body.get(key);
        if (e == null || e.isJsonNull()) {
            return 0;
        }
        double v = e.getAsDouble();
        if (Double.isNaN(v) || Math.abs(v) > max) {
            throw new AiBridgeServer.BadRequest(key + " must be at most " + max + " per jog.");
        }
        return v;
    }
}
