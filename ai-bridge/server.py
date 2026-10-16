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
JOB_TIMEOUT_S = 920  # a little more than the Java side job timeout

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


def _call(method, path, query=None, body=None, timeout=None):
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
        with urllib.request.urlopen(request, timeout=timeout or TIMEOUT_S) as response:
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
def list_job_placements(job_file: str) -> str:
    """List the boards and part placements of an OpenPnP job file (.job.xml): boardId,
    placementId, part, enabled, placed and the machine location of each placement. Only
    placements whose side matches the board's sideUp can be placed.
    Reads the file only; the Job tab and the machine are not touched."""
    return json.dumps(_call("GET", "/job/placements", {"file": job_file}), ensure_ascii=False, indent=1)


@mcp.tool()
def place_from_job(
    job_file: str,
    placements: list[dict],
    reason: str,
    confirm: bool = False,
    max_part_failures: int | None = None,
    capture_before: bool = True,
) -> str:
    """Place parts of a job file with OpenPnP's own job processor: the nozzle with a fitting
    tip picks from an enabled feeder, bottom vision aligns the part and it is placed on the
    board, as in a normal job run with only these placements enabled.

    placements: [{"boardId": "Pnl1⇒Brd1", "placementId": "U1"}, ...]; boardId may be its last
    part ("Brd1") if unique. See list_job_placements. The job file is not changed and the Job
    tab is not used, so the placed status there stays as it was.
    MOVES THE MACHINE: first call without confirm to get the plan (placement location, nozzles
    and feeders), ask the user, and only then call again with confirm=true. Check "warnings":
    a placement marked as placed may already have a part on the board. After the job the head
    is parked. If a feeder finds no part, OpenPnP disables it and the remaining placements are
    run again with the next enabled feeder, until all are placed or no feeder is left
    (feedersDisabled lists them; re-enable them in the Feeders tab later). The result always
    lists which placements were placed. If a picked part fails, e.g. bottom vision finds it
    too far off, it is discarded and the next part is picked, until max_part_failures parts
    (default 5) fail in a row; partsRejected lists why. On failure the job processor discards
    what was picked, and nozzlesAfterFailure shows what is left.
    With capture_before (default) the top camera first photographs each placement, so that
    inspect_placements can find the part by what changed (beforePhotos counts them). If there
    is a bare board photo (survey_bare_boards) and the placement changed since then, a part
    seems to be there already: it is not placed (skippedOccupied, "skipped" on the placement).
    notCheckedForParts lists placements without a bare board photo, placed unchecked.
    """
    body = {"file": job_file, "placements": placements, "confirm": confirm, "reason": reason,
            "maxPartFailures": max_part_failures, "captureBefore": capture_before}
    return json.dumps(_call("POST", "/job/place", body=body, timeout=JOB_TIMEOUT_S),
                      ensure_ascii=False, indent=1)


@mcp.tool()
def survey_bare_boards(
    job_file: str,
    reason: str,
    confirm: bool = False,
    placements: list[dict] | None = None,
) -> str:
    """Photograph the placements of a job while the boards are still bare (no parts on them).

    Run this once after loading new boards. place_from_job then compares each placement with
    its bare photo and does not place where a part seems to be already, and inspect_placements
    can compare with it when there is no photo from just before placing. placements: as for
    place_from_job; default all part placements on enabled boards. Takes about a second per
    placement. Returns how many were photographed per board, no images. Afterwards the head is
    parked, away from the boards.

    MOVES THE MACHINE (camera only, at Safe Z, no pick or place): first call without confirm
    to get the plan, ask the user, and only then call again with confirm=true.
    """
    body = {"file": job_file, "placements": placements, "confirm": confirm, "reason": reason}
    return json.dumps(_call("POST", "/job/survey", body=body, timeout=JOB_TIMEOUT_S),
                      ensure_ascii=False, indent=1)


