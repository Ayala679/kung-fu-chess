package parsing;

import java.util.Scanner;

import model.Board;
import model.Piece;

/**
 * BoardMapper: converts raw board input into a model {@link Board}.
 * Orchestrates the board-helper steps: parse (BoardParser) -> validate
 * (BoardValidator) -> map tokens to Pieces (PieceMapper). An invalid/empty
 * input yields an empty board.
 */
public class BoardMapper {
    public static Board readBoard(Scanner input) {
        String[][] tokens = BoardParser.readBoard(input);
        if (tokens.length == 0 || !BoardValidator.isValid(tokens)) {
            return new Board(new Piece[0][0]);
        }
        return build(tokens);
    }

    private static Board build(String[][] tokens) {
        Piece[][] grid = new Piece[tokens.length][tokens[0].length];
        for (int i = 0; i < tokens.length; i++) {
            for (int j = 0; j < tokens[i].length; j++) {
                grid[i][j] = PieceMapper.parse(tokens[i][j]);
            }
        }
        return new Board(grid);
    }
}
