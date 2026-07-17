# Slack Slash-Command Webhook

A minimal Flask handler that integrates the AI agent with Slack via `/agent` slash command.

## What it does

Users type `/agent <question>` in any Slack channel. The handler:
1. Verifies the Slack signing secret
2. Forwards the prompt to the agent's `/api/chat` endpoint
3. Posts the response back to Slack (truncated to 3000 chars)
4. Responds to Slack within the 3-second timeout via `response_url` (async pattern)

## Setup

### 1. Create a Slack App

Go to [api.slack.com/apps](https://api.slack.com/apps) and create a new app.

### 2. Add Slash Command

Under **Slash Commands**, create a new command:
- **Command**: `/agent`
- **Request URL**: `https://your-webhook-url.com/slack/agent` (where you'll deploy this handler)
- **Short Description**: Query the AI agent
- **Usage Hint**: `<your question>`

Copy the **Signing Secret** from **App Credentials**.

### 3. Install App to Workspace

Under **Install App**, click "Install to Workspace" and grant permissions.

### 4. Deploy Handler

Install dependencies:
```bash
pip install -r requirements.txt
```

Set environment variables:
```bash
export SLACK_SIGNING_SECRET="xoxb-1234567890-..."
export AGENT_URL="https://agent.example.com"
export AGENT_AUTH="username:password"
```

Run the Flask app:
```bash
python handler.py
```

Or deploy to AWS Lambda / Google Cloud Run (add a WSGI entrypoint for serverless).

### 5. Request URL

Update the slash command's Request URL to point to your deployed handler at `/slack/agent`.

## Deployment

### Lambda / Cloud Run Example

```dockerfile
FROM python:3.11
WORKDIR /app
COPY handler.py requirements.txt .
RUN pip install -r requirements.txt gunicorn
EXPOSE 5000
CMD ["gunicorn", "-b", "0.0.0.0:5000", "handler:app"]
```

## Notes

- Responses are truncated to 3000 characters (Slack block limit).
- The handler uses async `response_url` pattern to respond within Slack's 3-second timeout.
- Signing secret verification prevents unauthorized requests.
- No test file included — copy-paste and deploy as-is.
