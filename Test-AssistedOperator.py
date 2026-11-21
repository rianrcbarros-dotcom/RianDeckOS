"""Operador local com áudio de teste, sem gravar o ambiente."""
import base64
import importlib
import json
import os
import socket
import sys
import time
import urllib.request

voice = importlib.import_module("Test-LocalVoice")


def api(route="", method="GET"):
    request = urllib.request.Request(
        "http://127.0.0.1:48627/v1/operator" + route,
        headers={"Authorization": "Bearer " + voice.TOKEN}, method=method,
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.loads(response.read()) if response.status == 200 else response.status


def verify():
    cases = [
        ("Abra o Cursor.", "Concluída"),
        ("Peça ao Codex para analisar meu projeto.", "Aguardando autorização"),
    ]
    if "--codex-only" in sys.argv: cases = cases[1:]
    samples = [voice.stimulus(phrase) for phrase, _ in cases]
    with socket.create_connection(("127.0.0.1", 48627), timeout=50) as link, link.makefile("rb") as stream:
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            "GET /v1/assistant/realtime HTTP/1.1\r\nHost: 127.0.0.1:48627\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
            f"Sec-WebSocket-Key: {key}\r\nAuthorization: Bearer {voice.TOKEN}\r\n\r\n"
        )
        link.sendall(request.encode())
        assert stream.readline().startswith(b"HTTP/1.1 101")
        while stream.readline() not in (b"\r\n", b""):
            pass
        voice.event(link, type="setup", phoneSpeaker=True, voice="pt_BR-edresson-low", speed=1.18)
        frames = voice.frames(link, stream)
        ready, _ = next(frames)
        assert ready.get("type") == "ready", ready
        for sample, (_, expected) in zip(samples, cases):
            previous = (api()["job"] or {}).get("id")
            voice.event(link, type="start_capture")
            for i in range(0, len(sample), 1920):
                voice.send(link, 2, sample[i:i + 1920])
            voice.event(link, type="submit_capture")
            events, pcm = voice.turn(frames)
            for _ in range(38):
                job = api()["job"]
                if job and job["id"] != previous and job["state"] not in ("Executando", "Planejada", "Validando"):
                    break
                time.sleep(1)
            print(events.get("heard"), job.get("state") if job else None, flush=True)
            assert pcm > 0 and job and job["id"] != previous and job["state"] == expected, (events, job)
        assert api("/" + job["id"] + "/cancel", "POST") == 204
        assert api()["job"]["state"] == "Cancelada"
        print("Plano do Codex bloqueado até autorização. Cancelamento confirmado.", flush=True)
        voice.send(link, 8, b"")


if __name__ == "__main__":
    verify()
