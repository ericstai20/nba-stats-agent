# Learning Notes

What this project has taught me so far, written so I can come back and understand it later.

## The big picture

The goal is to ask a question in plain English, like "who leads the league in assists?", and get an answer based on real NBA stats.

The project has two parts, and they work like a restaurant:

| Part | Restaurant role | Language | Status |
|---|---|---|---|
| `stats-service/` | The kitchen: gets the data ready | Python | Working |
| `agent-app/` | The waiter: understands the question and asks the kitchen | Java | Working, with three tools |

A question travels like this:

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

## How the agent app is organized

It uses the same split as the Python service, with the web in one place and each outside service in another:

| File | Job | Python equivalent |
|---|---|---|
| `AskController.java` | The front counter: takes a question and sends back the answer | `main.py` |
| `ClaudeService.java` | Runs the agent loop and defines the tools | No equivalent |
| `StatsClient.java` | Calls the Python service for stats | No equivalent |
| `ConversationStore.java` | Remembers recent conversations for follow-up questions | No equivalent |

## How the agent loop works

An **agent** is a program where the AI decides which steps to take, and the code carries them out. Claude never touches the stats service. It asks, and my Java code does the work, like a manager asking an assistant to look something up.

1. Send the whole conversation to Claude, along with the list of tools.
2. Check why Claude stopped:
   - `end_turn` means it's finished, so return its answer.
   - `tool_use` means it needs something run, so carry on.
3. Run each tool it asked for and add the results to the conversation.
4. Go back to step 1, up to 10 times.

Here's a real log from asking "Who scored more per game in 2025-26, Stephen Curry or Kevin Durant?":

```
Turn 1: Claude stopped because of 'tool_use'
Claude asked for get_player_stats with {name=Stephen Curry, season=2025-26}
Claude asked for get_player_stats with {name=Kevin Durant, season=2025-26}
Turn 2: Claude stopped because of 'end_turn'
```

### The agent's tools

| Tool | What it's for | Calls |
|---|---|---|
| `get_player_stats` | One player's stats for a season | `/players/stats` |
| `search_players` | Finding a player when a name is unclear | `/players/search` |
| `get_league_leaders` | The top players in a stat | `/leaders` |

There's no tool for `/compare`, because Claude can compare two players by looking up each one. Fewer tools means fewer choices to get wrong.

Claude can also chain tools, using one result to decide the next step. Nobody told it these steps for "Who led the league in scoring in 2025-26, and how many assists did that player average?". It worked them out itself:

```
Claude asked for get_league_leaders with {stat=PTS, season=2025-26, top_n=1}
Claude asked for get_player_stats with {name=Luka Dončić, season=2025-26}
```

That's the difference between an agent and a fixed script: the question decides the path.

### Rules worth remembering

- **Claude has no memory between requests.** Every request includes the whole conversation so far, which is also why longer loops cost more.
- **A tool definition is a description, not code.** Claude reads the name, description and inputs to decide when to use a tool, so clear wording matters.
- **Results go back together.** If Claude asks for two tools in one turn, both results go back in one message, each with the ID of the request it answers.
- **Errors go back to Claude too.** A result marked as an error lets Claude explain the problem. When I shut down the Python service, it said it couldn't reach the stats and wouldn't guess.
- **Loops need a limit.** Every trip is a paid request, so the limit stops a confused agent from spending forever.
- **Claude has no clock.** The instructions tell it today's date, or it wouldn't know which season "this season" means.

## How follow-up questions work

Claude has no memory between requests, so "How about his assists?" means nothing on its own. My app has to remember the conversation and send it again with each new question.

It works like a coat check:

1. My first question arrives without a ticket, so the app answers it and saves the question and answer under a new random **conversation ID**.
2. The reply includes that ID, and the page holds on to it.
3. My follow-up includes the ID, so the app finds the history and sends it to Claude ahead of the new question.

Here's a real log. Nobody told Claude who "his" meant in the second question. It worked that out from the earlier answer:

```
Who led the league in scoring in 2025-26?
Claude asked for get_league_leaders with {stat=PTS, season=2025-26, top_n=3}

How about his assists?
Claude asked for get_player_stats with {name=Luka Dončić, season=2025-26}
```

### Choices we made

**Where to keep the conversation:**

