# Kung Fu Chess

A Java implementation of Kung Fu Chess (real-time chess: pieces move simultaneously
and a move takes time to complete). The project is organized around **clear layer
separation** — each class owns exactly one responsibility.

## Package structure

```
src/
├── model/        ← pure data (no game logic)
│   ├── Position.java      immutable (row,col) + distance helpers
│   ├── Piece.java         color + type, promotedAt() (no token knowledge)
│   ├── Board.java         the grid + safe cell accessors
│   ├── MovingPiece.java   a piece in transit (from→to, timing)
│   └── GameState.java     virtual clock + game-over flag
│
├── parsing/      ← turn raw text into a Board (owns the "wK" token format)
│   ├── BoardParser.java   text → String[][] (+ row-width check)
│   ├── BoardValidator.java validates tokens
│   ├── PieceMapper.java   token ↔ Piece (parse / format), the only token codec
│   └── BoardMapper.java   orchestrates parse → validate → map to Board
│
├── ruleengine/   ← "is this move allowed?" (never mutates anything)
│   ├── PieceRules.java     one switch-case: geometry per piece type
│   └── MoveValidator.java  general checks for ANY piece (source non-empty, not
│                           friendly-occupied, path clear) — no piece type, no config
│
├── gameengine/   ← the game itself
│   ├── GameEngine.java     central gateway: validates, schedules, decides game-over
│   └── RealTimeArbiter.java owns pieces-in-transit + virtual time + atomic board updates
│
├── event/        ← the input side
│   ├── EventEngine.java    click semantics (select / cancel / re-select / move request)
│   ├── EventMapper.java    command string → GameEvent
│   ├── InputMapper.java    pixel coords → cell coords
│   ├── EventDispatcher.java routes events to the EventEngine
│   ├── GameEvent.java + *EventImpl.java  one event type per command
│   └── ClickEvent.java / CellClickEvent.java  input data holders
│
├── view/
│   └── BoardRenderer.java  renders the board (including in-transit pieces)
│
├── controller/
│   └── BoardController.java wires the whole chain, exposes executeCommand()
│
├── config/
│   └── GameConfig.java     all constants (durations, cell size, token patterns)
│
└── Main.java               reads stdin, delegates to BoardController
```

## How a command flows

```
stdin ─▶ BoardController ─▶ EventDispatcher ─▶ EventEngine ─▶ GameEngine
                                                                 │
                                        RuleEngine (MoveValidator + PieceRules)
                                                                 │
                                                          RealTimeArbiter ─▶ Board
                                                                 │
                                                          BoardRenderer ─▶ stdout
```

- **EventEngine** interprets clicks and produces a ready `(from, to)` move request.
- **GameEngine** is the single gateway: it asks the **RuleEngine** whether the move is
  allowed, computes its duration, and hands scheduling to the **RealTimeArbiter**.
- **RealTimeArbiter** owns everything about time: it holds the active moves, advances
  the virtual clock, decides when a move arrives, and applies the board change
  atomically. Tests never sleep — they push virtual time forward via `wait`.

## Movement rules — one class, one switch

Every piece's movement rule lives in a single class, **`ruleengine.PieceRules`**, as a
`switch` over `Piece.Type`. A piece is just data (`Piece` holds its type); the rules
are centralized in one place.

```java
switch (type) {
    case K: return rowDist <= 1 && colDist <= 1;
    case R: return (rowDist == 0 || colDist == 0) && pathClear(...);
    case B: return (rowDist == colDist)           && pathClear(...);
    case Q: return (rowDist == 0 || colDist == 0 || rowDist == colDist) && pathClear(...);
    case N: return (rowDist == 1 && colDist == 2) || (rowDist == 2 && colDist == 1);
    case P: return isValidPawn(...);
}
```

**Adding a new piece = adding one `case`** — no new class per piece. This keeps the
rule set compact and readable as the number of piece types grows.

## Build & run

```bash
cd src

# compile (UTF-8 because of the checkmarks in comments; excludes JUnit test files)
javac -encoding UTF-8 -d out @sources.txt

# run the game (reads a board + commands from stdin)
java -cp out Main < input.txt
```

`Main` is the only entry point (a single class defining `public static void main` -
this matters for graders/tools that auto-detect the entry point instead of being
told explicitly which class to run).

## Tests & coverage

Unit tests (JUnit 5) live in `src/tests/`. To compile, run them, and generate a
JaCoCo HTML coverage report in one step:

```powershell
powershell -File tools/run-tests.ps1
```

This has no Maven/Gradle dependency - it downloads the JUnit console launcher
and JaCoCo jars into `tools/` on first run (not committed to git), then opens
the report at `out/coverage-html/index.html`.

Input format:

```
Board:
wK bK . .
. . . .
Commands:
click 0 0
wait 500
print board
```

Commands: `click x y`, `jump x y` (pixel coordinates), `wait ms`, `print board`.
