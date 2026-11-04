# Learning Notes

What this project has taught me so far, written so I can come back and understand it later.

## The big picture

The goal is to ask a question in plain English, like "who leads the league in assists?", and get an answer based on real NBA stats.

The project has two parts, and they work like a restaurant:

| Part | Restaurant role | Language | Status |
|---|---|---|---|
| `stats-service/` | The kitchen: gets the data ready | Python | Working |
| `agent-app/` | The waiter: understands the question and asks the kitchen | Java | Not started |

A question will travel like this once both parts exist:

```
Me -> Java app -> Claude decides which stats are needed
                       |
                       v
      Python stats service -> nba_api -> NBA's website
```

## Key terms

| Term | What it means | Analogy |
|---|---|---|
| **API** | A set of rules that lets one program ask another for something | A restaurant menu: you can only order what's listed |
| **Server** | A program that waits for requests and answers them | A kitchen that's open for orders |
| **Endpoint** | One specific URL the server responds to, like `/leaders` | One item on the menu |
| **Query parameter** | Extra details added to a URL, like `?stat=AST&top_n=5` | "No onions, extra cheese" |
| **JSON** | A plain-text format for structured data that most languages can read | A form everyone knows how to fill in |
| **FastAPI** | The Python package that connects URLs to my functions | The front counter system |
| **uvicorn** | The program that runs a FastAPI app as a server | The building the restaurant runs in |
| **nba_api** | A package that fetches data from the NBA's site | The kitchen's food supplier |
| **Virtual environment (`venv`)** | A private set of Python packages for one project | A toolbox for one job, so tools don't get mixed up |
| **Pinned version** | An exact package version, like `fastapi==0.162.0` | A recipe naming an exact brand, so it turns out the same |
| **Environment variable** | A setting kept outside the code, like the ones in `.env` | A note on the fridge instead of writing on the recipe |

## How the stats service is organized

There are two files, and each has one job:

- **`main.py`** handles the web side: URLs, checking input, and sending responses.
- **`nba_client.py`** handles the basketball side: fetching data and cleaning it up. It knows nothing about the web.

This idea is called **separation of concerns**. Because the two are separate, I can change how the data is fetched without touching the URLs, and the reverse is also true.

### The endpoints

| Endpoint | What it does | Example |
|---|---|---|
| `/players/search` | Finds players by name | `/players/search?name=LeBron` |
| `/players/stats` | One player's per-game stats | `/players/stats?name=LeBron James&season=2025-26` |
| `/leaders` | The league's top players in a stat | `/leaders?stat=AST&top_n=5` |
| `/compare` | Two players side by side | `/compare?name_a=Stephen Curry&name_b=Kevin Durant` |

### Ideas worth remembering from the code

- **Decorators:** the `@app.get("/leaders")` line connects a URL to the function under it.
- **Custom errors:** `nba_client.py` raises named errors like `PlayerNotFoundError`, and `main.py` turns each one into the right status code.
- **Rate limiting:** the code waits at least 0.6 seconds between calls to the NBA's site, so the site is less likely to block us.
- **One error format:** every error looks like `{"error": "message"}`, so the Java app only has to understand one shape.
- **Handling messy real-world data:** a player traded mid-season has one row per team plus a combined `TOT` row, and the code uses the combined one.

## HTTP status codes

A status code is a short, standard signal of how a request went. This service uses:

| Code | Meaning | Example that triggers it |
|---|---|---|
| **200** | OK | Any successful request |
| **400** | Bad request: the input was wrong | `season=banana` |
| **404** | Not found | A player who doesn't exist, or a season they didn't play |
| **409** | Conflict: the request could mean several things | `name=James`, which matches 47 players |
| **503** | Temporarily unavailable | The NBA's site failed to respond |

## Git basics

| Term | What it means |
|---|---|
| **Commit** | A saved snapshot of the project, like a save point in a game |
| **Branch** | A separate line of work, so `main` stays safe until I'm ready |
| **Staging (`git add`)** | Choosing which changes go in the next commit |
| **Push** | Copying commits from my computer to GitHub |
| **`.gitignore`** | A list of files Git should never save |

**Never commit `.env`.** It holds my secret API key, and `.gitignore` is what keeps it out of Git. `.env.example` is the safe template with placeholders, which is why it can be saved in Git.

## Lessons from building this

### Test before you build on top of something
We tested the stats service before starting the Java app. If something fails later, I'll have a better idea of where to look.

### Test the failures too
Half the job is checking what happens when things go wrong. Good tests include bad input (`season=banana`), a name that's too vague, and tricky cases like a traded player.

### A bug can be in the message, not the logic
The `/leaders` endpoint requires players to have played 70% of the games so far. In an 82-game season, that's 57.4 games.

- **The bug:** the filter correctly required 57.4 games, but the message rounded that down and said "at least 57". A player with exactly 57 was left out even though the message said they'd qualify.
- **The fix:** `math.ceil` rounds up to 58, and that one number is now used for both the filter and the message.
- **The lesson:** if two places need the same value, calculate it once and reuse it, so they can't disagree.

### Computers store decimals imprecisely
`0.7` isn't stored as exactly 0.7, so rounding up could accidentally add a game. Before relying on `math.ceil`, we checked every game count from 1 to 82 and found no mismatches. Checking is better than assuming.

### Editor warnings can be out of date
VS Code once said `"math" is not defined` because it checked the file just before the `import math` line was added. Running the code confirmed it was fine. Warnings are worth taking seriously, and running the code is how you find out for sure.

### Restart the server after changing code
The server only loads the code when it starts, so it keeps using the old version until it's restarted. (Adding `--reload` to the uvicorn command makes it restart automatically while developing.)

## Commands I'll use a lot

Run these in PowerShell, starting from the project folder.

**Start the stats service:**
```powershell
cd stats-service
.\venv\Scripts\python.exe -m uvicorn main:app --port 8000
```

Then open <http://localhost:8000/docs> in a browser to try the endpoints. Press `Ctrl+C` in the terminal to stop the server.

**Install the packages (only needed after setting up a new `venv`):**
```powershell
.\venv\Scripts\python.exe -m pip install -r requirements.txt
```

**Save my work with Git:**
```powershell
git status                  # What has changed?
git diff                    # What exactly changed?
git add <file>              # Choose a file for the next commit
git commit -m "Message"     # Save the snapshot
git log --oneline           # Show past commits
```

## Practice ideas

1. Open the `/docs` page and trigger each status code in the table above.
2. Add `FG3A` (three-pointers attempted per game) to `LEADER_STATS` in `nba_client.py`. It's one line. Restart the server and try `/leaders?stat=FG3A`.
3. Read `current_season()` in `nba_client.py` and work out what it returns for January 2027 and for September 2027.
4. Change the 70% games minimum and predict what happens to the `/leaders` results before you run it.

## Still to learn

- [ ] Automated tests: programs that re-check my code with one command
- [ ] Building the Java app
- [ ] How an AI agent uses tools: how Claude decides which endpoint to call
- [ ] Merging a branch into `main` and pushing to GitHub
