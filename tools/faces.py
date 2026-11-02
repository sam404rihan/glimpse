"""Face detection + recognition on the PC: the reference the phone's models are checked against,
and the engine behind pc_helper.py.

Uses InsightFace's buffalo_m pack (free for non-commercial use only):
  * det_2.5g.onnx  - SCRFD-2.5GF face detector (boxes + 5 landmarks)
  * w600k_r50.onnx - ArcFace ResNet-50 recogniser (512-d identity embedding)
Downloaded once into <repo>/models/buffalo_m. With PyTorch and onnx2torch installed the graphs run
on the GPU; otherwise on the CPU through onnxruntime.
"""
from __future__ import annotations

import os
import threading
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

MODEL_DIR = Path(os.environ.get("GLIMPSE_FACE_MODELS") or Path(__file__).resolve().parents[1] / "models" / "buffalo_m")
PACK_URL = "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_m.zip"
DET_FILE, REC_FILE = "det_2.5g.onnx", "w600k_r50.onnx"

DET_SIZE = 640
DET_THRESH = 0.5
NMS_THRESH = 0.4
MIN_FACE_PX = 20  # smaller faces give unreliable identities

# Where ArcFace expects the eyes, nose and mouth corners in its 112x112 input.
ARCFACE_DST = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366],
                        [41.5493, 92.3655], [70.7299, 92.2041]], dtype=np.float32)


@dataclass
class Face:
    box: np.ndarray  # x1, y1, x2, y2 in pixels of the analysed image
    score: float
    kps: np.ndarray  # 5 x 2 landmarks
    emb: np.ndarray | None = None  # unit-length 512-d identity vector

    @property
    def size(self) -> float:
        return float(min(self.box[2] - self.box[0], self.box[3] - self.box[1]))


def ensure_models(progress=None) -> Path:
    """Download the pack once (about 260 MB) and unpack the two models used (about 180 MB)."""
    if (MODEL_DIR / DET_FILE).exists() and (MODEL_DIR / REC_FILE).exists():
        return MODEL_DIR
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    zpath = MODEL_DIR.parent / "buffalo_m.zip"
    if not zpath.exists() or not zipfile.is_zipfile(zpath):
        tmp = zpath.with_suffix(".part")
        start = tmp.stat().st_size if tmp.exists() else 0
        req = urllib.request.Request(PACK_URL, headers={"Range": f"bytes={start}-"} if start else {})
        with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "ab" if start else "wb") as f:
            total = start + int(r.headers.get("Content-Length") or 0)
            while chunk := r.read(1 << 20):
                f.write(chunk)
                if progress:
                    progress(f.tell(), total)
        os.replace(tmp, zpath)
    with zipfile.ZipFile(zpath) as z:
        for name in z.namelist():
            if os.path.basename(name) in (DET_FILE, REC_FILE):
                (MODEL_DIR / os.path.basename(name)).write_bytes(z.read(name))
    zpath.unlink()  # the other models in the pack aren't used
    return MODEL_DIR


class _Runner:
    """Runs one ONNX model: PyTorch on GPU if possible, onnxruntime on CPU otherwise."""

    def __init__(self, path: Path, device: str):
        self.torch_model = None
        if device == "cuda":
            try:
                import onnx
                import torch
                from onnx2torch import convert

                self.torch = torch
                # Pass the loaded graph: given a path, onnx2torch writes a temp file Windows won't reopen.
                self.torch_model = convert(onnx.load(str(path))).to(device).eval()
                self.device = device
            except Exception as e:
                print(f"Face model {path.name}: GPU conversion failed ({type(e).__name__}: {e}); using CPU.")
                self.torch_model = None
        if self.torch_model is None:
            import onnxruntime as ort

            opts = ort.SessionOptions()
            opts.log_severity_level = 3  # the ArcFace graph declares batch=1 but runs any batch; hush that
            self.sess = ort.InferenceSession(str(path), opts, providers=["CPUExecutionProvider"])
            self.input_name = self.sess.get_inputs()[0].name
            self.device = "cpu"

    def __call__(self, x: np.ndarray) -> list[np.ndarray]:
        if self.torch_model is not None:
            with self.torch.inference_mode():
                out = self.torch_model(self.torch.from_numpy(x).to(self.device))
            out = out if isinstance(out, (list, tuple)) else [out]
            return [o.float().cpu().numpy() for o in out]
        return self.sess.run(None, {self.input_name: x})


