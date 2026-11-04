# NBA Stats AI Agent

A web app where you ask basketball questions in plain English and an AI agent
looks up real stats to answer them.

**Status:** in progress.

## Project layout

- `stats-service/`: Python (FastAPI) service that provides NBA stats as JSON
- `agent-app/`: Java (Spring Boot) app that runs the agent loop with the Claude API and serves the web page