| Approach | Good | Bad |
|---|---|---|
| The browser sends the whole history each time | The server stays simple | The browser could change what Claude "said", and requests keep growing |
| **The server keeps it in memory (what I use)** | Simple, and teaches how sessions work | A restart forgets everything |
| A database | Survives restarts | A lot of extra machinery for an app only I use |

**What to remember:** I only keep questions and final answers, not the raw stats from each lookup. That keeps follow-ups cheap, but Claude has to look a number up again if an earlier answer didn't state it.

### Limits keep costs and memory under control

- **10 exchanges per conversation:** everything kept gets sent to Claude with each question, so a longer history costs more every time.
- **100 conversations in total:** without a limit, the app's memory use would keep growing for as long as it runs. When the store is full, it forgets the conversation that has gone unused the longest.

### Terms

| Term | What it means | Analogy |
|---|---|---|
| **State** | Information a program remembers between requests | A coat check's rack of coats |
| **Stateless** | Remembers nothing between requests, like Claude's API | A shop that doesn't recognize returning customers |
| **Session** | One user's series of related requests, tied together by an ID | One visit to the coat check |
| **UUID** | A long random ID that's practically impossible to guess or repeat | A ticket number nobody could guess |
| **`synchronized`** | Lets only one request at a time into a piece of code | A single-file line, like the lock in `nba_client.py` |
| **Memory leak** | Memory use that keeps growing because old data is never removed | A coat check that never gives away unclaimed coats |
| **`Optional`** | A Java box that either holds a value or is empty, which forces me to handle both | A locker that might be empty |

## How the web page works

`index.html` lives in `agent-app/src/main/resources/static/`, and Spring Boot automatically serves files from that folder. It has three layers:

| Layer | Job | Analogy |
|---|---|---|
| **HTML** | What's on the page | The walls and furniture of a house |
| **CSS** | How it looks | The paint and decoration |
| **JavaScript** | What it does when you interact with it | The light switches and plumbing |

When I click **Ask**:

1. JavaScript stops the form's default behavior, which would reload the page.
2. It disables the buttons and shows "Looking up the stats...".
3. `fetch` sends my question to `/ask`, the same endpoint my terminal command uses.
4. `await` waits for the reply without freezing the page.
5. It adds the answer, or the error, to the conversation and turns the buttons back on. After an error, it also puts my question back in the box.

The browser runs this code on my computer, while the Java and Python code runs as servers. That's the difference between the **frontend** (what runs in the browser) and the **backend** (what runs on the server).

## How the automated tests work

An **automated test** is a small program that runs my code and checks the result. Instead of clicking around after every change, I run one command and find out in seconds whether I broke something.

The hard part is that my code depends on things that are slow, cost money or keep changing: Claude and the NBA's site. So the tests swap those out for stand-ins, like a pilot training in a flight simulator instead of a real plane.

```
Real app:  Java app -> Claude API (paid, answers vary)
                    -> Python service -> nba_api -> NBA's site (live data)

Java tests:    Java app -> FakeServer pretending to be Claude
                        -> FakeServer pretending to be the Python service

Python tests:  Python service -> FakeNbaSite pretending to be nba_api's requests
```

| File | What it tests |
|---|---|
| `stats-service/test_nba_client.py` | Basketball logic: traded players, minimums, ties, finding names |
| `stats-service/test_main.py` | URLs, status codes and the error format |
| `agent-app/.../ClaudeServiceTest.java` | The agent loop, using a scripted fake Claude |
| `agent-app/.../StatsClientTest.java` | How Java calls the Python service |
| `agent-app/.../AskControllerTest.java` | The `/ask` endpoint |

### Testing terms

| Term | What it means | Analogy |
|---|---|---|
| **Assertion** | A line that checks something is true, and fails the test if it isn't | A teacher marking one answer right or wrong |
| **Arrange, act, assert** | The usual three steps of a test: set up, do the thing, check the result | Set up the experiment, run it, read the result |
| **Fake** | A simple stand-in for something real | A flight simulator |
| **Fixture** (pytest) | Setup code a test asks for by naming it as a parameter | Equipment laid out before a lab class |
| **`monkeypatch`** (pytest) | Swaps something out for one test, then restores it | A substitute teacher for one day |
| **`@Test`, `@BeforeEach`** (JUnit) | Labels that mark a test, and setup to run before each test | A checklist item, and "wash hands first" |
| **pytest / JUnit** | The programs that find and run tests in Python and Java | The person running the exam |
| **Dev dependency** | A package only needed while building, like pytest in `requirements-dev.txt` | Scaffolding that isn't part of the finished house |
| **Mutation testing** | Breaking the real code on purpose to check that a test notices | A fire drill for the tests |

