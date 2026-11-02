"""Lets the Glimpse phone app find faces on this PC (GPU if available). Run:  python tools/pc_helper.py

The phone sends one downscaled photo per request. It is decoded in memory, analysed and dropped:
nothing is saved here except the pairing code. The server only answers requests that carry that code.
"""
from __future__ import annotations

import argparse
import base64
import io
import os
import secrets
import socket
import threading
from pathlib import Path

import uvicorn
from fastapi import FastAPI, Header, HTTPException, Request
from PIL import Image
from starlette.concurrency import run_in_threadpool

import faces

DATA_DIR = Path(os.environ.get("GLIMPSE_HELPER_DATA") or Path.home() / ".glimpse-pc-helper")
TOKEN_FILE = DATA_DIR / "phone_pairing.txt"
PORT = 8765
MAX_BYTES = 16 << 20  # the phone sends at most 1600 px JPEGs, a few hundred KB
MAX_BAD_TOKENS = 10  # per address, until restart: the code can't be guessed by trying

app = FastAPI(title="Glimpse PC helper", docs_url=None, redoc_url=None, openapi_url=None)
state: dict = {"model": None, "token": "", "bad": {}}
bad_lock = threading.Lock()


def pairing_token() -> str:
    """Kept across restarts so the phone stays paired. Delete the file to unpair every phone."""
    if TOKEN_FILE.exists():
        return TOKEN_FILE.read_text().strip()
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    alphabet = "abcdefghjkmnpqrstuvwxyz23456789"  # no 0/o, 1/l/i: it is typed on a phone
    token = "".join(secrets.choice(alphabet) for _ in range(10))
    TOKEN_FILE.write_text(token)
    return token


def lan_address() -> str:
    """The address the phone reaches this PC at (no packet is sent: UDP connect only picks a route)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("192.168.0.1", 9))
            return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def check(request: Request, authorization: str | None):
    ip = request.client.host if request.client else "?"
    with bad_lock:
        if state["bad"].get(ip, 0) >= MAX_BAD_TOKENS:
            raise HTTPException(403, "Too many wrong pairing codes; restart pc_helper.py")
        if authorization != f"Bearer {state['token']}":
            state["bad"][ip] = state["bad"].get(ip, 0) + 1
            raise HTTPException(401, "Wrong pairing code")


@app.get("/hello")
def hello(request: Request, authorization: str | None = Header(None)):
    check(request, authorization)
    return {"device": state["model"].device}


@app.post("/faces")
async def find_faces(request: Request, authorization: str | None = Header(None)):
    check(request, authorization)
    body = await request.body()
    if not body or len(body) > MAX_BYTES:
        raise HTTPException(413, "Send one JPEG of at most 16 MB")
    return await run_in_threadpool(analyse, body)  # decoding runs in parallel; FaceModel serialises the GPU


def analyse(jpeg: bytes) -> dict:
    """Faces with boxes in pixels of the received image and unit-length embeddings as little-endian float32."""
    try:
        img = Image.open(io.BytesIO(jpeg))
        img.load()
    except Exception as e:
        raise HTTPException(400, f"Not an image: {type(e).__name__}")
    found = state["model"].analyze(img.convert("RGB"))
    return {
        "w": img.width,
        "h": img.height,
        "faces": [
            {"box": [float(v) for v in f.box], "score": f.score, "emb": base64.b64encode(f.emb.astype("<f4").tobytes()).decode()}
            for f in found
        ],
    }


def main():
    # Plain ASCII on purpose: a piped or redirected Windows console is cp1252 and can't print symbols.
    ap = argparse.ArgumentParser(description="Find faces for the Glimpse phone app on this PC")
    ap.add_argument("--port", type=int, default=PORT)
    ap.add_argument("--cpu", action="store_true", help="don't use the GPU")
    args = ap.parse_args()

    state["token"] = pairing_token()
    print("Loading face models...")
    state["model"] = faces.FaceModel("cpu" if args.cpu else None)
    print(f"Face models on {state['model'].device.upper()}.")
    print()
    print("  In Glimpse: Pictures > menu (3 dots) > Find faces on PC, and enter")
    print(f"      {lan_address()}:{args.port}/{state['token']}")
    print()
    print("  Phone and PC must be on the same Wi-Fi. If Windows asks, allow Python on private networks.")
    print("  Photos are analysed in memory and never saved. Ctrl+C to stop.")
    uvicorn.run(app, host="0.0.0.0", port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
