"""Teste de voz local com fala de teste sintetizada, sem gravar o ambiente."""
import base64
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import time

ROOT = Path(os.environ.get("RIANDECK_VOICE_ROOT", str(Path(os.environ["LOCALAPPDATA"]) / "RianDeckVoice")))
PROJECT = Path(__file__).resolve().parent
TOKEN = (PROJECT / "out/agent/pairing.key").read_text().strip()
sys.stdout.reconfigure(encoding="utf-8")


def stimulus(text):
    exe = (ROOT / "python-path.txt").read_text().strip()
    env = {**os.environ, "PYTHONPATH": str(ROOT / "piper-runtime"), "PYTHONUTF8": "1"}
    model = ROOT / "voices/pt_BR-faber-medium.onnx"
    result = subprocess.run(
        [exe, "-s", "-m", "piper", "-m", str(model), "--output-raw"],
        input=(text + "\n").encode(), capture_output=True, env=env, timeout=30, check=True,
    ).stdout
    assert len(result) >= 4410, "Piper não retornou áudio suficiente"
    rate = json.loads(Path(str(model) + ".json").read_text(encoding="utf-8"))["audio"]["sample_rate"]
    samples = memoryview(result).cast("h")
    scaled = [samples[min(i * rate // 24000, len(samples) - 1)] for i in range(len(samples) * 24000 // rate)]
    return struct.pack("<" + "h" * len(scaled), *scaled)


def send(link, code, payload):
    key = os.urandom(4)
    size = len(payload)
    header = bytes((128 | code, 128 | size)) if size < 126 else struct.pack(">BBH", 128 | code, 254, size)
    link.sendall(header + key + bytes(value ^ key[i % 4] for i, value in enumerate(payload)))


def event(link, **settings):
    send(link, 1, json.dumps(settings, ensure_ascii=False).encode())


def frames(link, stream):
    while True:
        first, second = stream.read(2)
        size = second & 127
        if size == 126:
            size = struct.unpack(">H", stream.read(2))[0]
        elif size == 127:
            size = struct.unpack(">Q", stream.read(8))[0]
        payload = stream.read(size)
        code = first & 15
        if code == 8:
            return
        if code == 9:
            send(link, 10, payload)
        elif code == 1:
            yield json.loads(payload), 0
        elif code == 2:
            yield {}, len(payload)


def turn(events):
    seen = {}
    audio_bytes = 0
    for item, size in events:
        audio_bytes += size
        kind = item.get("type")
        if kind == "error":
            raise RuntimeError(item.get("text"))
        if kind:
            seen[kind] = item.get("text", True)
        if kind == "done":
            return seen, audio_bytes
    raise RuntimeError("Sessão encerrada antes da resposta")


def main():
    question = stimulus("Me diga o nome da capital federal do Brasil.")
    second = stimulus("Status do PC.")
    with socket.create_connection(("127.0.0.1", 48627), timeout=55) as link, link.makefile("rb") as stream:
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            "GET /v1/assistant/realtime HTTP/1.1\r\nHost: 127.0.0.1:48627\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
            f"Sec-WebSocket-Key: {key}\r\nAuthorization: Bearer {TOKEN}\r\n\r\n"
        )
        link.sendall(request.encode())
        assert stream.readline().startswith(b"HTTP/1.1 101"), "WebSocket rejeitado"
        while stream.readline() not in (b"\r\n", b""):
            pass
        event(link, type="setup", voice="pt_BR-edresson-low", phoneSpeaker=True, speed=1.18, pause=.10, greeting="Fala! Bora conversar?")
        events = frames(link, stream)
        welcome, pcm = turn(events)
        assert welcome.get("ready") and pcm > 24000, (welcome, pcm)
        print(f"Saudação Piper: {pcm / 48000:.2f} s de áudio", flush=True)

        # A voice-like packet outside the explicit recording window must never leak into the next turn.
        send(link, 2, question[:1920])
        event(link, type="start_capture")
        send(link, 2, bytes(2400))
        event(link, type="submit_capture")
        silence, pcm = turn(events)
        assert not pcm and "Não detectei fala" in silence.get("caption", ""), silence
        print("Silêncio enviado: descartado", flush=True)

        for name, phrase in (("Português + Ollama", question), ("Segundo turno", second)):
            event(link, type="start_capture")
            for offset in range(0, len(phrase), 1920):
                send(link, 2, phrase[offset:offset + 1920])
            event(link, type="submit_capture")
            start = time.monotonic()
            result, pcm = turn(events)
            assert result.get("heard") and result.get("caption") and pcm > 0, (result, pcm)
            if name == "Português + Ollama":
                assert "brasília" in result["caption"].lower(), result
            print(f"{name}: {result['heard']} → {result['caption']} ({time.monotonic() - start:.1f} s, com reprodução)", flush=True)
        send(link, 8, b"")


if __name__ == "__main__":
    main()
