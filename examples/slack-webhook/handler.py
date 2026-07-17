"""
Minimal Slack slash-command webhook for the AI agent.
Receives /agent <prompt>, forwards to the agent API, posts response.
"""

import os
import json
import hmac
import hashlib
import time
import requests
from flask import Flask, request, jsonify
from urllib.parse import urlencode

app = Flask(__name__)

# Load from environment
SLACK_SIGNING_SECRET = os.getenv("SLACK_SIGNING_SECRET", "")
AGENT_URL = os.getenv("AGENT_URL", "http://localhost:8080")
AGENT_AUTH = os.getenv("AGENT_AUTH", "admin:change-me")


def verify_slack_signature(req):
    """Verify the request signature per Slack's signing secret standard."""
    timestamp = req.headers.get("X-Slack-Request-Timestamp", "")
    signature = req.headers.get("X-Slack-Signature", "")

    # Prevent replay attacks: reject if timestamp > 5 minutes old
    if abs(time.time() - int(timestamp)) > 300:
        return False

    basestring = f"v0:{timestamp}:{req.get_data(as_text=True)}"
    computed = f"v0={hmac.new(
        SLACK_SIGNING_SECRET.encode(),
        basestring.encode(),
        hashlib.sha256
    ).hexdigest()}"

    return hmac.compare_digest(computed, signature)


@app.route("/slack/agent", methods=["POST"])
def handle_slash_command():
    """Handle /agent slash command from Slack."""

    # Verify signature
    if not verify_slack_signature(request):
        return jsonify({"error": "Unauthorized"}), 401

    data = request.form
    prompt = data.get("text", "").strip()
    response_url = data.get("response_url", "")
    channel_id = data.get("channel_id", "")
    user_id = data.get("user_id", "")

    if not prompt:
        return jsonify({"text": "Usage: /agent <your prompt>"}), 200

    # Immediate ACK within 3s
    def post_response_async():
        """Post result asynchronously to response_url."""
        try:
            # Call agent API
            payload = {
                "messages": [
                    {"role": "user", "content": prompt}
                ]
            }
            resp = requests.post(
                f"{AGENT_URL}/api/chat",
                json=payload,
                auth=tuple(AGENT_AUTH.split(":")),
                timeout=30
            )
            resp.raise_for_status()

            # Extract response text
            result = resp.json()
            text = result.get("content", [{}])[0].get("text", "No response")

            # Truncate to Slack's limit
            if len(text) > 3000:
                text = text[:2997] + "..."

            # Post to Slack
            slack_msg = {
                "text": text,
                "thread_ts": data.get("thread_ts")
            }
            requests.post(response_url, json=slack_msg, timeout=10)
        except Exception as e:
            requests.post(
                response_url,
                json={"text": f"Error: {str(e)[:100]}"},
                timeout=10
            )

    # Start async task
    from threading import Thread
    Thread(target=post_response_async, daemon=True).start()

    # Return immediate response within 3s
    return jsonify({"text": "Processing..."}), 200


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
