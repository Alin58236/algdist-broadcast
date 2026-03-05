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
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class BroadcastNode {

    private static final Logger log = LoggerFactory.getLogger(BroadcastNode.class);
    static final int MSG_SIZE = 1024;
    static final int PAYLOAD_END = 1004; // bytes [0..1003] inclusive => 1004 bytes
    static final int SHA_OFFSET = 1004;  // bytes [1004..1023] => 20 bytes
    static final int SHA_LEN = 20;
    static final HexFormat HEX = HexFormat.of();
    static final int SO_TIMEOUT_MS = 5000;
    static final int STARTUP_WAIT_MS = 10_000;
    static final int SEND_PACE_MS = 1; // bottleneck for sending => avoids losing packets

    //records
    record Endpoint(InetAddress addr, int port) {}
    record Config(int broadcastsPerNode, java.util.Map<Integer, Endpoint> nodesById) {}

    public static void main(String[] args) throws Exception {

        //initial config

        if (args.length != 2) {
            System.err.println("Usage: java BroadcastNode <config.txt> <NodeIndex>");
            System.exit(2);
        }

        Path configPath = Paths.get(args[0]);
        int myIndex = Integer.parseInt(args[1]);
        Config cfg = parseConfig(configPath);

        int N = cfg.broadcastsPerNode;
        Map<Integer,Endpoint> nodes = cfg.nodesById;

        int M = nodes.size(); // number of nodes
        Endpoint me = nodes.get(myIndex);
        if (me == null) {
            System.err.println("NodeIndex=" + myIndex + " not present in config. Known ids: " + nodes.keySet());
            System.exit(2);
        }

        //open udp socket
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(me.addr, me.port));
        socket.setSoTimeout(SO_TIMEOUT_MS);

        log.info(" Started node {} bound to {}:{} at {}", myIndex, me.addr.getHostAddress(), me.port, ts());

        AtomicInteger receivedCount = new AtomicInteger(0);
        int expectedReceives = N * M;

        Thread receiver = new Thread(() -> {
            byte[] buf = new byte[MSG_SIZE];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);

            int consecutiveTimeouts = 0;

            while (receivedCount.get() < expectedReceives && !socket.isClosed()) {
                try {
                    pkt.setLength(MSG_SIZE);
                    socket.receive(pkt);
                    consecutiveTimeouts = 0;

                    int len = pkt.getLength();
                    if (len != MSG_SIZE) {
                        log.error("Invalid packet length={} from {}:{}", len, pkt.getAddress(), pkt.getPort());
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
                    consecutiveTimeouts++;
                    log.warn("Receive timeout ({}ms). received={}/{}", SO_TIMEOUT_MS, receivedCount.get(), expectedReceives);

//                     test terminate after 5 consecutive timeouts
                     if (consecutiveTimeouts >= 5) {
                         log.info("FAIL timeout_limit reached received={}/{}", receivedCount.get(), expectedReceives);
                         break;
                     }
                } catch (IOException ioe) {
                    if (!socket.isClosed()) log.error("Receive IO error: ", ioe);
                    break;
                } catch (Exception e) {
                    log.error("Receiver error: ", e);
                    break;
                }
            }
        }, "receiver-" + myIndex);

        Thread sender = new Thread(() -> {
            try {
                log.info("[SENDER THREAD] Waiting {}ms before broadcasting...\n", STARTUP_WAIT_MS);
                Thread.sleep(STARTUP_WAIT_MS);

                SecureRandom rng = new SecureRandom();

                for (int i = 0; i < N && !socket.isClosed(); i++) {
                    byte[] msg = new byte[MSG_SIZE];
                    msg[0] = (byte) myIndex;

                    byte[] rnd = new byte[1003];
                    rng.nextBytes(rnd);
                    System.arraycopy(rnd, 0, msg, 1, 1003);

                    byte[] digest = sha1(msg);
                    System.arraycopy(digest, 0, msg, SHA_OFFSET, SHA_LEN);

                    // test: every 5th message send a bad one
                    // if (i % 5 == 4) {
                    //     int pos = 1 + (i % 1003);
                    //     msg[pos] ^= 0x01;
                    // }

                    for (Endpoint ep : nodes.values()) {
                        DatagramPacket out = new DatagramPacket(msg, msg.length, ep.addr, ep.port);
                        try {
                            socket.send(out);
                            if (SEND_PACE_MS > 0) Thread.sleep(SEND_PACE_MS);
                        } catch (IOException ioe) {
                            log.error("Send error to {}:{} : {}", ep.addr.getHostAddress(), ep.port, ioe.getMessage());
                        }
                    }
                }

                log.info(" Done sending N={} messages (fanout to M={}). Expecting receives={}\n", N, M, expectedReceives);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Sender error: ", e);
            }
        }, "sender-" + myIndex);

        receiver.start();
        sender.start();

        // wait both threads to finish and joion
        sender.join();
        receiver.join();

        socket.close();
        log.info(" Exiting. received={}/{}\n", receivedCount.get(), expectedReceives);
    }

    static Config parseConfig(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IllegalArgumentException("Empty config");

        int N = Integer.parseInt(lines.getFirst().trim());

        java.util.Map<Integer, Endpoint> nodesById = new java.util.LinkedHashMap<>();
        int implicitId = 0;

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

            int id = (parts.length >= 3) ? Integer.parseInt(parts[2]) : implicitId;
            implicitId++;

            if (nodesById.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate node id: " + id);
            }

            nodesById.put(id, new Endpoint(ip, port));
        }

        if (nodesById.isEmpty()) throw new IllegalArgumentException("No nodes in config");

        return new Config(N, nodesById);
    }


    static byte[] sha1(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(data, 0, PAYLOAD_END);
        return md.digest();
    }

    static String ts() {
        return Instant.now().toString();
    }
}