class FaceModel:
    def __init__(self, device: str | None = None):
        if device is None:
            try:
                import torch

                device = "cuda" if torch.cuda.is_available() else "cpu"
            except ImportError:
                device = "cpu"
        ensure_models()
        self.det = _Runner(MODEL_DIR / DET_FILE, device)
        self.rec = _Runner(MODEL_DIR / REC_FILE, device)
        self.device = self.det.device
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ detection
    def detect(self, img: Image.Image) -> list[Face]:
        rgb = np.asarray(img.convert("RGB"))
        h, w = rgb.shape[:2]
        scale = DET_SIZE / max(h, w)
        nh, nw = max(1, round(h * scale)), max(1, round(w * scale))
        canvas = np.zeros((DET_SIZE, DET_SIZE, 3), dtype=np.uint8)
        canvas[:nh, :nw] = cv2.resize(rgb, (nw, nh), interpolation=cv2.INTER_LINEAR)
        blob = ((canvas.astype(np.float32) - 127.5) / 128.0).transpose(2, 0, 1)[None]
        with self._lock:
            outs = self.det(np.ascontiguousarray(blob))
        boxes, scores, kpss = self._decode(outs)
        if not len(scores):
            return []
        keep = _nms(boxes, scores, NMS_THRESH)
        faces = []
        for i in keep:
            f = Face(boxes[i] / scale, float(scores[i]), kpss[i] / scale)
            if f.size >= MIN_FACE_PX:
                faces.append(f)
        return faces

    @staticmethod
    def _decode(outs):
        """SCRFD head: 3 strides x (scores, box distances, landmark offsets), 2 anchors per cell."""
        fmc, strides, anchors = 3, (8, 16, 32), 2
        all_boxes, all_scores, all_kps = [], [], []
        for i, stride in enumerate(strides):
            scores = outs[i].reshape(-1)
            box_d = outs[i + fmc].reshape(-1, 4) * stride
            kps_d = outs[i + 2 * fmc].reshape(-1, 10) * stride
            n = DET_SIZE // stride
            centers = np.stack(np.mgrid[:n, :n][::-1], axis=-1).reshape(-1, 2).astype(np.float32) * stride
            centers = np.repeat(centers, anchors, axis=0)
            pos = np.where(scores >= DET_THRESH)[0]
            c = centers[pos]
            b = box_d[pos]
            all_boxes.append(np.stack([c[:, 0] - b[:, 0], c[:, 1] - b[:, 1], c[:, 0] + b[:, 2], c[:, 1] + b[:, 3]], 1))
            k = kps_d[pos].reshape(-1, 5, 2) + c[:, None, :]
            all_kps.append(k)
            all_scores.append(scores[pos])
        return np.concatenate(all_boxes), np.concatenate(all_scores), np.concatenate(all_kps)

    # ------------------------------------------------------------------ recognition
    @staticmethod
    def align(rgb: np.ndarray, kps: np.ndarray) -> np.ndarray:
        m, _ = cv2.estimateAffinePartial2D(kps.astype(np.float32), ARCFACE_DST, method=cv2.LMEDS)
        return cv2.warpAffine(rgb, m, (112, 112), borderValue=0)

    def embed(self, img: Image.Image, faces: list[Face]) -> list[Face]:
        if not faces:
            return faces
        rgb = np.asarray(img.convert("RGB"))
        crops = np.stack([self.align(rgb, f.kps) for f in faces]).astype(np.float32)
        blob = ((crops - 127.5) / 127.5).transpose(0, 3, 1, 2)
        with self._lock:
            emb = self.rec(np.ascontiguousarray(blob))[0]
        emb /= np.linalg.norm(emb, axis=1, keepdims=True) + 1e-9
        for f, e in zip(faces, emb):
            f.emb = e.astype(np.float32)
        return faces

    def analyze(self, img: Image.Image) -> list[Face]:
        return self.embed(img, self.detect(img))


def _nms(boxes: np.ndarray, scores: np.ndarray, thresh: float) -> list[int]:
    x1, y1, x2, y2 = boxes.T
    areas = (x2 - x1) * (y2 - y1)
    order = scores.argsort()[::-1]
    keep = []
    while order.size:
        i = order[0]
        keep.append(int(i))
        xx1 = np.maximum(x1[i], x1[order[1:]])
        yy1 = np.maximum(y1[i], y1[order[1:]])
        xx2 = np.minimum(x2[i], x2[order[1:]])
        yy2 = np.minimum(y2[i], y2[order[1:]])
        inter = np.maximum(0, xx2 - xx1) * np.maximum(0, yy2 - yy1)
        iou = inter / (areas[i] + areas[order[1:]] - inter)
        order = order[1:][iou <= thresh]
    return keep
