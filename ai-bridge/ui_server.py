"""Web UI for OpenPnP with the AI bridge: cameras, task timeline, board, parts and a Claude chat.

Serves ai-bridge/ui/index.html on http://127.0.0.1:8990 and
- forwards /api/view/... to the bridge's view server (camera frames, job layout, status), which
  answers while a machine task runs,
- forwards /api/bridge/... (GET only) to the bridge itself (parts, feeders),
- reads the bridge's action log for /api/actions,
- runs Claude Code (the claude CLI in stream-json mode) for /api/chat/..., and passes its events
  to the browser as server-sent events. Tools that need permission are asked about in the page.

Only the Python standard library is used. See UI_PLAN.md for the protocol details.

    python ai-bridge/ui_server.py          # real machine (%USERPROFILE%/.openpnp2)
    python ai-bridge/ui_server.py --sim    # simulator (%USERPROFILE%/openpnp-sim)
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT_DIR = HERE.parent
UI_FILE = HERE / "ui" / "index.html"
ACTION_LOG_LIMIT = 300
# Keep this many chat events for a page that reconnects; older ones are dropped.
EVENT_HISTORY = 5000


class Bridge:
    """Talks to AiBridgeServer and its view server; bridge.json is read again on every call,
    because OpenPnP writes a new token each time it starts."""

    def __init__(self, config_dir):
        self.config_dir = config_dir

    def info(self):
        path = self.config_dir / "ai-bridge" / "bridge.json"
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None

    def open(self, port_key, path, timeout=10):
        info = self.info()
        if not info or not info.get(port_key):
            raise ConnectionError("OpenPnP is not running with the AI bridge "
                                  f"(no {port_key} in bridge.json).")
        url = f"http://127.0.0.1:{info[port_key]}{path}"
        req = urllib.request.Request(url, headers={"X-AI-Bridge-Token": info["token"]})
        return urllib.request.urlopen(req, timeout=timeout)


class Chat:
    """One Claude Code process in stream-json mode. Events from it, and the page's own events
    (permission requests and answers), go into one numbered list that pages follow."""

    def __init__(self, sim):
        self.sim = sim
        self.proc = None
        self.lock = threading.Condition()
        self.events = []  # (number, dict)
        self.next_number = 1
        self.auto_approve = False
        self.pending = {}  # request_id -> can_use_tool request
        self.busy = False
        self.session_id = None

    # -- events --------------------------------------------------------------------------------

    def emit(self, event):
        with self.lock:
            self.events.append((self.next_number, event))
            self.next_number += 1
            if len(self.events) > EVENT_HISTORY:
                del self.events[: len(self.events) - EVENT_HISTORY]
            self.lock.notify_all()

    def wait_events(self, after, timeout=15):
        with self.lock:
            if not self.events or self.events[-1][0] <= after:
                self.lock.wait(timeout)
            return [(n, e) for n, e in self.events if n > after]

    # -- process -------------------------------------------------------------------------------

    def system_prompt(self):
        server = "openpnp-sim" if self.sim else "openpnp"
        machine = "the simulator" if self.sim else "the real machine"
        return (
            f"You are talking with the user in the OpenPnP web UI, which is connected to {machine}. "
            f"For machine operations use only the tools of the MCP server '{server}'. Next to this "
            "chat the user sees the live cameras, the board, the parts list and a task timeline. "
            f"For work with two or more steps, first list the steps with the update_plan tool of "
            f"'{server}' and call it again whenever a step starts or finishes; the UI shows the plan "
            "on the task timeline, grouped into phases (setup, feeders, vision, boards, place, "
            "inspect, rework), so name each step so that its phase is clear. Keep replies short, "
            "in the language the user writes in."
        )

    def start(self):
        exe = shutil.which("claude")
        if not exe:
            raise RuntimeError("The claude command was not found. Install Claude Code first.")
        cmd = [exe, "-p", "--input-format", "stream-json", "--output-format", "stream-json",
               "--verbose", "--include-partial-messages", "--permission-prompt-tool", "stdio",
               "--append-system-prompt", self.system_prompt()]
        if self.session_id:
            cmd += ["--resume", self.session_id]
        self.proc = subprocess.Popen(cmd, cwd=PROJECT_DIR, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        threading.Thread(target=self._read, args=(self.proc,), daemon=True).start()
        threading.Thread(target=self._read_errors, args=(self.proc,), daemon=True).start()
        self._write({"type": "control_request", "request_id": "init",
                     "request": {"subtype": "initialize", "hooks": None}})

    def _write(self, obj):
        self.proc.stdin.write((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))
        self.proc.stdin.flush()

    def _read(self, proc):
        for raw in proc.stdout:
            try:
                msg = json.loads(raw.decode("utf-8"))
            except ValueError:
                continue
            kind = msg.get("type")
            if kind == "control_request":
                self._permission(msg)
                continue
            if kind == "control_response" or kind == "rate_limit_event":
                continue
            if kind == "system" and msg.get("subtype") == "init":
                self.session_id = msg.get("session_id")
            if kind == "result":
                self.busy = False
            self.emit(msg)
        self.busy = False
        self.emit({"type": "ui", "subtype": "exited", "code": proc.wait()})

    def _read_errors(self, proc):
        for raw in proc.stderr:
            text = raw.decode("utf-8", "replace").strip()
            if text:
                self.emit({"type": "ui", "subtype": "stderr", "text": text})

    def _permission(self, msg):
        request = msg.get("request") or {}
        if request.get("subtype") != "can_use_tool":
            return
        request_id = msg["request_id"]
        event = {"type": "ui", "subtype": "permission", "request_id": request_id,
                 "tool_name": request.get("tool_name"), "input": request.get("input"),
                 "tool_use_id": request.get("tool_use_id"),
                 "description": request.get("description")}
        # update_plan only draws the task timeline, so it never needs asking.
        if self.auto_approve or str(request.get("tool_name", "")).endswith("__update_plan"):
            self.emit(event)
            self.answer(request_id, True, auto=True, input=request.get("input"))
            return
        with self.lock:
            self.pending[request_id] = request
        self.emit(event)

    def answer(self, request_id, allow, message=None, auto=False, input=None):
        with self.lock:
            request = self.pending.pop(request_id, None)
        if request is None and not auto:
            return False
        if allow:
            response = {"behavior": "allow",
                        "updatedInput": input if input is not None else (request or {}).get("input")}
        else:
            response = {"behavior": "deny", "message": message or "The user denied this in the UI."}
        self._write({"type": "control_response",
                     "response": {"subtype": "success", "request_id": request_id,
                                  "response": response}})
        self.emit({"type": "ui", "subtype": "permission_answer", "request_id": request_id,
                   "allow": allow, "auto": auto})
        return True

    # -- page actions --------------------------------------------------------------------------

    def send(self, text, images=()):
        """images: [{"media_type": "image/jpeg", "data": base64}], e.g. a camera frame."""
        if self.proc is None or self.proc.poll() is not None:
            self.start()
        self.busy = True
        self.emit({"type": "ui", "subtype": "user_text", "text": text, "images": list(images)})
        content = [{"type": "text", "text": text}]
        for image in images:
            content.append({"type": "image", "source": {"type": "base64",
                                                        "media_type": image["media_type"],
                                                        "data": image["data"]}})
        self._write({"type": "user", "message": {"role": "user", "content": content},
                     "parent_tool_use_id": None, "session_id": "default"})

    def interrupt(self):
        if self.proc and self.proc.poll() is None:
            self._write({"type": "control_request", "request_id": f"int{time.time()}",
                         "request": {"subtype": "interrupt"}})

    def reset(self):
        if self.proc and self.proc.poll() is None:
            self.proc.kill()
        self.proc = None
        self.session_id = None
        self.busy = False
        with self.lock:
            self.pending.clear()
        self.emit({"type": "ui", "subtype": "reset"})

    def state(self):
        with self.lock:
            pending = list(self.pending)
        return {"autoApprove": self.auto_approve, "busy": self.busy,
                "running": self.proc is not None and self.proc.poll() is None,
                "sessionId": self.session_id, "pending": pending, "sim": self.sim}


def read_actions(config_dir, limit):
    """The last actions from actions.jsonl, oldest first, without the bulky parts."""
    path = config_dir / "ai-bridge" / "actions.jsonl"
    try:
        with open(path, "rb") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - 400_000))
            lines = f.read().decode("utf-8", "replace").splitlines()
    except OSError:
        return []
    actions = []
    for line in lines[-limit:]:
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        request = entry.get("request") or {}
        result = entry.get("result") or {}
        actions.append({
            "time": entry.get("time"),
            "action": entry.get("action"),
            "reason": request.get("reason"),
            "status": result.get("status"),
            "error": result.get("error") or result.get("message"),
            "request": {k: v for k, v in request.items() if k not in ("reason", "confirm")},
        })
    return actions


def last_job_file(config_dir):
    """The job file of the most recent action that named one, e.g. place_from_job."""
    for action in reversed(read_actions(config_dir, ACTION_LOG_LIMIT)):
        file = (action.get("request") or {}).get("file")
        if file and str(file).lower().endswith(".job.xml"):
            return file
    return None


def make_handler(bridge, chat, config_dir):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def send_json(self, obj, code=200):
            body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def read_body(self):
            length = int(self.headers.get("Content-Length") or 0)
            return json.loads(self.rfile.read(length) or b"{}")

        def do_GET(self):
            url = urllib.parse.urlsplit(self.path)
            query = urllib.parse.parse_qs(url.query)
            try:
                if url.path in ("/", "/index.html"):
                    body = UI_FILE.read_bytes()
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html; charset=utf-8")
                    self.send_header("Content-Length", str(len(body)))
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    self.wfile.write(body)
                elif url.path.startswith("/api/view/"):
                    self.forward("viewPort", url.path[len("/api/view"):], url.query)
                elif url.path.startswith("/api/bridge/"):
                    self.forward("port", url.path[len("/api/bridge"):], url.query)
                elif url.path == "/api/layout":
                    self.send_layout(query.get("file", [None])[0])
                elif url.path == "/api/actions":
                    limit = int(query.get("limit", [ACTION_LOG_LIMIT])[0])
                    self.send_json({"status": "ok", "actions": read_actions(config_dir, limit)})
                elif url.path == "/api/chat/state":
                    self.send_json(chat.state())
                elif url.path == "/api/chat/events":
                    self.stream_events(int(query.get("after", ["0"])[0]))
                else:
                    self.send_json({"status": "error", "error": "not found"}, 404)
            except OSError:
                pass  # the page went away, e.g. a closed camera stream or event stream

        def do_POST(self):
            url = urllib.parse.urlsplit(self.path)
            try:
                body = self.read_body()
                if url.path == "/api/chat/send":
                    text = (body.get("text") or "").strip()
                    if not text:
                        return self.send_json({"status": "error", "error": "empty"}, 400)
                    chat.send(text, body.get("images") or [])
                elif url.path == "/api/chat/permission":
                    if not chat.answer(body["request_id"], bool(body.get("allow")),
                                       body.get("message")):
                        return self.send_json({"status": "error", "error": "Already answered."}, 409)
                elif url.path == "/api/chat/interrupt":
                    chat.interrupt()
                elif url.path == "/api/chat/reset":
                    chat.reset()
                elif url.path == "/api/chat/settings":
                    if "autoApprove" in body:
                        chat.auto_approve = bool(body["autoApprove"])
                else:
                    return self.send_json({"status": "error", "error": "not found"}, 404)
                self.send_json({"status": "ok", **chat.state()})
            except Exception as e:  # report to the page instead of dropping the connection
                self.send_json({"status": "error", "error": str(e)}, 500)

        def send_layout(self, file):
            """The job open in OpenPnP's Job tab; if that has no boards, the job file the
            bridge used last (the bridge loads job files itself, not through the Job tab)."""
            def fetch(f):
                path = "/job/layout" + ("?file=" + urllib.parse.quote(f) if f else "")
                with bridge.open("viewPort", path, timeout=30) as resp:
                    return json.loads(resp.read())
            try:
                layout = fetch(file)
                source = "file" if file else "jobTab"
                if not file and not layout.get("boards"):
                    last = last_job_file(config_dir)
                    if last:
                        layout = fetch(last)
                        source = "lastAction"
                layout["source"] = source
                self.send_json(layout)
            except urllib.error.HTTPError as e:
                self.send_json({"status": "error", "error": e.read().decode("utf-8", "replace")}, e.code)
            except (OSError, ConnectionError, ValueError) as e:
                self.send_json({"status": "error", "error": f"Cannot reach OpenPnP: {e}"}, 502)

        def forward(self, port_key, path, query):
            target = path + ("?" + query if query else "")
            streaming = path == "/camera/stream"
            try:
                resp = bridge.open(port_key, target, timeout=None if streaming else 30)
            except urllib.error.HTTPError as e:
                body = e.read()
                self.send_response(e.code)
                self.send_header("Content-Type", e.headers.get("Content-Type", "application/json"))
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            except (OSError, ConnectionError) as e:
                return self.send_json({"status": "error", "error": f"Cannot reach OpenPnP: {e}"}, 502)
            with resp:
                self.send_response(resp.status)
                self.send_header("Content-Type", resp.headers.get("Content-Type", ""))
                self.send_header("Cache-Control", "no-store")
                if streaming:
                    self.send_header("Connection", "close")
                    self.end_headers()
                    while True:
                        chunk = resp.read1(65536)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        self.wfile.flush()
                    self.close_connection = True
                else:
                    body = resp.read()
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)

        def stream_events(self, after):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            while True:
                events = chat.wait_events(after)
                if not events:
                    self.wfile.write(b": keep-alive\n\n")
                    self.wfile.flush()
                    continue
                for number, event in events:
                    data = json.dumps(event, ensure_ascii=False)
                    self.wfile.write(f"id: {number}\ndata: {data}\n\n".encode("utf-8"))
                    after = number
                self.wfile.flush()

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--sim", action="store_true", help="use the simulator configuration")
    parser.add_argument("--port", type=int, default=8990)
    args = parser.parse_args()
    home = Path(os.environ.get("USERPROFILE") or Path.home())
    config_dir = Path(os.environ.get("OPENPNP_CONFIG_DIR")
                      or (home / ("openpnp-sim" if args.sim else ".openpnp2")))
    bridge = Bridge(config_dir)
    chat = Chat(args.sim)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(bridge, chat, config_dir))
    server.daemon_threads = True
    print(f"OpenPnP UI ({'simulator' if args.sim else 'real machine'}, {config_dir}): "
          f"http://127.0.0.1:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        chat.reset()


if __name__ == "__main__":
    sys.exit(main())
