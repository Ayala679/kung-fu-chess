package controller;

import java.util.Scanner;
import gameengine.GameLogic;
import event.EventDispatcher;

public class BoardController {
    private GameLogic engine;
    private EventDispatcher dispatcher;

    private BoardController(GameLogic engine) { 
        this.engine = engine;
        this.dispatcher = new EventDispatcher(engine);
    }

    public static BoardController readFrom(Scanner sc) {
        GameLogic logic = GameLogic.readFrom(sc);
        return new BoardController(logic);
    }

    public boolean isEmpty() { return engine.isEmpty(); }
    public boolean isValid() { return engine.isValid(); }

    // Unified command dispatcher - handles all commands through events
    public void executeCommand(String command) {
        dispatcher.dispatchFromCommand(command);
    }
}

