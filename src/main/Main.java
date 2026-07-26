// Git repo: https://github.com/Ayala679/kung-fu-chess
import java.util.Scanner;

import controller.BoardController;

public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);

        // BoardController reads the board (up to "Commands:") and wires the
        // event/engine chain; remaining lines are dispatched as commands.
        BoardController controller = BoardController.readFrom(in);

        while (in.hasNextLine()) {
            String line = in.nextLine().trim();
            if (line.isEmpty()) continue;
            controller.executeCommand(line);
        }
    }
}
