package view;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import event.GameClient;
import model.Position;
import snapshot.GameSnapshot;

/**
 * The real interactive window: a JPanel that repaints the latest rendered
 * frame and forwards mouse clicks to EventEngine.handleClick. Img.show() alone
 * only pops a static, non-interactive JFrame, so this is a small custom panel
 * instead. A Swing Timer feeds real elapsed time into EventEngine.waitFor so
 * moves animate on their own instead of needing typed "wait" commands.
 */
public class BoardWindow {
    private static final int TICK_MS = 16;

    private final GameClient eventEngine;
    private final ImgRenderer renderer;
    private final JPanel panel;
    private final int boardWidthPx;
    private final int boardHeightPx;
    private volatile BufferedImage currentFrame;

    public BoardWindow(GameClient eventEngine, ImgRenderer renderer) {
        this.eventEngine = eventEngine;
        this.renderer = renderer;
        this.boardWidthPx = renderer.getBoardWidthPx();
        this.boardHeightPx = renderer.getBoardHeightPx();
        this.currentFrame = renderer.render(eventEngine.snapshot()).get();

        panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Rectangle dest = letterboxRect(getWidth(), getHeight());
                g.drawImage(currentFrame, dest.x, dest.y, dest.width, dest.height, null);
            }
        };
        panel.setPreferredSize(new Dimension(currentFrame.getWidth(), currentFrame.getHeight()));
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // mousePressed (not mouseClicked) - mouseClicked silently doesn't
                // fire if the mouse drifts even slightly between press and
                // release, which was dropping clicks intermittently.

                // paintComponent fits currentFrame into the panel's actual size
                // (getWidth()/getHeight()) preserving its aspect ratio - see
                // letterboxRect - rather than stretching it to exactly fill an
                // arbitrarily-resized window (which distorted the board/tables
                // into ovals/rectangles whenever the window wasn't at the
                // image's own 1342x1080-ish proportions). The click has to be
                // mapped back through that same letterboxed rectangle, not a
                // plain per-axis scale, or clicks would land on the wrong cell
                // everywhere the window doesn't match the image's aspect ratio.
                Rectangle dest = letterboxRect(panel.getWidth(), panel.getHeight());
                if (!dest.contains(e.getPoint())) return; // click landed in a letterbox bar, not on the image at all
                double scale = currentFrame.getWidth() / (double) dest.width;
                int imageX = (int) ((e.getX() - dest.x) * scale);
                int imageY = (int) ((e.getY() - dest.y) * scale);

                int boardX = imageX - ImgRenderer.BOARD_OFFSET_X;
                int boardY = imageY - ImgRenderer.BOARD_OFFSET_Y;
                if (boardX < 0 || boardY < 0 || boardX >= boardWidthPx || boardY >= boardHeightPx) {
                    return; // click landed outside the board (e.g. on a move table)
                }

                Position cell = BoardGeometry.cellAt(boardX, boardY, boardWidthPx, boardHeightPx);
                if (SwingUtilities.isRightMouseButton(e)) {
                    eventEngine.handleJump(cell.getRow(), cell.getCol()); // in-place dodge
                } else {
                    eventEngine.handleClick(cell.getRow(), cell.getCol());
                }
                refresh();
            }
        });

        // 'R' once the game is over requests a rematch - a no-op in offline play (see GameClient.requestRematch()).
        panel.setFocusable(true);
        panel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_R && eventEngine.snapshot().gameOver()) {
                    eventEngine.requestRematch();
                }
            }
        });

        new Timer(TICK_MS, e -> {
            eventEngine.waitFor(TICK_MS);
            refresh();
        }).start();
    }

    private void refresh() {
        Img frame = renderer.render(eventEngine.snapshot());
        long remainingMs = eventEngine.millisUntilOpponentAutoAbandon();
        if (remainingMs > 0) {
            renderer.drawDisconnectCountdown(frame, remainingMs);
        }
        currentFrame = frame.get();
        panel.repaint();
    }

    /** The largest rectangle of currentFrame's own aspect ratio that fits within (availableWidth, availableHeight), centered - so the board/tables scale evenly instead of stretching into an arbitrary window shape. */
    private Rectangle letterboxRect(int availableWidth, int availableHeight) {
        if (availableWidth <= 0 || availableHeight <= 0) return new Rectangle(0, 0, 0, 0);
        double imageAspect = currentFrame.getWidth() / (double) currentFrame.getHeight();
        double availableAspect = availableWidth / (double) availableHeight;
        int width;
        int height;
        if (availableAspect > imageAspect) {
            height = availableHeight;
            width = (int) Math.round(height * imageAspect);
        } else {
            width = availableWidth;
            height = (int) Math.round(width / imageAspect);
        }
        return new Rectangle((availableWidth - width) / 2, (availableHeight - height) / 2, width, height);
    }

    public void show() {
        show(null);
    }

    /** Same as {@link #show()}, but with a subtitle (e.g. a room code) appended to the window title. */
    public void show(String subtitle) {
        JFrame frame = new JFrame(subtitle == null || subtitle.isEmpty() ? "Kung Fu Chess" : "Kung Fu Chess - " + subtitle);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        panel.requestFocusInWindow(); // so the 'R' (rematch) key listener actually receives key events
    }
}
