# Kung Fu Chess — Architecture Notes

The project is graded on **architecture**: correct separation of responsibilities
between layers. This document records what each layer owns and why.

## Layers & responsibilities (one job each)

| Layer / class | Single responsibility |
|---|---|
| **model** | Hold data only, no game logic. `Position`, `Piece`, `Board`, `MovingPiece`, `GameState`. |
| **parsing** | Turn raw text into a `Board` and own the token format. `BoardParser` (text→grid), `BoardValidator` (token check), `PieceMapper` (token↔`Piece`), `BoardMapper` (orchestrate parse→validate→map). |
| **ruleengine** | Answer *"is this move allowed?"* without changing anything. `MoveValidator` = general checks that hold for **any** piece (in bounds, source non-empty, not friendly-occupied, path clear) — depends only on `model`. `PieceRules` = piece-type-specific geometry. `RuleEngine` is the single entry point combining the two; `GameEngine` calls only `RuleEngine`, never `MoveValidator`/`PieceRules` directly. |
| **gameengine** | Run the game. `GameEngine` = central gateway (validate, schedule, decide game-over). `RealTimeArbiter` = time + pieces-in-transit + atomic board updates. |
| **event** | The input side. `EventEngine` (click semantics + selection), `EventMapper`/`InputMapper` (parse command / pixels), `EventDispatcher` + `GameEvent` impls. |
| **view** | Render only. `BoardRenderer`. |
| **controller** | Wire the chain, expose one entry point. `BoardController`. |
| **config** | Constants only. `GameConfig`. |

## Dependency direction (no cycles)

```
model  ◀── everything (model depends on nothing)
config ◀── parsing, ruleengine, gameengine, event
ruleengine ─▶ model, config
gameengine ─▶ ruleengine, view, model, config
event      ─▶ gameengine, model
controller ─▶ parsing, gameengine, event, model
```

`model` and `config` are leaves. The input side (`event`) points at the engine; the
engine never points back at input. No package depends on `controller` or `Main`.

## Key design decision: movement rules as one switch, not a class-per-piece

Movement rules live in a single class, `ruleengine.PieceRules`, as a `switch` over
`Piece.Type`. `Piece` is just data that carries its type; `PieceRules` is the one
place that knows how each type moves.

- **Why not a class per piece (Strategy pattern)?** With many piece types that would
  mean many small classes. A single switch keeps every rule visible in one place and
  makes adding a piece a one-line change (`case X:`), which is easier to read and
  maintain at scale.
- Shared geometry (`pathClear`) is a private helper inside `PieceRules`.
- `PieceRules` only *inspects* the board — it never moves or captures. Execution is
  the arbiter's job.

## Real-time model

- The clock is **virtual**. `wait ms` advances `GameState.currentTime`; nothing sleeps.
  This makes runs deterministic and testable.
- **The board is only mutated on arrival.** While a piece is in transit the board is
  unchanged; `BoardRenderer` shows the transit state without touching the model.
  `RealTimeArbiter.update()` applies the source-clear + destination-set atomically,
  handles mid-air captures, promotes pawns, and reports a king capture via `GameState`.

## SRP self-audit (honest notes)

Clean: model has no logic; parsing/rules/engine/view/event each own one concern; the
input→engine direction is one-way.

**Token format lives in one place:** `parsing.PieceMapper` is the only code that knows
the `"wK"` encoding (both `parse` and `format`). The model (`Piece`) is built from a
`Color` + `Type` via `Piece.of(...)` and carries no token knowledge, so a new
input/output format touches only `PieceMapper`.

Minor coupling worth knowing about (deliberate trade-offs, not accidental leaks):

1. **`Piece.promotedAt(row, height)`** — promotion is a rule that lives on the model
   piece, chosen so the engine and the renderer share one definition (DRY). Strictly a
   rule, so it could move into `ruleengine` if promotion ever becomes configurable.
2. **`Piece.moveDuration(distance)`** — same trade-off, same reason: how long a move
   takes depends on piece type, so it lives next to `Type` rather than as a duplicated
   if/else inside `GameEngine`. `GameEngine.requestMove` just calls `piece.moveDuration(...)`.
3. **`GameEngine` holds a `BoardRenderer`** — so a `print` request has somewhere to go.
   This couples the engine to the view; a stricter split would let the controller own
   the renderer.
4. **`view.BoardRenderer` depends on `parsing.PieceMapper`** — the renderer formats
   pieces for output through the shared token codec. No cycle (`PieceMapper` depends
   only on `model`); it is the price of keeping one single source for the encoding.

Note: token/format validity now lives only in `parsing.BoardValidator.isValidToken`
(it is the parsing concern), and `MoveValidator` no longer depends on `Piece.Type` or
`GameConfig` — general movement rules only.

## Extensibility

- **New piece type:** add a `case` to `PieceRules.isValid`.
- **New command:** add a `GameEvent` impl + a branch in `EventMapper`.
- **New board backend:** `Board` accessors are the only contract the engine relies on.