@mcp.tool()
def inspect_placements(
    job_file: str,
    placements: list[dict],
    reason: str,
    confirm: bool = False,
    images: str = "failures",
    tolerance_mm: float = 0.25,
    max_rotation_degrees: float = 0,
) -> list:
    """Check placed parts: the top camera moves over each placement of a job file, takes a
    photo and judges it, so that photos need to be looked at only for parts that failed or
    for spot checks.

    If place_from_job took a photo before placing (its default), the part is found by what
    changed since then, whatever its color, and each placement gets a verdict: "ok", "check"
    (reasons: offset over tolerance_mm, rotation moving the part's ends by more than
    tolerance_mm, or over max_rotation_degrees if that is set (default 0: off), size of the change
    not matching the part, or something else in the photo changed, e.g. a pushed neighbor) or
    "missing" (nothing changed). Without a photo from before, the verdict is "unverified": a
    dark body search only suits IC bodies, judge from the image. measured.offsetX/offsetY is
    found minus expected in machine mm, rotationError in degrees counterclockwise. Neither
    method can tell pin 1 from the opposite end: check polarity in the image.

    images: "failures" (default, only placements whose verdict is not ok), "all" or "none".
    Images show the footprint where the job expects it (body green, pads cyan, pin 1 red dot,
    image center yellow cross), the part found in magenta and what changed tinted red.

    placements: [{"boardId": "Pnl1⇒Brd1", "placementId": "U1"}, ...] as for place_from_job.
    Afterwards the head is parked, away from the boards.
    MOVES THE MACHINE (camera only, at Safe Z, no pick or place): first call without confirm
    to get the plan, ask the user, and only then call again with confirm=true.
    """
    body = {"file": job_file, "placements": placements, "confirm": confirm, "reason": reason,
            "images": images, "toleranceMm": tolerance_mm,
            "maxRotationDegrees": max_rotation_degrees}
    return _with_images(_call("POST", "/job/inspect", body=body, timeout=JOB_TIMEOUT_S))


@mcp.tool()
def capture_camera(z: float | None = None) -> list:
    """Take a photo with the top camera where it is now. Does not move the machine.

    Returns the image with the camera location and scale: pixel (px, py) of the returned
    image is at machine x = cameraLocation.x + (px / imageScale - frameWidth / 2) * mmPerPixelX,
    y = cameraLocation.y - (py / imageScale - frameHeight / 2) * mmPerPixelY (up is +y).
    z is the height of what is looked at (e.g. the feeder or board surface), for the right
    scale; default the camera's default Z. The original frame is saved (frameFile).
    """
    return _with_images(_call("POST", "/camera/capture", body={"z": z}))


@mcp.tool()
def move_camera(x: float, y: float, reason: str, confirm: bool = False) -> str:
    """Move the top camera to machine X, Y (mm), with all nozzles at Safe Z first.

    MOVES THE MACHINE: first call without confirm to get the plan, ask the user, and only then
    call again with confirm=true. Afterwards all heads are lifted to Safe Z.
    """
    body = {"x": x, "y": y, "confirm": confirm, "reason": reason}
    return json.dumps(_call("POST", "/camera/move", body=body), ensure_ascii=False, indent=1)


@mcp.tool()
def probe_feeder_z(
    feeder_id: str,
    reason: str,
    confirm: bool = False,
    nozzle_id: str | None = None,
    start_z: float | None = None,
    floor_z: float | None = None,
    step: float = 0.05,
    settle_ms: int = 150,
    contact_drop: float | None = None,
    apply: bool = False,
) -> str:
    """Measure a feeder's pick Z by touching its next part with a nozzle and the vacuum sensor.

    The nozzle goes over the next part at Safe Z, down to start_z (default feeder Z + 1.5), turns
    the vacuum on and steps down by step mm until the vacuum level drops below the contact
    threshold (tip sealed on the part). It never goes below floor_z (default feeder Z - 1; at most
    4 mm below start_z). At contact the vacuum is turned off before lifting so the part stays in
    the tape. Choose floor_z conservatively: if the tip misses the part it goes all the way down.
    nozzle_id defaults to the first nozzle with a tip that fits the part. With apply=true (strip
    feeders only) the measured contactZ becomes the feeder's Z. The threshold is contact_drop
    below the level read in the air at start_z; without contact_drop it is between the tip's part
    on/off ranges when the air level is in the part off range, else 10 below the air level.

    MOVES THE MACHINE: first call without confirm to get the plan, ask the user, and only then
    call again with confirm=true. Check partStuckOnNozzle afterwards: if true, a part is on the
    nozzle with the vacuum kept on, discard it. readings lists Z and vacuum level per step.
    """
    body = {"feederId": feeder_id, "confirm": confirm, "nozzleId": nozzle_id, "startZ": start_z,
            "floorZ": floor_z, "step": step, "settleMs": settle_ms,
            "contactDrop": contact_drop, "apply": apply, "reason": reason}
    return json.dumps(_call("POST", "/feeder/probe-z", body=body), ensure_ascii=False, indent=1)


