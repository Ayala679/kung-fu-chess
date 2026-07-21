package tests;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.SSLSession;

import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.enums.Opcode;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.framing.Framedata;
import org.java_websocket.protocols.IProtocol;

/**
 * Minimal WebSocket test double: identity stands in for "a connection",
 * send(String) is recorded, everything else is either a safe no-op or
 * throws (nothing under test needs it). Used to unit-test server.Lobby's
 * pure matching/room logic without opening a real socket.
 */
public class FakeWebSocket implements WebSocket {
    public final List<String> sentMessages = new ArrayList<>();
    private boolean open = true;
    private Object attachment;

    public void setOpen(boolean open) {
        this.open = open;
    }

    @Override public void close(int code, String message) { open = false; }
    @Override public void close(int code) { open = false; }
    @Override public void close() { open = false; }
    @Override public void closeConnection(int code, String message) { open = false; }

    @Override public void send(String text) { sentMessages.add(text); }
    @Override public void send(ByteBuffer bytes) { throw new UnsupportedOperationException(); }
    @Override public void send(byte[] bytes) { throw new UnsupportedOperationException(); }
    @Override public void sendFrame(Framedata framedata) { throw new UnsupportedOperationException(); }
    @Override public void sendFrame(Collection<Framedata> frames) { throw new UnsupportedOperationException(); }
    @Override public void sendPing() { }
    @Override public void sendFragmentedFrame(Opcode op, ByteBuffer buffer, boolean fin) { throw new UnsupportedOperationException(); }

    @Override public boolean hasBufferedData() { return false; }
    @Override public InetSocketAddress getRemoteSocketAddress() { return null; }
    @Override public InetSocketAddress getLocalSocketAddress() { return null; }
    @Override public boolean isOpen() { return open; }
    @Override public boolean isClosing() { return false; }
    @Override public boolean isFlushAndClose() { return false; }
    @Override public boolean isClosed() { return !open; }
    @Override public Draft getDraft() { return null; }
    @Override public ReadyState getReadyState() { return null; }
    @Override public String getResourceDescriptor() { return null; }
    @Override public <T> void setAttachment(T attachment) { this.attachment = attachment; }
    @Override @SuppressWarnings("unchecked") public <T> T getAttachment() { return (T) attachment; }
    @Override public boolean hasSSLSupport() { return false; }
    @Override public SSLSession getSSLSession() { return null; }
    @Override public IProtocol getProtocol() { return null; }
}
