package event;

public class EventMapper {
    /**
     * Maps command-line input to a GameEvent.
     * Expected formats:
     *   - "click x y" → ClickEventImpl
     *   - "jump x y" → JumpEventImpl
     *   - "wait ms" → WaitEventImpl
     *   - "print board" → PrintBoardEventImpl
     */
    public static GameEvent mapCommand(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0) return null;

        String command = parts[0];

        switch (command) {
            case "click":
                if (parts.length >= 3) {
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    return new ClickEventImpl(x, y);
                }
                break;

            case "jump":
                if (parts.length >= 3) {
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    return new JumpEventImpl(x, y);
                }
                break;

            case "wait":
                if (parts.length >= 2) {
                    long ms = Long.parseLong(parts[1]);
                    return new WaitEventImpl(ms);
                }
                break;

            case "print":
                if (parts.length >= 2 && parts[1].equals("board")) {
                    return new PrintBoardEventImpl();
                }
                break;
        }

        return null;
    }
}