### What a fake can't tell me

The fake Claude follows my script, so it proves that my code handles a reply correctly. It can't prove that real Claude will choose the right tool or write a good answer. For that, I still need to ask the real app a question now and then.

## How continuous integration works

**Continuous integration (CI)** means a robot runs my tests every time I push code. It's like a teammate who checks every change on a clean computer, so "it works on my machine" problems show up right away.

GitHub's version is called **GitHub Actions**. It runs whatever is described in the files in `.github/workflows/`. Ours is `tests.yml`:

1. **When it runs:** on every push and every pull request (`on: push, pull_request`).
2. **Jobs:** two jobs, `python` and `java`, run at the same time, each on its own fresh Ubuntu Linux computer. That computer starts with nothing of mine on it.
3. **Steps in each job:** download the code (`actions/checkout`), install the language (`actions/setup-python` / `actions/setup-java`), then run the tests.
4. **The result:** a ✅ or ❌ next to the commit on GitHub, and the badge at the top of the README shows the latest result on `main`.

### CI terms

| Term | Meaning |
|---|---|
| **Workflow** | One automated process, described in a YAML file in `.github/workflows/` |
| **YAML** | A settings file format where indentation shows what belongs to what (like Python) |
| **Job** | A group of steps that runs on one computer. Separate jobs run in parallel |
| **Step** | One command (`run:`) or one ready-made action (`uses:`) |
| **Action** | A reusable step someone else wrote, like `actions/setup-java@v5`. The `@v5` picks the version |
| **Runner** | The computer GitHub lends us to run a job (`runs-on: ubuntu-latest`) |
| **Cache** | Saved downloads (pip packages, Maven libraries) reused between runs, so later runs are faster |
| **Badge** | A small image that GitHub keeps updated with the latest result |

### Why no API key is needed in CI
The tests use a fake Claude and a fake key (`ANTHROPIC_API_KEY=test-key` in `AgentAppApplicationTests`), so the robot never needs my real key. If a workflow ever did need a secret, it would go in the repo's **Settings → Secrets**, never in the YAML file, because the YAML file is public.

## Java and Spring Boot terms

| Term | What it means | Analogy or Python equivalent |
|---|---|---|
| **JDK** | The tools for building and running Java programs | Installing Python itself |
| **LTS (long-term support)** | A version that gets updates for years. I chose Java 25 for this reason | Buying a car with a long warranty |
| **Maven** | Downloads libraries and builds the project | `pip`, plus a build step |
| **`pom.xml`** | Lists the project's libraries and settings | `requirements.txt` |
| **Maven wrapper (`mvnw`)** | A script that fetches the right Maven version, so there's nothing extra to install | - |
| **Spring Boot** | The framework that handles the web server side | FastAPI |
| **Package** | The folder-based address for code, like `com.nbastats.agent`, that prevents name clashes | A mailing address |
| **Annotation** | A label like `@Service` that tells Spring how to treat a class | A decorator like `@app.get` |
| **Record** | A short way to write a class that only holds data | A form with fixed fields |
| **Compiler** | Checks and translates all the code before it runs, so some mistakes get caught early | Python mostly finds these while running |
| **SDK** | An official library for calling a service, like Anthropic's for Claude | `nba_api`, though that one is unofficial |

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
| **Pull request** | A GitHub page that shows my changes before they're added to `main` |
| **Merge** | Adding a branch's work into another branch, like `main` |
| **Pull (`git pull`)** | Copying new commits from GitHub to my computer. I need this after merging on GitHub |

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

### Compiler errors are helpful
My first version of the tool input code didn't compile, because of a wrong assumption about what an SDK method returns. The compiler caught it before anything ran, so it cost no time debugging and no API credits. The error also pointed to a cleaner approach: converting the input straight into a record.

### Tell the AI what to do when things go wrong
The instructions tell Claude to base answers only on tool results and to say so plainly if something fails. That's why it reported the outage instead of inventing a number. It's worth testing failures for an AI too, because a confident wrong answer is harder to spot than an error message.

