"""Checks the phone's face models against the full-precision originals and writes the Android test
fixtures. Run after export_models.py:  python -m pytest tools -v -s"""

import numpy as np
import pytest

import pipeline as P

FIX = P.ROOT / "app" / "src" / "test" / "resources" / "fixtures"
DEVICE = P.ROOT / "app" / "src" / "androidTest" / "assets"
LFW = sorted(P.FACES.glob("*.jpg"))


@pytest.fixture(scope="module")
def ref():
    return P.session(P.WORK / "det_fp32.onnx"), P.session(P.WORK / "rec_fp32.onnx")


@pytest.fixture(scope="module")
def phone():
    return P.session(P.ASSETS / "det.ort"), P.session(P.ASSETS / "rec.ort")


@pytest.fixture(scope="module")
def rgbs():
    return {p: P.load(p) for p in P.images()}


def test_models_fit_in_apk_budget():
    total = sum((P.ASSETS / f).stat().st_size for f in ("det.ort", "rec.ort"))
    print(f"\n  models: {total / 1e6:.1f} MB")
    assert total < 70e6


def test_phone_models_match_full_precision(ref, phone, rgbs):
    coss = []
    for rgb in rgbs.values():
        a, b = P.analyze(*ref, rgb), P.analyze(*phone, rgb)
        pairs = P.match(a, b)
        assert pairs is not None and P.same_detections(a, b)
        coss += [float(f.emb @ g.emb) for f, g in pairs]
    coss = np.array(coss)
    print(f"\n  {len(coss)} faces: cos mean {coss.mean():.4f} min {coss.min():.4f}")
    assert coss.mean() >= 0.98 and coss.min() >= 0.90  # grouping is checked on Android with these vectors


def test_write_fixtures(phone, rgbs):
    det, rec = phone
    FIX.mkdir(parents=True, exist_ok=True)
    # SCRFD raw head outputs + expected detections (letterbox space, after NMS) for the decode test.
    stems = ["Colin_Powell_0011"]
    if P.PHOTOS:  # plus the extra photo with the most faces, if extra photos were given
        stems.append(max(P.PHOTOS.glob("*.jpg"), key=lambda p: len(P.detect(det, rgbs[p]))).stem)
    for stem in stems:
        path = next(p for p in rgbs if p.stem == stem)
        outs, _ = P.det_outputs(det, rgbs[path])
        np.concatenate([o.ravel() for o in outs]).astype("<f4").tofile(FIX / f"det_{stem}.bin")
        boxes, scores, kpss = P.faces.FaceModel._decode(outs)
        keep = P.faces._nms(boxes, scores, P.faces.NMS_THRESH) if len(scores) else []
        with open(FIX / f"det_{stem}.txt", "w") as f:
            for i in keep:
                f.write(" ".join(f"{v:.4f}" for v in [*boxes[i], scores[i], *kpss[i].ravel()]) + "\n")
    # Alignment matrices for every LFW face.
    with open(FIX / "align.txt", "w") as f:
        for p in LFW:
            for face in P.detect(det, rgbs[p]):
                f.write(" ".join(f"{v:.5f}" for v in [*face.kps.ravel(), *P.similarity(face.kps).ravel()]) + "\n")
    # Every LFW face with its identity vector, for the clustering acceptance test.
    with open(FIX / "faces.txt", "w") as f:
        for p in LFW:
            for face in P.analyze(det, rec, rgbs[p]):
                f.write(f"{p.name} {face.score:.4f} {face.size:.2f} " + " ".join(f"{v:.5f}" for v in face.emb) + "\n")
    # On-device goldens: the expected faces of the LFW photos (which already live in DEVICE / "lfw").
    with open(DEVICE / "golden.txt", "w") as f:
        for p in LFW:
            for face in P.analyze(det, rec, rgbs[p]):
                f.write(f"{p.name} " + " ".join(f"{v:.3f}" for v in face.box) + f" {face.score:.4f} "
                        + " ".join(f"{v:.5f}" for v in face.emb) + "\n")
