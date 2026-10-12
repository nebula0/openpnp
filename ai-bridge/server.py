"""MCP server that forwards tool calls to the AiBridgeServer running inside OpenPnP.

OpenPnP must be started with -DaiBridge.port=<port>. On startup it writes
<configDir>/ai-bridge/bridge.json with the port and a fresh token, which this
server reads on every call (so restarting OpenPnP needs no reconfiguration).

Set OPENPNP_CONFIG_DIR to use a configuration other than ~/.openpnp2, e.g. a
simulation config.
"""

import base64
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from mcp.server.fastmcp import FastMCP, Image

CONFIG_DIR = Path(os.environ.get("OPENPNP_CONFIG_DIR", Path.home() / ".openpnp2"))
TIMEOUT_S = 200  # a little more than the Java side machine task timeout

mcp = FastMCP("openpnp")


def _bridge_info():
    path = CONFIG_DIR / "ai-bridge" / "bridge.json"
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise RuntimeError(
            f"{path} not found. Is OpenPnP running with -DaiBridge.port=... "
            f"and configDir {CONFIG_DIR}?"
        )


def _call(method, path, query=None, body=None):
    info = _bridge_info()
    url = f"http://127.0.0.1:{info['port']}{path}"
    if query:
        url += "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={"X-AI-Bridge-Token": info["token"], "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        # The bridge answers errors with a JSON body too.
        return json.loads(e.read().decode("utf-8"))
    except urllib.error.URLError as e:
        raise RuntimeError(f"Cannot reach OpenPnP AI bridge at {url}: {e.reason}. Is OpenPnP running?")


def _with_images(result):
    """Split base64 images out of a result into MCP image content."""
    images = result.pop("images", None) or []
    content = [json.dumps(result, ensure_ascii=False, indent=1)]
    for image in images:
        content.append(f"Image: {image['name']}")
        content.append(Image(data=base64.b64decode(image["data"]), format="png"))
    return content


@mcp.tool()
def get_status() -> str:
    """Machine state: enabled, homed, busy, job active, selected nozzle, and whether motion is allowed."""
    return json.dumps(_call("GET", "/status"), ensure_ascii=False, indent=1)


@mcp.tool()
def list_feeders(part_id: str | None = None) -> str:
    """List feeders with their part, enabled state and pick location. Optionally filter by part id (case-insensitive)."""
    return json.dumps(_call("GET", "/feeders", {"partId": part_id}), ensure_ascii=False, indent=1)


@mcp.tool()
def list_nozzles() -> str:
    """List nozzles with loaded nozzle tip, part on nozzle, and which one is selected in the GUI.

    Also gives each nozzle's last commanded Z in mm and whether it is within Safe Z
    (atSafeZ). The steppers have no encoders, so after a crash Z may be wrong.
    """
    return json.dumps(_call("GET", "/nozzles"), ensure_ascii=False, indent=1)


@mcp.tool()
def inspect_feeder(feeder_id: str, reason: str, confirm: bool = False, all_stages: bool = False) -> list:
    """Move the top camera over a ReferenceLoosePartFeeder and run its vision pipeline, without picking.

    Returns how many parts were found, their locations, and the result image
    (or every stage image with all_stages=true). MOVES THE MACHINE: first call
    without confirm to get the plan, ask the user, and only then call again with
    confirm=true. feeder_id may be the feeder id or its unique name. Afterwards all
    heads are lifted to Safe Z; safeZAfter reports whether that worked.
    """
    body = {"feederId": feeder_id, "confirm": confirm, "allStages": all_stages, "reason": reason}
    return _with_images(_call("POST", "/feeder/inspect", body=body))


@mcp.tool()
def pick_from_feeder(
    feeder_id: str,
    reason: str,
    confirm: bool = False,
    nozzle_id: str | None = None,
    return_part: bool = True,
) -> str:
    """Feed and pick one part from a feeder, like the Pick button in the Feeders tab, then by default put it back.

    MOVES THE MACHINE: first call without confirm to get the plan, ask the user,
    and only then call again with confirm=true. nozzle_id defaults to the nozzle
    selected in the GUI. If the call fails, check nozzlesAfterFailure: a part may
    still be on the nozzle. Afterwards all heads are lifted to Safe Z (also after a
    failure); safeZAfter reports whether that worked.
    """
    body = {
        "feederId": feeder_id,
        "confirm": confirm,
        "nozzleId": nozzle_id,
        "returnPart": return_part,
        "reason": reason,
    }
    return json.dumps(_call("POST", "/feeder/pick", body=body), ensure_ascii=False, indent=1)


