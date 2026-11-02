"""The phone's face pipeline in numpy, step for step, so the PC can check the phone's models and
write golden fixtures. Mirrors app/src/main/java/app/glimpse/faces/*.kt."""
from __future__ import annotations

import os
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]  # the repository
sys.path.insert(0, str(Path(__file__).resolve().parent))
import faces  # noqa: E402  (SCRFD decode, NMS, ARCFACE_DST, thresholds)

DET_SIZE = faces.DET_SIZE
ANALYSE_SIDE = 1600
WORK = ROOT / "build" / "models"
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "models"
FACES = ROOT / "app" / "src" / "androidTest" / "assets" / "lfw"  # 120 LFW photos, 10 people
# Optional extra photos (e.g. group shots) for calibration and the many-faces decode fixture.
PHOTOS = Path(os.environ["GLIMPSE_EXTRA_PHOTOS"]) if os.environ.get("GLIMPSE_EXTRA_PHOTOS") else None


def load_image(path, size: int | None = None) -> Image.Image:
    """Opens fast (JPEG decodes at reduced scale), honours EXIF rotation, flattens odd modes to RGB."""
    img = Image.open(path)
    if size and img.format == "JPEG":
        img.draft("RGB", (size * 2, size * 2))
    img = ImageOps.exif_transpose(img)
    if img.mode in ("RGBA", "LA", "PA") or (img.mode == "P" and "transparency" in img.info):
        img = img.convert("RGBA")
        bg = Image.new("RGB", img.size, (255, 255, 255))
        bg.paste(img, mask=img.split()[-1])
        return bg
    return img.convert("RGB")


def session(path) -> ort.InferenceSession:
    return ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])


def load(path) -> np.ndarray:
    """RGB uint8 at most ANALYSE_SIDE on the long side, EXIF rotation applied (as people.py)."""
    img = load_image(path, ANALYSE_SIDE // 2)
    img.thumbnail((ANALYSE_SIDE, ANALYSE_SIDE))
    return np.asarray(img.convert("RGB"))


def letterbox(rgb):
    h, w = rgb.shape[:2]
    scale = DET_SIZE / max(h, w)
    nh, nw = max(1, round(h * scale)), max(1, round(w * scale))
    canvas = np.zeros((DET_SIZE, DET_SIZE, 3), np.uint8)
    canvas[:nh, :nw] = cv2.resize(rgb, (nw, nh), interpolation=cv2.INTER_LINEAR)
    return canvas, scale


def det_blob(canvas):
    return np.ascontiguousarray(((canvas.astype(np.float32) - 127.5) / 128.0).transpose(2, 0, 1)[None])


def det_outputs(det, rgb):
    canvas, scale = letterbox(rgb)
    return det.run(None, {det.get_inputs()[0].name: det_blob(canvas)}), scale


def detect(det, rgb) -> list:
    """Faces in rgb pixels; the same rules as faces.FaceModel.detect."""
    outs, scale = det_outputs(det, rgb)
    boxes, scores, kpss = faces.FaceModel._decode(outs)
    if not len(scores):
        return []
    found = []
    for i in faces._nms(boxes, scores, faces.NMS_THRESH):
        f = faces.Face(boxes[i] / scale, float(scores[i]), kpss[i] / scale)
        if f.size >= faces.MIN_FACE_PX:
            found.append(f)
    return found


def similarity(src, dst=faces.ARCFACE_DST) -> np.ndarray:
    """Least-squares similarity transform (rotation + uniform scale + shift) src -> dst, as 2x3."""
    src = np.asarray(src, np.float64).reshape(-1, 2)
    dst = np.asarray(dst, np.float64).reshape(-1, 2)
    sm, dm = src.mean(0), dst.mean(0)
    s, d = src - sm, dst - dm
    norm = (s ** 2).sum()
    a = (s[:, 0] * d[:, 0] + s[:, 1] * d[:, 1]).sum() / norm
    b = (s[:, 0] * d[:, 1] - s[:, 1] * d[:, 0]).sum() / norm
    tx = dm[0] - (a * sm[0] - b * sm[1])
    ty = dm[1] - (b * sm[0] + a * sm[1])
    return np.array([[a, -b, tx], [b, a, ty]], np.float64)


def align(rgb, kps):
    return cv2.warpAffine(rgb, similarity(kps), (112, 112), flags=cv2.INTER_LINEAR, borderValue=0)


def rec_blob(crops):
    return np.ascontiguousarray(((np.stack(crops).astype(np.float32) - 127.5) / 127.5).transpose(0, 3, 1, 2))


def embed(rec, crops):
    e = rec.run(None, {rec.get_inputs()[0].name: rec_blob(crops)})[0]
    return e / (np.linalg.norm(e, axis=1, keepdims=True) + 1e-9)


def analyze(det, rec, rgb):
    fs = detect(det, rgb)
    if fs:
        for f, e in zip(fs, embed(rec, [align(rgb, f.kps) for f in fs])):
            f.emb = e.astype(np.float32)
    return fs


def iou(a, b) -> float:
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def match(ref, got):
    """Pairs (ref face, got face) by IoU >= 0.9; None if `got` misses a face of `ref` or adds a confident one."""
    pairs, used = [], set()
    for f in ref:
        cands = [(iou(f.box, g.box), j) for j, g in enumerate(got) if j not in used]
        best = max(cands, default=(0.0, -1))
        if best[0] < 0.9:
            return None
        used.add(best[1])
        pairs.append((f, got[best[1]]))
    if any(g.score >= 0.6 for j, g in enumerate(got) if j not in used):
        return None
    return pairs


def same_detections(ref, got) -> bool:
    pairs = match(ref, got)
    return pairs is not None and all(
        abs(f.score - g.score) <= 0.05 and np.abs(f.kps - g.kps).max() <= 2.0 for f, g in pairs)


def images():
    return sorted(FACES.glob("*.jpg")) + (sorted(PHOTOS.glob("*.jpg")) if PHOTOS else [])
