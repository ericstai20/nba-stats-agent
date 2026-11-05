# NBA Stats AI Agent

A web app where you ask basketball questions in plain English and an AI agent looks up real stats to answer them.

For example, if you ask "Who led the league in scoring in 2025-26, and how many assists per game did that player average?", the agent finds the scoring leader, looks up that player's assists, and then writes the answer.

You can also ask follow-up questions. After asking who led the league in scoring, you can ask "How about his assists?"

## How it works

```
Web page -> Java app <-> Claude API
               |
               v
         Python service -> nba_api -> NBA stats site
```

The Java app sends your question to Claude along with a list of tools. Claude decides which tools it needs, the Java app runs them, and the results go back to Claude. This repeats until Claude can answer.

| Tool | What it does |
|---|---|
| `get_player_stats` | Gets one player's per-game stats for a season |
| `search_players` | Finds players by name |
| `get_league_leaders` | Gets the top players in a stat |

## Project layout

- `stats-service/`: Python (FastAPI) service that provides NBA stats as JSON
- `agent-app/`: Java (Spring Boot) app that runs the agent loop with the Claude API and serves the web page
- `LEARNING.md`: my notes on the concepts behind this project

## Running it

The commands below are for PowerShell on Windows.

### What you need

- Java 25
- Python (built and tested with 3.13)
- An [Anthropic API key](https://console.anthropic.com) with credits. Each question costs a small amount.

### 1. Add your settings

From the project folder, copy the settings template:

```powershell
Copy-Item .env.example .env
```

Then open `.env` and replace the placeholder with your API key. Git ignores this file, so your key stays on your computer.

### 2. Install the Python packages

You only need to do this once:

```powershell
cd stats-service
python -m venv venv
.\venv\Scripts\python.exe -m pip install -r requirements.txt
```

### 3. Start both apps

Use a separate terminal for each one, starting from the project folder:

```powershell
# Terminal 1
cd stats-service
.\venv\Scripts\python.exe -m uvicorn main:app --port 8000
```

```powershell
# Terminal 2
cd agent-app
.\mvnw.cmd spring-boot:run
```

### 4. Ask a question

Open <http://localhost:8080> in your browser.

To stop the apps, press `Ctrl+C` in each terminal.

## Running the tests

The tests use stand-ins for Claude and the NBA's site, so they cost nothing and neither app needs to be running.

```powershell
# Python tests, from the stats-service folder
.\venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\venv\Scripts\python.exe -m pytest
```

You only need the install line once.

```powershell
# Java tests, from the agent-app folder
.\mvnw.cmd test
```

## Limitations

- The agent only knows regular-season stats for players and league leaders. It can't answer questions about things like playoffs, standings or game results.
- Follow-up questions work, but conversations are only kept in memory. Restarting the app or refreshing the page starts over.
- Each conversation remembers its last 10 questions and answers, and the app keeps the 100 most recently used conversations.
- The tests use stand-ins, so they can't tell you if Claude's real answers get worse or if the NBA's site changes.
- The stats come through [nba_api](https://github.com/swar/nba_api), an unofficial package, so they can stop working if the NBA's site changes or blocks requests.