@mcp.tool()
def home_machine(reason: str, confirm: bool = False) -> str:
    """Enable the machine if needed and home it, like the power and home buttons.

    MOVES THE MACHINE: all axes move to their home positions or switches. First call
    without confirm to get the plan, ask the user (remind them nothing may be in the
    way of the head), and only then call again with confirm=true. Afterwards all heads
    are lifted to Safe Z.
    """
    body = {"confirm": confirm, "reason": reason}
    return json.dumps(_call("POST", "/machine/home", body=body), ensure_ascii=False, indent=1)


@mcp.tool()
def discard_part(reason: str, confirm: bool = False, nozzle_id: str | None = None) -> str:
    """Drop whatever is on a nozzle at the machine's discard location, like the discard button.

    Works even if OpenPnP thinks the nozzle is empty, e.g. after a failed pick left a
    part on it. MOVES THE MACHINE: first call without confirm to get the plan, ask the
    user, and only then call again with confirm=true. nozzle_id defaults to the nozzle
    selected in the GUI. Afterwards all heads are lifted to Safe Z.
    """
    body = {"confirm": confirm, "nozzleId": nozzle_id, "reason": reason}
    return json.dumps(_call("POST", "/nozzle/discard", body=body), ensure_ascii=False, indent=1)


def _region(x_min, y_min, x_max, y_max):
    values = (x_min, y_min, x_max, y_max)
    if all(v is None for v in values):
        return None
    if any(v is None for v in values):
        raise ValueError("Give all of x_min, y_min, x_max and y_max, or none.")
    return {"xMin": x_min, "yMin": y_min, "xMax": x_max, "yMax": y_max}


@mcp.tool()
def scan_bed_map(
    reason: str,
    confirm: bool = False,
    x_min: float | None = None,
    y_min: float | None = None,
    x_max: float | None = None,
    y_max: float | None = None,
) -> str:
    """Scan the machine bed with the top camera and stitch the frames into the bed map.

    Without a region the whole bed is scanned and the map replaced. With a region
    (machine coordinates in mm) only that part of the existing map is updated.
    MOVES THE MACHINE: first call without confirm to get the plan (number of camera
    positions and area), ask the user, and only then call again with confirm=true.
    The scan runs in the background; poll get_bed_map_scan_status until running is
    false. Afterwards all heads are lifted to Safe Z.
    """
    body = {
        "confirm": confirm,
        "region": _region(x_min, y_min, x_max, y_max),
        "reason": reason,
    }
    return json.dumps(_call("POST", "/bedmap/scan", body=body), ensure_ascii=False, indent=1)


@mcp.tool()
def get_bed_map_scan_status() -> str:
    """Progress of the current or last bed map scan: running, done/total frames, and the result
    (error, nozzlesAfterFailure, safeZAfter) once it has finished."""
    return json.dumps(_call("GET", "/bedmap/scan"), ensure_ascii=False, indent=1)


@mcp.tool()
def cancel_bed_map_scan() -> str:
    """Stop a running bed map scan after the current frame. What was scanned so far is kept."""
    return json.dumps(_call("POST", "/bedmap/scan/cancel", body={}), ensure_ascii=False, indent=1)


@mcp.tool()
def get_bed_map(
    x_min: float | None = None,
    y_min: float | None = None,
    x_max: float | None = None,
    y_max: float | None = None,
    max_size: int | None = None,
) -> list:
    """Get the stitched bed map image, whole or a region (machine coordinates in mm).

    Returns the image and imageArea/imageMmPerPixel to convert image pixels to machine
    coordinates: x = xMin + px * imageMmPerPixel, y = yMax - py * imageMmPerPixel
    (up in the image is +y). max_size limits the longest image side (default and
    maximum 1600 px); ask for a smaller region to see more detail. Does not move the machine.
    """
    query = _region(x_min, y_min, x_max, y_max) or {}
    query["maxSize"] = max_size
    return _with_images(_call("GET", "/bedmap", query))


if __name__ == "__main__":
    mcp.run()
