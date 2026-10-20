import io
import json

from overdue_reminder.handler import build_reminders, handler

OVERDUE = [
    {"assetTag": "DK-1", "deviceName": "Dev kit 1", "borrower": "a@example.com", "due": "2027-06-14T12:00:00Z",
     "hoursOverdue": 24, "deviceId": "d1", "reservationId": "r1"},
    {"assetTag": "DK-2", "deviceName": "Dev kit 2", "borrower": "a@example.com", "due": "2027-06-11T12:00:00Z",
     "hoursOverdue": 96, "deviceId": "d2", "reservationId": "r2"},
    {"assetTag": "HH-9", "deviceName": "Handheld 9", "borrower": "b@example.com", "due": "2027-06-15T09:00:00Z",
     "hoursOverdue": 3, "deviceId": "d3", "reservationId": "r3"},
]


def test_one_reminder_per_borrower_most_overdue_first():
    r = build_reminders(OVERDUE)
    assert [x["to"] for x in r] == ["a@example.com", "b@example.com"]
    assert r[0]["subject"] == "Please return 2 loaned devices"
    assert r[0]["body"].index("DK-2") < r[0]["body"].index("DK-1")
    assert r[0]["escalate"] and not r[1]["escalate"]


def test_handler_calls_the_api_and_sends(monkeypatch):
    monkeypatch.setenv("API_URL", "http://api.internal/")
    seen, sent = [], []

    def opener(req, timeout):
        seen.append((req.full_url, req.get_header("Authorization")))
        return io.BytesIO(json.dumps(OVERDUE).encode())

    result = handler({}, None, opener=opener, send=sent.append, token=lambda: "svc-token")
    assert result == {"reminders": 2, "escalations": 1}
    assert seen == [("http://api.internal/api/reservations/overdue", "Bearer svc-token")]
    assert len(sent) == 2


def test_nothing_overdue_sends_nothing(monkeypatch):
    monkeypatch.setenv("API_URL", "http://api.internal")
    sent = []
    assert handler({}, None, opener=lambda r, timeout: io.BytesIO(b"[]"), send=sent.append,
                   token=lambda: "t") == {"reminders": 0, "escalations": 0}
    assert sent == []


def test_local_token_comes_from_the_environment(monkeypatch):
    from overdue_reminder.handler import service_token

    monkeypatch.setenv("API_TOKEN", "local-token")
    assert service_token() == "local-token"