@mcp.tool()
def test_bottom_vision(
    reason: str,
    confirm: bool = False,
    nozzle_id: str | None = None,
    rotation: float = 0,
    threshold: int | None = None,
) -> list:
    """Run the bottom vision of the part on a nozzle once, without placing it.

    The nozzle (default: the one holding a part) goes over the bottom camera at Safe Z and the
    part's bottom vision pipeline runs as in a job. Returns per shot found, offsetX/offsetY (mm),
    angle and the size found (widthMm/heightMm), plus the camera image, the Threshold stage output
    and the final image, also when nothing was found. threshold overrides the Threshold stages for
    this run only (nothing is saved), so several values can be tried with the same part. The part
    stays on the nozzle: pick it first with pick_from_feeder(return_part=False), discard it after.

    MOVES THE MACHINE: first call without confirm to get the plan, ask the user, and only then
    call again with confirm=true.
    """
    body = {"nozzleId": nozzle_id, "rotation": rotation, "threshold": threshold,
            "confirm": confirm, "reason": reason}
    return _with_images(_call("POST", "/vision/bottom/test", body=body))


@mcp.tool()
def update_feeder(
    feeder_id: str,
    reason: str,
    enabled: bool | None = None,
    z: float | None = None,
) -> str:
    """Enable or disable a feeder and/or set its pick Z (strip feeders only: the reference and
    last hole Z). Does not move the machine. Give at least one of enabled and z. The result shows
    the values before and after."""
    body = {"feederId": feeder_id, "enabled": enabled, "z": z, "reason": reason}
    return json.dumps(_call("POST", "/feeder/update", body=body), ensure_ascii=False, indent=1)


@mcp.tool()
def list_parts(ids: str | None = None, package: str | None = None) -> str:
    """List parts with their name, height (mm), package and the bottom vision settings they use
    (own or inherited, with thresholds). ids: comma separated part ids; package: a package id.
    Without filters all parts are listed."""
    return json.dumps(_call("GET", "/parts", {"ids": ids, "package": package}),
                      ensure_ascii=False, indent=1)


@mcp.tool()
def update_part(
    part_id: str,
    reason: str,
    height: float | None = None,
    name: str | None = None,
    bottom_vision_threshold: int | None = None,
) -> str:
    """Set a part's height (mm, top of the part above the board), name (e.g. its manufacturer or
    LCSC part number) and/or bottom vision threshold. Does not move the machine.

    With a threshold the part gets bottom vision settings of its own, a copy of those it
    inherits (usually its package's), if it has none yet; its package and other parts keep
    theirs. Find the threshold with test_bottom_vision(threshold=...) first. The result shows
    the values before and after."""
    body = {"partId": part_id, "height": height, "name": name,
            "bottomVisionThreshold": bottom_vision_threshold, "reason": reason}
    return json.dumps(_call("POST", "/part/update", body=body), ensure_ascii=False, indent=1)


@mcp.tool()
def update_package(package_id: str, bottom_vision_threshold: int, reason: str) -> str:
    """Set the bottom vision threshold of a package, used by its parts that have no settings
    of their own (partsInheriting in the result). If the package shares its settings with
    others, it gets a copy of its own first. Does not move the machine."""
    body = {"packageId": package_id, "bottomVisionThreshold": bottom_vision_threshold,
            "reason": reason}
    return json.dumps(_call("POST", "/package/update", body=body), ensure_ascii=False, indent=1)


