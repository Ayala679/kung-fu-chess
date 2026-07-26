import java.util.Scanner;
import event.EventEngine;
import gameengine.GameEngine;
import model.Board;
import model.GameState;
import parsing.BoardMapper;
import view.BoardWindow;
import view.ImgRenderer;
/**
 * Manual entry point for the graphical UI - separate from Main.java, which
 * stays console/Scanner-based. Reads a board the same way
 * controller.BoardController.readFrom does, then opens an interactive window.
 */
public class GuiMain {
    public static void main(String[] args) {
        GameEngine.DEBUG_LOGGING = true; // print why a move was accepted/rejected, for bug reports
        Scanner in = new Scanner(System.in);
        Board board = BoardMapper.readBoard(in);
        GameEngine engine = new GameEngine(board, new GameState());
        EventEngine eventEngine = new EventEngine(engine);
        ImgRenderer renderer = new ImgRenderer("resources/board.png");
        new BoardWindow(eventEngine, renderer).show();
    }
}