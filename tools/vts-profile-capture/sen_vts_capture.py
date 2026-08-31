"""Sen VTube Studio parameter capture tool.

This tool talks only to VTube Studio's documented public WebSocket API. It does
not inspect, patch, or redistribute VTube Studio itself.
"""

from __future__ import annotations

import base64
import json
import os
import queue
import struct
import statistics
import threading
import time
import uuid
import zlib
from datetime import datetime, timezone
from pathlib import Path
from tkinter import END, LEFT, BOTH, DISABLED, NORMAL, StringVar, Text, Tk, filedialog, messagebox
from tkinter import ttk

import websocket


TOOL_VERSION = "0.2.0"
API_NAME = "VTubeStudioPublicAPI"
API_VERSION = "1.0"
DEFAULT_URL = "ws://localhost:8001"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def config_path() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home())
    return Path(base) / "SenVTSParameterCapture" / "config.json"


class VTSAPIError(RuntimeError):
    pass


class VTSClient:
    def __init__(self, url: str):
        self.url = url
        self.socket: websocket.WebSocket | None = None
        self.pending_events: list[dict] = []

    def connect(self) -> None:
        self.close()
        self.socket = websocket.create_connection(
            self.url,
            timeout=35,
            http_proxy_host=None,
            http_proxy_port=None,
        )

    def close(self) -> None:
        if self.socket is not None:
            try:
                self.socket.close()
            except Exception:
                pass
        self.socket = None

    def request(self, message_type: str, data: dict | None = None) -> dict:
        if self.socket is None:
            raise VTSAPIError("尚未连接 VTube Studio")
        request_id = uuid.uuid4().hex
        payload = {
            "apiName": API_NAME,
            "apiVersion": API_VERSION,
            "requestID": request_id,
            "messageType": message_type,
            "data": data or {},
        }
        self.socket.send(json.dumps(payload, ensure_ascii=False))
        while True:
            reply = json.loads(self.socket.recv())
            if reply.get("requestID") != request_id:
                if str(reply.get("messageType", "")).endswith("Event"):
                    self.pending_events.append(reply)
                continue
            if reply.get("messageType") == "APIError":
                details = reply.get("data") or {}
                raise VTSAPIError(
                    f"VTS API 错误 {details.get('errorID', '?')}："
                    f"{details.get('message', '未知错误')}"
                )
            return reply.get("data") or {}

    def wait_for_event(self, event_name: str, timeout_seconds: float = 120.0) -> dict:
        if self.socket is None:
            raise VTSAPIError("尚未连接 VTube Studio")
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            for index, event in enumerate(self.pending_events):
                if event.get("messageType") == event_name:
                    return self.pending_events.pop(index).get("data") or {}
            remaining = deadline - time.monotonic()
            self.socket.settimeout(max(0.1, min(1.0, remaining)))
            try:
                message = json.loads(self.socket.recv())
            except websocket.WebSocketTimeoutException:
                continue
            if message.get("messageType") == event_name:
                self.socket.settimeout(35)
                return message.get("data") or {}
            if str(message.get("messageType", "")).endswith("Event"):
                self.pending_events.append(message)
        self.socket.settimeout(35)
        raise VTSAPIError("等待VTube Studio模型点击超时，请重新开始采集")

    def authenticate(self) -> None:
        path = config_path()
        token = ""
        if path.is_file():
            try:
                token = json.loads(path.read_text(encoding="utf-8")).get("token", "")
            except Exception:
                token = ""

        if token:
            try:
                result = self.request(
                    "AuthenticationRequest",
                    {
                        "pluginName": "Sen VTS Parameter Capture",
                        "pluginDeveloper": "catkiss62",
                        "authenticationToken": token,
                    },
                )
                if result.get("authenticated"):
                    return
            except VTSAPIError:
                pass

        token_result = self.request(
            "AuthenticationTokenRequest",
            {
                "pluginName": "Sen VTS Parameter Capture",
                "pluginDeveloper": "catkiss62",
                "pluginIcon": "",
            },
        )
        token = token_result.get("authenticationToken", "")
        if not token:
            raise VTSAPIError("VTube Studio 没有返回授权令牌")

        auth = self.request(
            "AuthenticationRequest",
            {
                "pluginName": "Sen VTS Parameter Capture",
                "pluginDeveloper": "catkiss62",
                "authenticationToken": token,
            },
        )
        if not auth.get("authenticated"):
            raise VTSAPIError("VTube Studio 未允许采集工具连接")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"token": token}, indent=2), encoding="utf-8")