@mcp.tool()
def scan_qr_feeders(
    y: float,
    x_from: float,
    x_to: float,
    reason: str,
    confirm: bool = False,
    apply: bool = False,
    step: float = 10,
    y_tolerance: float = 4,
    tape_offset: float = -14,
    search_length: float = 80,
    tape_width: float = 8,
    z: float | None = None,
) -> str:
    """Set up strip feeders from QR labels at the start of each cut tape strip, without photos
    to look at. Also runs without the AI: ai-bridge/scan_feeders.py.

    The top camera moves along y from x_from to x_to every step mm, also y_tolerance mm above
    and below in rows 2 mm apart (the camera sees only ~10.6 mm across y; 0: one row), and reads the QR labels
    (each names an OpenPnP part id, e.g. "C0603-1uF"). The labels are stuck on the strips, which
    run on from under them: it photographs each strip from tape_offset mm beside its label (along
    y, default below) and every 8 mm further, up to search_length mm, until parts show (used
    pockets are empty), and finds the sprocket holes and parts. A strip that feeds towards its
    label (turned round) is reported as an error. With apply, a strip
    feeder of that part near there is updated (Z and rotation in tape kept, feed count reset
    so that it picks the first part found), otherwise one is added, disabled, with Z and
    rotation in tape copied from a strip feeder of the same package (or z). Without apply
    nothing changes: labels shows what would happen ("would add"/"would update"), with
    firstPick. Labels with an unknown part or no strip found get status "error".

    MOVES THE MACHINE (camera only, at Safe Z): first call without confirm to get the plan,
    ask the user, and only then call again with confirm=true. Afterwards the head is parked.
    """
    body = {"y": y, "xFrom": x_from, "xTo": x_to, "step": step, "yTolerance": y_tolerance,
            "tapeOffset": tape_offset,
            "searchLength": search_length, "tapeWidth": tape_width, "z": z, "apply": apply, "confirm": confirm,
            "reason": reason}
    return json.dumps(_call("POST", "/feeder/scan-qr", body=body, timeout=JOB_TIMEOUT_S),
                      ensure_ascii=False, indent=1)


@mcp.tool()
def setup_strip_feeder(
    part_id: str,
    z: float | None = None,
    tape_width: float = 8,
    rotation_in_tape: float = 0,
    name: str | None = None,
    create: bool = False,
) -> list:
    """Set up a strip (cut tape) feeder from one photo where the top camera is now. Does not
    move the machine: have the operator jog the camera over the start of the tape first, with
    at least two sprocket holes, the first part and one more hole beyond it in view.

    Finds the holes and parts and derives the ReferenceStripFeeder settings: reference hole,
    last hole, feed direction (parts must be left of it, which fixes the direction) and part
    pitch, and checks that the feeder's pick locations land on the parts found (error, mm).
    z is the tape surface height, used for the camera scale and as the pick height; without it
    the camera default Z is used and zWarning says so. rotation_in_tape (degrees) is the
    feeder's Rotation in Tape: check that the pick rotation matches how the parts lie in the
    photo relative to the package footprint.

    Without create nothing changes: show the result to the user. With create=true (only after
    the user agreed) the feeder is added to the machine, disabled until Z has been checked.
    """
    body = {"partId": part_id, "z": z, "tapeWidth": tape_width, "rotationInTape": rotation_in_tape,
            "name": name, "create": create}
    return _with_images(_call("POST", "/feeder/strip/setup", body=body))


@mcp.tool()
def get_bed_map(
    x_min: float | None = None,
    y_min: float | None = None,
    x_max: float | None = None,
    y_max: float | None = None,
    max_size: int | None = None,
    z: float | None = None,
    mm_per_pixel: float | None = None,
) -> list:
    """Get the stitched bed map image, whole or a region (machine coordinates in mm).

    Returns the image and imageArea/imageMmPerPixel to convert image pixels to machine
    coordinates: x = xMin + px * imageMmPerPixel, y = yMax - py * imageMmPerPixel
    (up in the image is +y). max_size limits the longest image side (default and
    maximum 1600 px); ask for a smaller region to see more detail. Does not move the machine.

    The map itself has 0.1 mm per pixel. For a region small enough to show finer than that,
    the image is stitched anew from the original camera frames (source "frames"), down to
    about 0.01 mm per pixel, enough to read small parts. mm_per_pixel asks for a coarser
    resolution. The map is stitched for the plane at viewingPlaneZ; objects at other heights
    are slightly misaligned at frame borders. Give z (the board or feeder Z) to stitch a
    region for that height instead.
    """
    query = _region(x_min, y_min, x_max, y_max) or {}
    query["maxSize"] = max_size
    query["z"] = z
    query["mmPerPixel"] = mm_per_pixel
    return _with_images(_call("GET", "/bedmap", query))


if __name__ == "__main__":
    mcp.run()
