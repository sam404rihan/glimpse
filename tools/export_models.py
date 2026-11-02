"""Build the phone's face models from InsightFace buffalo_m (SCRFD-2.5GF detector + ArcFace R50).

buffalo_m's recogniser is byte-identical to buffalo_l's w600k_r50.onnx (checked by sha256), so identity vectors
from either pack can be mixed: switching packs only changes the detector, and nothing needs analysing again.
SCRFD-2.5GF is about a quarter of SCRFD-10GF's compute, and the detector was 87% of the phone's model time.

    pip install -r tools/requirements.txt
    python tools/export_models.py

downloads buffalo_m once (into models/, about 260 MB) and writes app/src/main/assets/models/{det,rec}.ort.
Then check them and refresh the Android test fixtures with:  python -m pytest tools -v -s

The models are InsightFace's and free for non-commercial use only: https://github.com/deepinsight/insightface
"""
from __future__ import annotations

import shutil
import subprocess
import sys

import numpy as np
import onnx
from onnxruntime.quantization import CalibrationDataReader, CalibrationMethod, QuantFormat, QuantType, quantize_static
from onnxruntime.quantization.shape_inference import quant_pre_process
from onnxruntime.tools.onnx_model_utils import make_input_shape_fixed

import pipeline as P

DET_SRC = P.faces.MODEL_DIR / P.faces.DET_FILE
REC_SRC = P.faces.MODEL_DIR / P.faces.REC_FILE


class Reader(CalibrationDataReader):
    def __init__(self, name, blobs):
        self.it = iter([{name: b} for b in blobs])

    def get_next(self):
        return next(self.it, None)


def quantize(src, dst, blobs, method):
    pre = P.WORK / (src.stem + ".pre.onnx")
    if not pre.exists():
        m = onnx.load(str(src))
        if m.opset_import[0].version < 13:  # per-channel DequantizeLinear needs opset 13
            m = onnx.version_converter.convert_version(m, 13)
        up = P.WORK / (src.stem + ".op13.onnx")
        onnx.save(m, str(up))
        quant_pre_process(str(up), str(pre), skip_symbolic_shape=True)
    name = P.session(src).get_inputs()[0].name
    quantize_static(str(pre), str(dst), Reader(name, blobs), quant_format=QuantFormat.QDQ,
                    activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8, per_channel=True,
                    calibrate_method=method, extra_options={"CalibMovingAverage": method == CalibrationMethod.MinMax})


def fp16(src, dst):
    from onnxruntime.transformers.float16 import convert_float_to_float16

    onnx.save(convert_float_to_float16(onnx.load(str(src)), keep_io_types=True), str(dst))


def rec_ok(ref, cand, crops) -> tuple[bool, str]:
    a, b = P.embed(ref, crops), P.embed(cand, crops)
    cos = (a * b).sum(1)
    drift = np.abs(a @ a.T - b @ b.T).max()
    msg = f"cos mean {cos.mean():.4f} min {cos.min():.4f}, pairwise drift {drift:.3f}"
    # Measured: INT8 MinMax gives mean 0.985 / min 0.93 / drift 0.075 and still groups all 80 LFW photos
    # perfectly (android FacesTest.lfwPeopleAreGroupedCorrectlyWithInt8Recogniser), 7x faster than fp32.
    return bool(cos.mean() >= 0.98 and cos.min() >= 0.90 and drift <= 0.08), msg


def main():
    P.faces.ensure_models(lambda done, total: print(f"\rDownloading buffalo_m: {done >> 20} of {total >> 20} MB", end="", flush=True))
    print()
    P.WORK.mkdir(parents=True, exist_ok=True)
    phone = P.WORK / "phone"
    shutil.rmtree(phone, ignore_errors=True)
    phone.mkdir()

    det32 = P.WORK / "det_fp32.onnx"
    m = onnx.load(str(DET_SRC))
    make_input_shape_fixed(m.graph, m.graph.input[0].name, [1, 3, P.DET_SIZE, P.DET_SIZE])
    onnx.save(m, str(det32))
    rec32 = P.WORK / "rec_fp32.onnx"
    shutil.copy(REC_SRC, rec32)

    det_ref, rec_ref = P.session(det32), P.session(rec32)
    rgbs = [P.load(p) for p in P.images()]
    det_blobs = [P.det_blob(P.letterbox(r)[0]) for r in rgbs]
    ref_faces = [P.detect(det_ref, r) for r in rgbs]
    crops = [P.align(r, f.kps) for r, fs in zip(rgbs, ref_faces) for f in fs]
    print(f"{len(rgbs)} images, {len(crops)} faces for calibration")

    # Recogniser: smallest variant that keeps identities.
    chosen = None
    for method in (CalibrationMethod.MinMax, CalibrationMethod.Percentile, CalibrationMethod.Entropy):
        out = P.WORK / f"rec_int8_{method.name}.onnx"
        quantize(rec32, out, [P.rec_blob([c]) for c in crops], method)
        ok, msg = rec_ok(rec_ref, P.session(out), crops)
        print(f"rec INT8 {method.name}: {msg} -> {'OK' if ok else 'rejected'}")
        if ok:
            chosen = out
            break
    if chosen is None:
        chosen = P.WORK / "rec_fp16.onnx"
        fp16(rec32, chosen)
        print("rec: fp16 fallback", rec_ok(rec_ref, P.session(chosen), crops)[1])
    shutil.copy(chosen, phone / "rec.onnx")

    # Detector: INT8 only if it finds exactly the same faces.
    det8 = P.WORK / "det_int8.onnx"
    quantize(det32, det8, det_blobs, CalibrationMethod.MinMax)
    s8 = P.session(det8)
    bad = sum(not P.same_detections(ref, P.detect(s8, r)) for r, ref in zip(rgbs, ref_faces))
    print(f"det INT8: {bad} of {len(rgbs)} images differ -> {'kept fp32' if bad else 'INT8'}")
    shutil.copy(det32 if bad else det8, phone / "det.onnx")

    P.ASSETS.mkdir(parents=True, exist_ok=True)
    subprocess.run([sys.executable, "-m", "onnxruntime.tools.convert_onnx_models_to_ort", str(phone),
                    "--output_dir", str(phone / "ort"), "--optimization_style", "Fixed",
                    "--target_platform", "arm"], check=True)
    for name in ("det", "rec"):
        shutil.copy(phone / "ort" / f"{name}.ort", P.ASSETS / f"{name}.ort")
        print(f"{name}.ort: {(P.ASSETS / f'{name}.ort').stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
