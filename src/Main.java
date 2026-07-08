// Repository: TODO: add git repo URL here
// Example: https://github.com/your-org/your-repo
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        Board board = Board.readFrom(sc);

        if (board.isEmpty()) {
            sc.close();
            return;
        }

        if (!board.isValid()) {
            sc.close();
            return;
        }

        while (sc.hasNextLine()) {
            String line = sc.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\\s+");
            String command = parts[0];

            if (command.equals("click")) {
                if (parts.length >= 3) {
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    board.handleClick(x, y);
                }
            } else if (command.equals("jump")) {
                if (parts.length >= 3) {
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    board.handleJump(x, y);
                }
            } else if (command.equals("wait")) {
                if (parts.length >= 2) {
                    long ms = Long.parseLong(parts[1]);
                    board.handleWait(ms);
                }
            } else if (command.equals("print")) {
                if (parts.length >= 2 && parts[1].equals("board")) {
                    board.printBoard();
                }
            }
        }

        sc.close();
    }
}