### Tool descriptions change how the agent behaves
My Python code warned that the `is_active` field can be out of date, but that warning was only a code comment, and Claude can't read my code. So it said Stephen was the only active Curry as if that were certain. Adding one sentence to the tool description made it pass the warning on to me and flag Seth Curry as possibly out of date. If Claude needs to know something, it has to be in what Claude actually sees.

### Show outside text safely on a web page
The page uses `textContent` rather than `innerHTML` to display answers. `textContent` always treats text as text, while `innerHTML` would make the browser act on any HTML in the answer. I should treat text from outside my code as untrusted, including what an AI writes.

### Protect paid actions from accidents
The buttons turn off during a request, so a double click can't pay for the same question twice. When an action costs money, it's worth asking how it could happen by accident.

### Handle API keys like passwords
A key can spend real money. It only belongs in `.env`, never in code or chat. If one leaks, delete it in the Anthropic console and create a new one. Claude.ai subscriptions and API credits are separate, too.

### A passing test doesn't prove much until I've seen it fail
My first traded-player test had the combined `TOT` row last. Code that ignored `TOT` and grabbed the last row would still have passed, so the test wasn't checking what its name claimed. Moving `TOT` to the middle fixed that. Then we broke the real code on purpose in 19 ways, like raising the loop limit and ignoring refusals, and the tests failed all 19 times.

### Make code easy to test by making its connections changeable
`ClaudeService` always called Anthropic's real address, so a test couldn't redirect it. Adding one setting, `ANTHROPIC_BASE_URL`, lets the tests point it at a fake server. The real code runs unchanged, including the part that sends real web requests.

### Test what matters, not just what's easy
The most valuable test here checks that the agent stops after 10 turns. If that limit broke, nothing would look wrong on the page, but a confused agent could keep spending my credits. It's worth asking which bugs would be the most expensive and the hardest to notice.

### Not every warning needs fixing today
Two warnings appear when the tests run, and neither one fails them. Mockito's says that a way of loading it will stop working in a future Java version. The Python one says the test client prefers a newer package, `httpx2`, over `httpx`. Warnings like these are worth reading and writing down, but they don't have to interrupt what I'm doing.

### Be careful what an app remembers
Memory makes mistakes last longer. Refusals and "too many lookups" replies aren't saved, because they'd confuse later questions. An empty answer would be worse: Claude's API rejects empty messages, so saving one would break every later question in that conversation. Failures can stay in the moment, but whatever gets saved comes back on every request.

### Say so when context is lost
If the app restarts, the page still holds an ID the server has forgotten. The app could treat that as a brand-new conversation, but then "How about his assists?" would arrive with no idea who "his" is. It returns a 404 and explains what happened instead, and it does this before paying for a request to Claude.

### Fakes and the real thing catch different problems
The tests prove that the history gets sent correctly, but only real Claude could show that it understands "his". So I needed both: the tests for every small detail, for free, and one real question to check the whole idea.

### Windows and Linux treat files differently
Linux only runs a file if it's marked **executable**, and Windows doesn't track that at all. Git stored `mvnw` as `100644` ("not executable"), so the robot's Linux computer would have stopped with "Permission denied". `git update-index --chmod=+x agent-app/mvnw` changed it to `100755` ("executable"). Line endings are a similar trap: Windows uses `CRLF` and Linux uses `LF`. We checked, and Git already stores `mvnw` with `LF`. CI is useful precisely because it runs on a different kind of computer from mine.

### Capital letters in file names matter on GitHub
Windows treats `Screenshot.png` and `screenshot.png` as the same file, but Linux (and so GitHub) treats them as different. If the README linked to one spelling and the file had the other, the image would work on my computer and be broken on GitHub. Using all-lowercase file names avoids the problem. Windows can also ignore a rename that only changes capitals, so it's safer to rename in two steps (`Screenshot.png` → `tmp.png` → `screenshot.png`).

### Add alt text to images
In Markdown, `![description](path)` shows an image. The description is **alt text**: screen readers read it aloud to blind users, and it appears if the image can't load. A useful description says what the image shows, not just "screenshot".

### Make the project easy to judge quickly
Someone reviewing a project might give it 30 seconds and won't install anything. A screenshot near the top of the README and a passing-tests badge show that it works without them running it.

### Check before panicking
Right after pushing, GitHub's API said there were zero runs. A few seconds later the run showed up. Some systems take a moment to catch up, so it's worth checking again before deciding something is broken.

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

