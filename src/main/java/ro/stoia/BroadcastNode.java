package ro.stoia;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class BroadcastNode {

    private static final Logger log = LoggerFactory.getLogger(BroadcastNode.class);
    static final int MSG_SIZE = 1024;
    static final int PAYLOAD_END = 1004; // bytes [0..1003] inclusive => 1004 bytes
    static final int SHA_OFFSET = 1004;  // bytes [1004..1023] => 20 bytes
    static final int SHA_LEN = 20;
    static final HexFormat HEX = HexFormat.of();

    record Endpoint(InetAddress addr, int port) {
    }

    record Config(int broadcastsPerNode, List<Endpoint> nodes) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java BroadcastNode <config.txt> <NodeIndex>");
            System.exit(2);
        }

        Path configPath = Paths.get(args[0]);
        int myIndex = Integer.parseInt(args[1]);

        Config cfg = parseConfig(configPath);
        int N = cfg.broadcastsPerNode;
        List<Endpoint> nodes = cfg.nodes;
        int M = nodes.size();

        if (myIndex < 0 || myIndex >= M) {
            System.err.println("Invalid NodeIndex=" + myIndex + ", must be in [0," + (M - 1) + "]");
            System.exit(2);
        }

        Endpoint me = nodes.get(myIndex);


        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(me.addr, me.port));
        socket.setSoTimeout(5000); // 5s max block on receive
        log.info(" Started node {} bound to {}:{} at {}", myIndex, me.addr.getHostAddress(), me.port, ts());

        AtomicInteger receivedCount = new AtomicInteger(0);
        int expectedReceives = N * M;

        Thread receiver = new Thread(() -> {
            byte[] buf = new byte[MSG_SIZE];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);

            while (receivedCount.get() < expectedReceives && !socket.isClosed()) {
                try {
                    pkt.setLength(MSG_SIZE); // important if reusing packet
                    socket.receive(pkt);

                    int len = pkt.getLength();
                    if (len != MSG_SIZE) {
                        log.error("Invalid packet length=" + len + " from " + pkt.getAddress() + ":" + pkt.getPort());
                        continue;
                    }

                    byte[] msg = Arrays.copyOf(pkt.getData(), MSG_SIZE);

                    int src = Byte.toUnsignedInt(msg[0]);
                    byte[] sentSha = Arrays.copyOfRange(msg, SHA_OFFSET, SHA_OFFSET + SHA_LEN);
                    byte[] calcSha = sha1(msg);

                    boolean ok = Arrays.equals(sentSha, calcSha);

                    String line = (ok ? "OK " : "FAIL ")
                            + src + " "
                            + HEX.formatHex(sentSha) + " "
                            + HEX.formatHex(calcSha);

                    log.info(line);

                    receivedCount.incrementAndGet();

                } catch (SocketTimeoutException ste) {
                    // allowed: no longer than 5 seconds waiting
                    // If we haven't reached expectedReceives, keep waiting; network may be slow.
                    log.error("Receive timeout (5s). received={}/{} :", receivedCount.get(), expectedReceives, ste);
                } catch (IOException ioe) {
                    if (!socket.isClosed()) log.error("Receive IO error: ", ioe);
                } catch (Exception e) {
                    log.error("Receiver error: ", e);
                }
            }
        }, "receiver-" + myIndex);

        receiver.start();

        // Wait 15s after startup before broadcasting
        log.info(" Waiting 15s before broadcasting...\n");
        Thread.sleep(15_000);

        SecureRandom rng = new SecureRandom();

        for (int i = 0; i < N; i++) {
            byte[] msg = new byte[MSG_SIZE];
            msg[0] = (byte) myIndex;
            byte[] rnd = new byte[1003];
            rng.nextBytes(rnd);
            System.arraycopy(rnd, 0, msg, 1, 1003);

            byte[] digest = sha1(msg);
            System.arraycopy(digest, 0, msg, SHA_OFFSET, SHA_LEN);

            for (Endpoint ep : nodes) {
                DatagramPacket out = new DatagramPacket(msg, msg.length, ep.addr, ep.port);
                try {
                    socket.send(out);
                } catch (IOException ioe) {
                    log.error("Send error to {}:{} : {}", ep.addr.getHostAddress(), ep.port, ioe.getMessage());
                }
            }
        }

        log.info(" Done sending N={} messages (fanout to M={}). Expecting receives={}\n", N, M, expectedReceives);

        // Wait for receiver to reach N*M; if it stalls forever, you can add a global deadline if required.
        receiver.join();

        socket.close();
        log.info(" Exiting. received={}/{}\n", receivedCount.get(), expectedReceives);

    }

    static Config parseConfig(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IllegalArgumentException("Empty config");

        int N = Integer.parseInt(lines.get(0).trim());
        List<Endpoint> nodes = new ArrayList<>();

        for (int i = 1; i < lines.size(); i++) {
            String raw = lines.get(i).trim();
            if (raw.isEmpty()) continue;
            int hash = raw.indexOf('#');
            if (hash >= 0) raw = raw.substring(0, hash).trim();
            if (raw.isEmpty()) continue;

            String[] parts = raw.split("\\s+");
            if (parts.length < 2) throw new IllegalArgumentException("Bad config line: " + lines.get(i));

            InetAddress ip = InetAddress.getByName(parts[0]);
            int port = Integer.parseInt(parts[1]);
            nodes.add(new Endpoint(ip, port));
        }

        if (nodes.isEmpty()) throw new IllegalArgumentException("No nodes in config");
        return new Config(N, nodes);
    }

    static byte[] sha1(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(data, 0, BroadcastNode.PAYLOAD_END);
        return md.digest();
    }

    static String ts() {
        return Instant.now().toString();
    }

}

