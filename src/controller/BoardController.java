package controller;

import java.util.Scanner;

import event.EventDispatcher;
import event.EventEngine;
import gameengine.GameEngine;
import model.Board;
import model.GameState;
import parsing.BoardMapper;

/**
 * BoardController: wires the full chain for a game session and exposes a single
 * entry point for commands. Input -> EventDispatcher -> EventEngine -> GameEngine.
 */
public class BoardController {
    private final GameEngine engine;
    private final EventDispatcher dispatcher;

    private BoardController(GameEngine engine) {
        this.engine = engine;
        this.dispatcher = new EventDispatcher(new EventEngine(engine));
    }

    public static BoardController readFrom(Scanner sc) {
        Board board = BoardMapper.readBoard(sc);
        GameEngine engine = new GameEngine(board, new GameState());
        return new BoardController(engine);
    }

    public boolean isEmpty() { return engine.isEmpty(); }
    public boolean isValid() { return engine.isValid(); }

    /** Unified command entry point - every command flows through here. */
    public void executeCommand(String command) {
        dispatcher.dispatchFromCommand(command);
    }
}
