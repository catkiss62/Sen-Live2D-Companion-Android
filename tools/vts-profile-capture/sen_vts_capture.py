"""Sen VTube Studio parameter capture tool.

This tool talks only to VTube Studio's documented public WebSocket API. It does
not inspect, patch, or redistribute VTube Studio itself.
"""

from __future__ import annotations

import json
import os
import queue
import statistics
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from tkinter import END, LEFT, BOTH, DISABLED, NORMAL, StringVar, Text, Tk, filedialog, messagebox
from tkinter import ttk

import websocket


TOOL_VERSION = "0.1.0"
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
                continue
            if reply.get("messageType") == "APIError":
                details = reply.get("data") or {}
                raise VTSAPIError(
                    f"VTS API 错误 {details.get('errorID', '?')}："
                    f"{details.get('message', '未知错误')}"
                )
            return reply.get("data") or {}

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