**Run the whole agent.** This takes two terminals, because both apps need to be running, and the Java app must start from the `agent-app` folder to find `.env`:
```powershell
# Terminal 1
cd stats-service
.\venv\Scripts\python.exe -m uvicorn main:app --port 8000

# Terminal 2
cd agent-app
.\mvnw.cmd spring-boot:run
```

Then open <http://localhost:8080> in a browser and ask away. Each question spends a little of my credits.

**Ask the agent from the terminal instead (this spends credits too):**
```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8080/ask -Method Post -ContentType 'application/json' -Body '{"question":"Who scored more per game in 2025-26, Stephen Curry or Kevin Durant?"}'
```

**Ask a follow-up from the terminal.** Each `/ask` reply includes a `conversationId`. Put it in the next question (this spends credits too):
```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8080/ask -Method Post -ContentType 'application/json' -Body '{"question":"How about his assists?","conversationId":"PASTE-THE-ID-HERE"}'
```

Accented names can look garbled in Windows PowerShell 5.1, because it guesses the wrong text encoding. The browser page shows them correctly.

**Build the Java app and run its tests (free, and neither app needs to be running):**
```powershell
cd agent-app
.\mvnw.cmd test
```

**Run the Python tests (also free):**
```powershell
cd stats-service
.\venv\Scripts\python.exe -m pip install -r requirements-dev.txt   # only needed once
.\venv\Scripts\python.exe -m pytest                                # run them all
.\venv\Scripts\python.exe -m pytest -k traded                      # only tests with "traded" in the name
```

## Practice ideas

1. Open the `/docs` page and trigger each status code in the table above.
2. Add `FG3A` (three-pointers attempted per game) to `LEADER_STATS` in `nba_client.py`. It's one line. Restart the server and try `/leaders?stat=FG3A`.
3. Read `current_season()` in `nba_client.py` and work out what it returns for January 2027 and for September 2027.
4. Change the 70% games minimum and predict what happens to the `/leaders` results before you run it.
5. Ask the agent a question and follow the log. Before you run it, predict how many turns and tool calls it will take.
6. Change `MAX_TURNS` in `ClaudeService.java` to 1, ask a stats question, and work out why you get that reply. Then change it back.
7. Read `systemPrompt()` in `ClaudeService.java` and change "Keep answers short" to something else. See how the answers change.
8. Ask a question you think needs three lookups, then check the log to see whether Claude took the path you expected.
9. Change `--accent` in the page's CSS to your favorite team's color. Restart the Java app to see it.
10. In your browser, press `F12` and open the **Network** tab, then ask a question and find the request to `/ask`.
11. Change `math.ceil` to `math.floor` in `nba_client.py` and run the Python tests. Read the failure message, work out what it's telling you, then change it back.
12. If you try practice idea 2 (`FG3A`), write a test for it first and watch it fail, then add the line and watch it pass. This is called **test-driven development**.
13. Write a Java test for a question that takes three tools in a row, using `ClaudeServiceTest` as a guide.
14. Ask a question, restart the Java app without refreshing the page, then ask a follow-up. Predict what you'll see first.
15. Have a conversation with at least three questions, then find the `/ask` request in the browser's **Network** tab and look at the `conversationId` it sends.
16. Change `MAX_EXCHANGES_PER_CONVERSATION` to 1, ask three linked questions, and see where the agent loses track. Run the tests too, then change it back.
17. Make CI fail on purpose: on a practice branch, break a test (for example, change `math.ceil` to `math.floor`), push, and watch the ❌ appear on GitHub. Open the failed run and find the error in the log. Then fix it, push again, and delete the branch. (A check you've never seen fail doesn't prove much, just like a test.)

## Still to learn

- [x] Merging a branch into `main` and pushing to GitHub
- [x] Creating a Java app with Spring Boot
- [x] How an AI agent uses tools, and writing the loop myself
- [x] Giving the agent more tools
- [x] Building a web page for the app
- [x] Automated tests: programs that re-check my code with one command, including a fake Claude so they cost nothing
- [x] Follow-up questions: remembering a conversation between requests
- [x] Continuous integration: running the tests automatically on GitHub
- [ ] Showing live progress on the page while the agent works
- [ ] Giving the agent more data, like playoffs, standings and game logs
