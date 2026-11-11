import pytest

pytest.importorskip("fastapi")
pytest.importorskip("httpx")
from fastapi.testclient import TestClient  # noqa: E402


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("DATA_DIR", str(tmp_path))
    monkeypatch.setenv("API_KEY", "k")
    monkeypatch.setenv("VOICE_PROVIDER", "test")
    monkeypatch.setenv("ALLOW_TEST_PROVIDER", "1")
    import app.main as m
    m._store = None
    return TestClient(m.app)


H = {"X-API-Key": "k"}


def test_health_and_auth(client):
    assert client.get("/health").json()["status"] == "ok"
    assert client.get("/v1/voice-profiles").status_code == 401
    assert client.get("/v1/voice-profiles", headers=H).status_code == 200


def test_consent_flow(client):
    r = client.post("/v1/voice-profiles", headers=H, files={"file": ("a.mp3", b"ID3xx")}, data={"name": "n", "owner_name": "Sonya", "owner_relation": "self"})
    pid = r.json()["id"]
    assert client.post(f"/v1/voice-profiles/{pid}/clone", headers=H).status_code == 403
    assert client.post(f"/v1/voice-profiles/{pid}/consent", headers=H, json={"accept": True, "disclosure_ack": True, "typed_name": "Sonya"}).json()["status"] == "consented"
    assert client.post(f"/v1/voice-profiles/{pid}/clone", headers=H).json()["status"] == "ready"
    assert client.delete(f"/v1/voice-profiles/{pid}", headers=H).json()["deleted"]


def _ready_profile(client):
    r = client.post("/v1/voice-profiles", headers=H, files={"file": ("a.mp3", b"ID3xx")}, data={"name": "n", "owner_name": "Sonya", "owner_relation": "self"})
    pid = r.json()["id"]
    client.post(f"/v1/voice-profiles/{pid}/consent", headers=H, json={"accept": True, "disclosure_ack": True, "typed_name": "Sonya"})
    assert client.post(f"/v1/voice-profiles/{pid}/clone", headers=H).json()["status"] == "ready"
    return pid


def test_preview_roundtrip_and_size_cap(client):
    pid = _ready_profile(client)
    ok = client.post(f"/v1/voice-profiles/{pid}/preview", headers=H, content=b"\x01\x02" * 16000)   # 1 s
    assert ok.status_code == 200 and len(ok.content) == 32000
    too_big = client.post(f"/v1/voice-profiles/{pid}/preview", headers=H, content=b"\x00" * (16000 * 2 * 21))
    assert too_big.status_code == 413


def test_websocket_auth_header_and_query(client):
    from starlette.websockets import WebSocketDisconnect
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect("/v1/calls/ws"):
            pass
    with client.websocket_connect("/v1/calls/ws", headers={"X-API-Key": "k"}) as ws:   # header auth works
        ws.send_json({"type": "start", "destination": "bad dest!", "account": {"username": "u", "server": "127.0.0.1"}})
        assert ws.receive_json()["type"] == "error"
    with client.websocket_connect("/v1/calls/ws?token=k") as ws:                       # legacy query token still works
        ws.send_json({"type": "start", "destination": "bad dest!", "account": {"username": "u", "server": "127.0.0.1"}})
        assert ws.receive_json()["type"] == "error"
