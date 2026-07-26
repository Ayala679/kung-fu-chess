package view;

import java.awt.Dimension;
import java.awt.Graphics;
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
                g.drawImage(currentFrame, 0, 0, getWidth(), getHeight(), null);
            }
        };
        panel.setPreferredSize(new Dimension(currentFrame.getWidth(), currentFrame.getHeight()));
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // mousePressed (not mouseClicked) - mouseClicked silently doesn't
                // fire if the mouse drifts even slightly between press and
                // release, which was dropping clicks intermittently.

                // paintComponent scales currentFrame to fit the panel's actual
                // size (getWidth()/getHeight()), which isn't always exactly the
                // canvas's own pixel size (e.g. if the window got constrained to
                // fit the screen) - so the click has to be scaled back the same
                // way before it means anything in image-pixel terms.
                double scaleX = currentFrame.getWidth() / (double) panel.getWidth();
                double scaleY = currentFrame.getHeight() / (double) panel.getHeight();
                int imageX = (int) (e.getX() * scaleX);
                int imageY = (int) (e.getY() * scaleY);

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

        new Timer(TICK_MS, e -> {
            eventEngine.waitFor(TICK_MS);
            refresh();
        }).start();
    }

    private void refresh() {
        currentFrame = renderer.render(eventEngine.snapshot()).get();
        panel.repaint();
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
    }
}
