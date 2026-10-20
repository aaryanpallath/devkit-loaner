"""Daily Lambda (EventBridge schedule): fetch overdue loans from the API and build one reminder per borrower.

Sending is behind `send`, which defaults to logging. In AWS it would call SES (`ses.send_email`), which needs a
verified sender; that wiring is left out so the function never emails anyone by accident.

The overdue report requires a token with the REMINDER_SERVICE role. On AWS the token is read at run time from the
Secrets Manager secret named by TOKEN_SECRET_ARN (kept fresh by the identity provider's client-credentials flow), so
it never appears in the function's configuration; locally it can come from API_TOKEN.
"""

from __future__ import annotations

import json
import logging
import os
import urllib.request
from collections import defaultdict
from collections.abc import Callable

log = logging.getLogger()
log.setLevel(logging.INFO)


def service_token() -> str:
    if os.environ.get("API_TOKEN"):
        return os.environ["API_TOKEN"]
    import boto3   # available in the Lambda runtime

    return boto3.client("secretsmanager").get_secret_value(SecretId=os.environ["TOKEN_SECRET_ARN"])["SecretString"]


def fetch_overdue(api_url: str, token: str, opener: Callable = urllib.request.urlopen) -> list[dict]:
    req = urllib.request.Request(f"{api_url.rstrip('/')}/api/reservations/overdue",
                                 headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
    with opener(req, timeout=10) as resp:
        return json.loads(resp.read())


def build_reminders(overdue: list[dict]) -> list[dict]:
    """One message per borrower, listing every device they still have, most overdue first."""
    by_borrower: dict[str, list[dict]] = defaultdict(list)
    for o in overdue:
        by_borrower[o["borrower"]].append(o)
    reminders = []
    for borrower, items in sorted(by_borrower.items()):
        items.sort(key=lambda o: -o["hoursOverdue"])
        lines = [f"- {o['assetTag']} {o['deviceName']}: due {o['due']}, {o['hoursOverdue']} h overdue" for o in items]
        reminders.append({
            "to": borrower,
            "subject": f"Please return {len(items)} loaned device{'s' if len(items) > 1 else ''}",
            "body": "These devices are past their return time:\n" + "\n".join(lines),
            "escalate": any(o["hoursOverdue"] >= 72 for o in items),
        })
    return reminders


def handler(event, context, opener: Callable = urllib.request.urlopen, send: Callable | None = None,
            token: Callable[[], str] = service_token) -> dict:
    api_url = os.environ["API_URL"]
    reminders = build_reminders(fetch_overdue(api_url, token(), opener))
    for r in reminders:
        (send or (lambda m: log.info("reminder %s", json.dumps(m))))(r)
    return {"reminders": len(reminders), "escalations": sum(r["escalate"] for r in reminders)}