def median_parameters(samples: list[list[dict]]) -> list[dict]:
    grouped: dict[str, list[float]] = {}
    metadata: dict[str, dict] = {}
    for sample in samples:
        for parameter in sample:
            name = str(parameter.get("name", ""))
            if not name:
                continue
            grouped.setdefault(name, []).append(float(parameter.get("value", 0.0)))
            metadata[name] = dict(parameter)
    result = []
    for name in sorted(grouped):
        item = metadata[name]
        item["value"] = statistics.median(grouped[name])
        result.append(item)
    return result


def clean_art_mesh_hit(hit: dict) -> dict:
    info = hit.get("hitInfo") or {}
    return {
        "artMeshOrder": int(hit.get("artMeshOrder", 0)),
        "isMasked": bool(hit.get("isMasked", False)),
        "hitInfo": {
            "modelID": str(info.get("modelID", "")),
            "artMeshID": str(info.get("artMeshID", "")),
            "angle": float(info.get("angle", 0.0)),
            "size": float(info.get("size", 1.0)),
            "vertexID1": int(info.get("vertexID1", -1)),
            "vertexID2": int(info.get("vertexID2", -1)),
            "vertexID3": int(info.get("vertexID3", -1)),
            "vertexWeight1": float(info.get("vertexWeight1", 0.0)),
            "vertexWeight2": float(info.get("vertexWeight2", 0.0)),
            "vertexWeight3": float(info.get("vertexWeight3", 0.0)),
        },
    }


def choose_ahoge_hit(hits: list[dict]) -> dict | None:
    if not hits:
        return None
    preferred = ("artmesh140_skinning2", "artmesh140_skinning")
    for preferred_id in preferred:
        for hit in hits:
            mesh_id = str((hit.get("hitInfo") or {}).get("artMeshID", "")).lower()
            if mesh_id == preferred_id:
                return hit
    return min(hits, key=lambda value: int(value.get("artMeshOrder", 0)))


def ordered_ahoge_hits(hits: list[dict]) -> list[dict]:
    suggested = choose_ahoge_hit(hits)
    ordered = sorted(hits, key=lambda value: int(value.get("artMeshOrder", 0)))
    if suggested is None:
        return ordered
    return [suggested] + [hit for hit in ordered if hit is not suggested]


def marker_png_base64() -> str:
    """Build a disposable 64px cyan target marker using only the standard library."""
    size = 64
    rows = []
    center = (size - 1) / 2.0
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            distance = ((x - center) ** 2 + (y - center) ** 2) ** 0.5
            ring = 20.0 <= distance <= 27.0
            cross = abs(x - center) <= 2.0 or abs(y - center) <= 2.0
            if ring or cross:
                row.extend((0, 255, 255, 235))
            else:
                row.extend((0, 0, 0, 0))
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    return base64.b64encode(png).decode("ascii")


