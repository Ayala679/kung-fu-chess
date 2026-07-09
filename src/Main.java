import java.util.Scanner;
import java.util.ArrayList;

import model.Board;
import model.Piece;
import model.GameState;
import gameengine.GameLogic;
import event.EventDispatcher;

public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        ArrayList<String> boardRows = new ArrayList<>();
        boolean isReadingBoard = false;
        GameLogic logic = null;
        EventDispatcher dispatcher = null;

        while (in.hasNextLine()) {
            String line = in.nextLine().trim();
            if (line.isEmpty()) continue;

            if (line.equals("Board:")) {
                isReadingBoard = true;
                continue;
            }
            if (line.equals("Commands:")) {
                isReadingBoard = false;

                if (!boardRows.isEmpty()) {
                    String[] firstRow = boardRows.get(0).split("\\s+");
                    int cols = firstRow.length;
                    Piece[][] grid = new Piece[boardRows.size()][cols];

                    for (int i = 0; i < boardRows.size(); i++) {
                        String[] tokens = boardRows.get(i).split("\\s+");
                        for (int j = 0; j < tokens.length; j++) {
                            grid[i][j] = Piece.fromToken(tokens[j]);
                        }
                    }

                    Board board = new Board(grid);
                    GameState gameState = new GameState();
                    logic = new GameLogic(board, gameState);
                    dispatcher = new EventDispatcher(logic);
                }
                continue;
            }

            if (isReadingBoard) {
                boardRows.add(line);
                continue;
            }

            if (dispatcher != null) {
                dispatcher.dispatchFromCommand(line);
            }
        }
    }
}