package br.com.rian.deck;

import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

final class DeckVoiceSocket implements Closeable {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Socket socket = new Socket();
    private InputStream input;
    private OutputStream output;

    void open() throws Exception {
        open("/v1/assistant/realtime");
    }

    void open(String path) throws Exception {
        socket.connect(new InetSocketAddress("127.0.0.1", 48627), 2500);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(18000);
        input = new BufferedInputStream(socket.getInputStream());
        output = new BufferedOutputStream(socket.getOutputStream());
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String request = "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:48627\r\nConnection: Upgrade\r\n"
            + "Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " + key
            + "\r\nAuthorization: Bearer " + Pairing.TOKEN + "\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        if (!line().startsWith("HTTP/1.1 101 ")) throw new IOException("WebSocket recusado");
        String expected = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
        boolean valid = false;
        for (int i = 0; i < 30; i++) {
            String header = line();
            if (header.isEmpty()) {
                if (!valid) throw new IOException("Resposta WebSocket inválida");
                socket.setSoTimeout(0);
                return;
            }
            if (header.toLowerCase(Locale.ROOT).startsWith("sec-websocket-accept:"))
                valid = expected.equals(header.substring(header.indexOf(':') + 1).trim());
        }
        throw new IOException("Cabeçalhos WebSocket inválidos");
    }

    private String line() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 4096; i++) {
            int next = one();
            if (next == 10) return bytes.toString("US-ASCII").trim();
            bytes.write(next);
        }
        throw new IOException("Cabeçalho muito longo");
    }

    private int one() throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    synchronized void send(int opcode, byte[] payload) throws IOException {
        if (payload.length > 65535) throw new IOException("Quadro grande demais");
        output.write(0x80 | opcode);
        if (payload.length < 126) output.write(0x80 | payload.length);
        else {
            output.write(0xfe);
            output.write(payload.length >>> 8);
            output.write(payload.length);
        }
        byte[] key = new byte[4];
        RANDOM.nextBytes(key);
        output.write(key);
        byte[] masked = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) masked[i] = (byte)(payload[i] ^ key[i % 4]);
        output.write(masked);
        output.flush();
    }

    interface Receiver { void receive(int opcode, byte[] bytes) throws Exception; }

    void read(Receiver receiver) throws Exception {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int kind = 0;
        while (!socket.isClosed()) {
            int first = one(), opcode = first & 15, second = one();
            if ((first & 0x70) != 0 || (second & 0x80) != 0) throw new IOException("Quadro inválido");
            int size = second;
            if (size == 126) size = one() * 256 + one();
            else if (size == 127) {
                long total = 0;
                for (int i = 0; i < 8; i++) total = (total << 8) | one();
                if (total < 0 || total > 131072) throw new IOException("Quadro grande demais");
                size = (int)total;
            }
            if (size + message.size() > 131072) throw new IOException("Mensagem grande demais");
            byte[] bytes = new byte[size];
            new DataInputStream(input).readFully(bytes);
            if (opcode == 8) return;
            if (opcode == 9) { send(10, bytes); continue; }
            if (opcode == 10) continue;
            if (opcode != 0) kind = opcode;
            message.write(bytes);
            if ((first & 0x80) != 0) {
                receiver.receive(kind, message.toByteArray());
                message.reset();
            }
        }
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