class CaptureApp:
    def __init__(self, root: Tk):
        self.root = root
        self.root.title("Sen · VTube Studio 参数采集器")
        self.root.geometry("820x680")
        self.root.minsize(720, 560)

        self.events: queue.Queue[tuple[str, object]] = queue.Queue()
        self.client: VTSClient | None = None
        self.connected = False
        self.busy = False
        self.model: dict = {}
        self.snapshots: list[dict] = []
        self.stats: dict = {}
        self.ahoge_anchor: dict = {}
        self.ahoge_verify_index = 0

        self.url_var = StringVar(value=DEFAULT_URL)
        self.name_var = StringVar(value="正常待机")
        self.status_var = StringVar(value="未连接")
        self.model_var = StringVar(value="尚未读取模型")

        self._build_ui()
        self.root.after(100, self._drain_events)
        self.root.protocol("WM_DELETE_WINDOW", self._close)

    def _build_ui(self) -> None:
        outer = ttk.Frame(self.root, padding=14)
        outer.pack(fill=BOTH, expand=True)

        ttk.Label(outer, text="Sen · VTube Studio 参数采集器", font=("Microsoft YaHei UI", 17, "bold")).pack(anchor="w")
        ttk.Label(
            outer,
            text=(
                "先在电脑上启动 VTube Studio 并载入 Sen。工具只使用官方 WebSocket API，"
                "第一次连接时请在 VTube Studio 中允许插件访问。"
            ),
            wraplength=770,
        ).pack(anchor="w", pady=(5, 12))

        connection = ttk.LabelFrame(outer, text="1. 连接 VTube Studio", padding=10)
        connection.pack(fill="x")
        ttk.Label(connection, text="API 地址").pack(side=LEFT)
        self.url_entry = ttk.Entry(connection, textvariable=self.url_var, width=31)
        self.url_entry.pack(side=LEFT, padx=8)
        self.connect_button = ttk.Button(connection, text="连接并授权", command=self.connect)
        self.connect_button.pack(side=LEFT)
        ttk.Label(connection, textvariable=self.status_var).pack(side=LEFT, padx=12)

        capture = ttk.LabelFrame(outer, text="2. 采集当前状态", padding=10)
        capture.pack(fill="x", pady=10)
        ttk.Label(capture, textvariable=self.model_var, wraplength=760).pack(anchor="w")
        row = ttk.Frame(capture)
        row.pack(fill="x", pady=(9, 2))
        ttk.Label(row, text="状态名称").pack(side=LEFT)
        self.name_entry = ttk.Entry(row, textvariable=self.name_var, width=28)
        self.name_entry.pack(side=LEFT, padx=8)
        self.capture_button = ttk.Button(row, text="采集当前参数", command=self.capture, state=DISABLED)
        self.capture_button.pack(side=LEFT)
        self.save_button = ttk.Button(row, text="保存参数包", command=self.save, state=DISABLED)
        self.save_button.pack(side=LEFT, padx=8)

        ttk.Label(
            capture,
            text=(
                "建议先采集“正常待机”；之后在 VTS 中开启一个表情或动作，再填写名称并采集。"
                "每次会连续读取9帧并取中位数，减少眨眼、呼吸和物理抖动造成的偶然误差。"
            ),
            wraplength=760,
        ).pack(anchor="w", pady=(5, 0))

        anchor = ttk.LabelFrame(outer, text="3. 呆毛根部精确采集", padding=10)
        anchor.pack(fill="x", pady=(0, 10))
        ttk.Label(
            anchor,
            text=(
                "点击采集后回到VTube Studio：先左键点长呆毛与头皮相接的根部，"
                "再点根部旁边的一处头皮作为方向参考。工具会保存所有重叠ArtMesh的三角形与重心权重。"
            ),
            wraplength=760,
        ).pack(anchor="w")
        anchor_row = ttk.Frame(anchor)
        anchor_row.pack(fill="x", pady=(8, 0))
        self.anchor_button = ttk.Button(
            anchor_row, text="采集根部+方向点", command=self.capture_ahoge_anchor,
            state=DISABLED)
        self.anchor_button.pack(side=LEFT)
        self.verify_anchor_button = ttk.Button(
            anchor_row, text="在VTS验证下一个候选", command=self.verify_ahoge_anchor,
            state=DISABLED)
        self.verify_anchor_button.pack(side=LEFT, padx=8)
        self.save_anchor_button = ttk.Button(
            anchor_row, text="保存呆毛锚点包", command=self.save_ahoge_anchor,
            state=DISABLED)
        self.save_anchor_button.pack(side=LEFT)

        log_box = ttk.LabelFrame(outer, text="采集记录", padding=8)
        log_box.pack(fill=BOTH, expand=True)
        self.log = Text(log_box, wrap="word", height=18, font=("Consolas", 10))
        self.log.pack(fill=BOTH, expand=True)
        self._log("等待连接。不会修改 VTube Studio 或模型文件。")

    def _set_busy(self, busy: bool) -> None:
        self.busy = busy
        state = DISABLED if busy else NORMAL
        self.connect_button.configure(state=state)
        self.url_entry.configure(state=state)
        self.name_entry.configure(state=state)
        self.capture_button.configure(state=NORMAL if self.connected and not busy else DISABLED)
        self.save_button.configure(state=NORMAL if self.snapshots and not busy else DISABLED)
        self.anchor_button.configure(state=NORMAL if self.connected and not busy else DISABLED)
        anchor_state = NORMAL if self.ahoge_anchor and not busy else DISABLED
        self.verify_anchor_button.configure(state=anchor_state)
        self.save_anchor_button.configure(state=anchor_state)

    def _log(self, text: str) -> None:
        self.log.insert(END, f"[{time.strftime('%H:%M:%S')}] {text}\n")
        self.log.see(END)

    def _worker(self, action, *args) -> None:
        self._set_busy(True)

        def run():
            try:
                value = action(*args)
                self.events.put(("success", value))
            except Exception as exc:
                self.events.put(("error", exc))

        threading.Thread(target=run, daemon=True).start()

    def _drain_events(self) -> None:
        try:
            while True:
                kind, value = self.events.get_nowait()
                self._set_busy(False)
                if kind == "error":
                    self.status_var.set("操作失败")
                    self._log(f"失败：{value}")
                    messagebox.showerror("操作失败", str(value))
                elif isinstance(value, tuple) and value and value[0] == "connected":
                    _, model, stats = value
                    self.connected = True
                    self.model = model
                    self.stats = stats
                    self.status_var.set("已连接")
                    self.model_var.set(
                        f"当前模型：{model.get('modelName', '未知')} · "
                        f"参数 {model.get('numberOfLive2DParameters', '?')} · "
                        f"贴图 {model.get('numberOfTextures', '?')}"
                    )
                    self._log("连接成功，已读取当前模型。")
                    self._set_busy(False)
                elif isinstance(value, tuple) and value and value[0] == "captured":
                    snapshot = value[1]
                    self.snapshots = [s for s in self.snapshots if s.get("name") != snapshot.get("name")]
                    self.snapshots.append(snapshot)
                    self._log(
                        f"已采集“{snapshot['name']}”：Live2D 参数 "
                        f"{len(snapshot['live2DParameters'])}，活动表情 "
                        f"{sum(1 for e in snapshot.get('expressions', []) if e.get('active'))}。"
                    )
                    self.status_var.set(f"已采集 {len(self.snapshots)} 个状态")
                    self._set_busy(False)
                elif isinstance(value, tuple) and value and value[0] == "ahoge_anchor":
                    self.ahoge_anchor = value[1]
                    self.ahoge_verify_index = 0
                    root_hits = self.ahoge_anchor["points"][0]["artMeshHits"]
                    direction_hits = self.ahoge_anchor["points"][1]["artMeshHits"]
                    chosen = self.ahoge_anchor.get("suggestedRootHit") or {}
                    chosen_id = (chosen.get("hitInfo") or {}).get("artMeshID", "无")
                    self._log(
                        f"呆毛锚点已采集：根部命中{len(root_hits)}层，方向点命中"
                        f"{len(direction_hits)}层；建议验证 {chosen_id}。"
                    )
                    self.status_var.set("呆毛根部锚点已采集")
                    self._set_busy(False)
                elif isinstance(value, tuple) and value and value[0] == "ahoge_verified":
                    self._log(
                        f"候选 {value[2]}/{value[3]}：临时青色标记已钉到 {value[1]}。"
                        "请移动头部观察；正确就直接保存锚点包，错误则继续验证下一个候选。"
                    )
                    self.status_var.set("已放置根部验证标记")
                    self._set_busy(False)
        except queue.Empty:
            pass
        self.root.after(100, self._drain_events)

    def connect(self) -> None:
        self._log("正在连接；若 VTube Studio 弹出授权提示，请点允许。")
        self._worker(self._connect_impl, self.url_var.get().strip() or DEFAULT_URL)

    def _connect_impl(self, url: str):
        client = VTSClient(url)
        client.connect()
        client.authenticate()
        stats = client.request("StatisticsRequest")
        model = client.request("CurrentModelRequest")
        if not model.get("modelLoaded"):
            client.close()
            raise VTSAPIError("VTube Studio 当前没有载入模型，请先载入 Sen")
        if self.client is not None:
            self.client.close()
        self.client = client
        return "connected", model, stats

    def capture(self) -> None:
        name = self.name_var.get().strip()
        if not name:
            messagebox.showinfo("需要名称", "请先填写状态名称，例如“正常待机”或“害羞”。")
            return
        self._log(f"正在采集“{name}”的9帧参数…")
        self._worker(self._capture_impl, name)

    def _optional_request(self, message_type: str, data: dict | None = None):
        try:
            return self.client.request(message_type, data) if self.client else {}
        except Exception as exc:
            return {"captureError": str(exc)}

    def _capture_impl(self, name: str):
        if self.client is None:
            raise VTSAPIError("尚未连接 VTube Studio")
        model = self.client.request("CurrentModelRequest")
        if not model.get("modelLoaded"):
            raise VTSAPIError("采集时模型已经卸载")

        samples = []
        for index in range(9):
            data = self.client.request("Live2DParameterListRequest")
            samples.append(data.get("parameters") or [])
            if index < 8:
                time.sleep(0.1)

        input_parameters = self._optional_request("InputParameterListRequest")
        expression_state = self._optional_request("ExpressionStateRequest")
        hotkeys = self._optional_request("HotkeysInCurrentModelRequest", {"modelID": model.get("modelID", "")})
        physics = self._optional_request("GetCurrentModelPhysicsRequest")
        snapshot = {
            "name": name,
            "capturedAt": utc_now(),
            "sourceSamples": 9,
            "modelID": model.get("modelID", ""),
            "live2DParameters": median_parameters(samples),
            "inputParameters": input_parameters,
            "expressions": expression_state.get("expressions", []),
            "hotkeys": hotkeys.get("availableHotkeys", []),
            "physics": physics,
        }
        return "captured", snapshot

    def capture_ahoge_anchor(self) -> None:
        self._log("等待VTS点击：第1次点呆毛根部，第2次点旁边头皮方向点（均用左键）。")
        self.status_var.set("请回到VTS依次点击两个点")
        self._worker(self._capture_ahoge_anchor_impl)

    def _capture_ahoge_anchor_impl(self):
        if self.client is None:
            raise VTSAPIError("尚未连接 VTube Studio")
        model = self.client.request("CurrentModelRequest")
        if not model.get("modelLoaded"):
            raise VTSAPIError("VTube Studio 当前没有载入模型")
        self.client.pending_events = [
            event for event in self.client.pending_events
            if event.get("messageType") != "ModelClickedEvent"
        ]
        self.client.request(
            "EventSubscriptionRequest",
            {"eventName": "ModelClickedEvent", "subscribe": True,
             "config": {"onlyClicksOnModel": True}},
        )
        points = []
        labels = ("root", "direction")
        try:
            while len(points) < 2:
                event = self.client.wait_for_event("ModelClickedEvent", 120.0)
                if event.get("mouseButtonID") != 0 or not event.get("modelWasClicked"):
                    continue
                hits = [clean_art_mesh_hit(hit) for hit in (event.get("artMeshHits") or [])]
                if not hits:
                    continue
                points.append({
                    "role": labels[len(points)],
                    "capturedAt": utc_now(),
                    "clickPosition": event.get("clickPosition") or {},
                    "windowSize": event.get("windowSize") or {},
                    "artMeshHits": hits,
                })
        finally:
            self._optional_request(
                "EventSubscriptionRequest",
                {"eventName": "ModelClickedEvent", "subscribe": False, "config": {}},
            )
        suggested = choose_ahoge_hit(points[0]["artMeshHits"])
        payload = {
            "schema": "sen-ahoge-anchor",
            "schemaVersion": 1,
            "toolVersion": TOOL_VERSION,
            "createdAt": utc_now(),
            "source": "VTube Studio Public API ModelClickedEvent",
            "model": {
                "modelID": model.get("modelID", ""),
                "modelName": model.get("modelName", ""),
                "modelLoadTime": model.get("modelLoadTime", 0),
            },
            "points": points,
            "suggestedRootHit": suggested,
        }
        return "ahoge_anchor", payload

    def verify_ahoge_anchor(self) -> None:
        if not self.ahoge_anchor:
            return
        self._log("正在放置下一个青色根部候选标记；首次使用若VTS弹窗，请允许临时图片权限。")
        self._worker(self._verify_ahoge_anchor_impl)

    def _verify_ahoge_anchor_impl(self):
        if self.client is None:
            raise VTSAPIError("尚未连接 VTube Studio")
        hits = ordered_ahoge_hits(self.ahoge_anchor["points"][0]["artMeshHits"])
        if not hits:
            raise VTSAPIError("根部点击没有可验证的ArtMesh")
        candidate_index = self.ahoge_verify_index % len(hits)
        hit = hits[candidate_index]
        info = hit.get("hitInfo") or {}
        if not info.get("artMeshID"):
            raise VTSAPIError("根部点击没有可验证的ArtMesh")
        permission = self.client.request(
            "PermissionRequest", {"requestedPermission": "LoadCustomImagesAsItems"})
        granted = any(
            item.get("name") == "LoadCustomImagesAsItems" and item.get("granted")
            for item in permission.get("permissions", [])
        )
        if not granted:
            raise VTSAPIError("未获得加载临时验证标记的权限")
        self._optional_request("ItemUnloadRequest", {
            "unloadAllInScene": False,
            "unloadAllLoadedByThisPlugin": True,
            "allowUnloadingItemsLoadedByUserOrOtherPlugins": False,
        })
        loaded = self.client.request("ItemLoadRequest", {
            "fileName": "sen-anchor-marker.png",
            "positionX": 0.0,
            "positionY": 0.0,
            "size": 0.05,
            "rotation": 0.0,
            "fadeTime": 0.15,
            "order": 100,
            "failIfOrderTaken": False,
            "smoothing": 0.0,
            "censored": False,
            "flipped": False,
            "locked": True,
            "unloadWhenPluginDisconnects": True,
            "customDataBase64": marker_png_base64(),
            "customDataAskUserFirst": False,
            "customDataSkipAskingUserIfWhitelisted": True,
            "customDataAskTimer": -1,
        })
        instance_id = loaded.get("instanceID", "")
        if not instance_id:
            raise VTSAPIError("VTS没有返回临时标记实例ID")
        pin_info = {
            "modelID": info.get("modelID", ""),
            "artMeshID": info.get("artMeshID", ""),
            "angle": 0.0,
            "size": 0.05,
        }
        for name in (
                "vertexID1", "vertexID2", "vertexID3",
                "vertexWeight1", "vertexWeight2", "vertexWeight3"):
            pin_info[name] = info.get(name)
        self.client.request("ItemPinRequest", {
            "pin": True,
            "itemInstanceID": instance_id,
            "angleRelativeTo": "RelativeToWorld",
            "sizeRelativeTo": "RelativeToWorld",
            "vertexPinType": "Provided",
            "pinInfo": pin_info,
        })
        self.ahoge_anchor["selectedRootHit"] = hit
        self.ahoge_anchor["selectedRootHitReason"] = "last successfully pinned candidate"
        self.ahoge_verify_index = candidate_index + 1
        return ("ahoge_verified", info.get("artMeshID", ""),
                candidate_index + 1, len(hits))

    def save_ahoge_anchor(self) -> None:
        if not self.ahoge_anchor:
            return
        path = filedialog.asksaveasfilename(
            title="保存 Sen 呆毛锚点包",
            defaultextension=".json",
            initialfile="Sen.ahoge-anchor.json",
            filetypes=[("Sen 呆毛锚点包", "*.json"), ("全部文件", "*.*")],
        )
        if not path:
            return
        Path(path).write_text(
            json.dumps(self.ahoge_anchor, ensure_ascii=False, indent=2), encoding="utf-8")
        self._log(f"呆毛锚点包已保存：{path}")
        messagebox.showinfo("保存成功", "请把 Sen.ahoge-anchor.json 发给我。")

    def save(self) -> None:
        if not self.snapshots:
            return
        default_name = "Sen.vts-profile.json"
        path = filedialog.asksaveasfilename(
            title="保存 Sen VTS 参数包",
            defaultextension=".json",
            initialfile=default_name,
            filetypes=[("Sen VTS 参数包", "*.json"), ("全部文件", "*.*")],
        )
        if not path:
            return
        payload = {
            "schema": "sen-vts-profile",
            "schemaVersion": 1,
            "toolVersion": TOOL_VERSION,
            "createdAt": utc_now(),
            "source": "VTube Studio Public API",
            "vTubeStudio": self.stats,
            "model": self.model,
            "snapshots": self.snapshots,
        }
        Path(path).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        self._log(f"参数包已保存：{path}")
        messagebox.showinfo("保存成功", "参数包已经保存。之后把这个 JSON 发给我即可。")

    def _close(self) -> None:
        if self.client is not None:
            self.client.close()
        self.root.destroy()


def main() -> None:
    root = Tk()
    try:
        ttk.Style(root).theme_use("vista")
    except Exception:
        pass
    CaptureApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
