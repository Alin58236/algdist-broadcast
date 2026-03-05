# bcastnode — UDP Broadcast Node

A Java program that runs multiple “nodes” (separate JVM processes).  
Each node sends UDP datagrams to every node listed in a config file, and validates received datagrams by comparing a SHA-1 included in the packet with a SHA-1 recomputed from the payload.


## Code Choices

### 1. The `Thread.sleep(1)` Bottleneck (UDP Pacing)
UDP is a "fire-and-forget" protocol with no built-in flow control. If you execute a `for` loop calling `socket.send(...)` 1,000 times without any delay, the CPU blasts those packets into the OS network stack almost instantly. 

When this massive burst hits the receiver, the OS places the packets into the socket's receive buffer. Because the receiver thread takes a tiny fraction of time to wake up, read the packet, compute a SHA-1 hash, and log it, the buffer quickly overflows. Once full, the OS starts **silently dropping datagrams**. 

By adding a `Thread.sleep(1)` between sends, we implement **application-level pacing**. This limits the burst rate, giving the receiver thread enough time to pull packets out of the OS buffer before it overflows, drastically reducing packet loss caused by local bottlenecking.

### 2. Separate Threads for Sending and Receiving
By splitting sending and receiving into two distinct threads (`SenderTask` and `ReceiverTask`), we avoid race conditions and missed packets:
- **The Receiver Thread** starts immediately and blocks on `socket.receive()`. It is constantly listening and processing data as it arrives.
- **The Sender Thread** sleeps for an initial `STARTUP_WAIT_MS` (to ensure all other nodes have fully started their receiver threads) before it begins transmitting.

Concurrent `send()` and `receive()` on the same `DatagramSocket` is fully thread-safe in Java.

### 3. The "Timeout Streak" Termination Logic
Because UDP does not guarantee delivery, we cannot rely on a `while (receivedCount < expected)` loop to terminate naturally. If even one packet drops over the network, the loop would block forever waiting for a datagram that will never arrive.

To fix this, we configure the socket with `socket.setSoTimeout(5000)`. If no packet arrives within 5 seconds, it throws a `SocketTimeoutException`. We catch this, increment a counter, and resume listening. If this happens 5 times in a row (25 seconds of total silence), we assume the broadcast is completely finished (or dead), log `FAIL timeout_limit reached`, and cleanly terminate the thread.

### 4. Artificially Corrupting Data (Validating the SHA-1)
To prove that the receiver's SHA-1 validation works, the sender intentionally corrupts specific messages. Right before sending, the code checks `if (i % 5 == 4)` (every 5th message). 

If true, it takes the byte array—**after** the correct SHA-1 has already been calculated and appended to the end—and flips a single bit in the payload (`msg[pos] ^= 0x01;`). When the receiver gets this packet, it extracts the original SHA-1, but when it computes a new SHA-1 over the corrupted payload, the hashes mismatch, triggering a successful `FAIL` log.

### 5. Config Reading

The configurations are read at the beginning of the Main class. The heavy lifting is done by the helper method ``` parseConfig()```, which turns the configurations in the `config.txt` into a list of Records called `Configs`, which contain `Endpoint` (node) objects.

## How it works

### Config file (`config.txt`)

Format:

- **Line 1**: `N` = number of messages each node generates.
- **Lines 2..end**: one node per line:

```txt
<ip> <port> <nodeId>
```

Example:

```txt
100
127.0.0.1 5000 4
127.0.0.1 5001 5
127.0.0.1 5002 6
127.0.0.1 5003 7
127.0.0.1 5004 8
```

Notes:
- `nodeId` is explicit and can start at any number (e.g. 4..8, 10..20, etc.).
- Blank lines are ignored.
- `#` comments are supported (including inline comments).

### UDP packet format (1024 bytes)

Each UDP packet is exactly **1024 bytes**:

- `msg[0]` : sender id (1 byte)
- `msg[1..1003]` : random payload (1003 bytes)
- `msg[1004..1023]` : SHA-1 digest (20 bytes) over bytes `0..1003`

Receiver logic:
1. Extract `sentSha` from bytes `1004..1023`.
2. Compute `calcSha = SHA1(msg[0..1003])`.
3. Log:
   - `OK <src> <sentShaHex> <calcShaHex>` if equal
   - `FAIL <src> <sentShaHex> <calcShaHex>` if different

### Threads

Each node runs two threads:
- **Receiver thread**: blocks on `DatagramSocket.receive()` and validates packets.
- **Sender thread**: waits `STARTUP_WAIT_MS`, then generates `N` messages and sends each message to all endpoints from the config.

To reduce UDP burst loss, the sender can pace sending (e.g. `Thread.sleep(1)` between sends).

## Logging (Logback)

Logback writes logs into `<projectroot>/runlogs`.

We pass a node-specific JVM property from scripts:

- `-DLOG_NODE_INDEX=<nodeId>`

And `logback.xml` uses `${LOG_NODE_INDEX}` in filenames, so each node writes to its own files:

- `runlogs/logs_<id>.txt` (INFO lines: OK / FAIL)
- `runlogs/error_<id>.txt` (ERROR lines)

If you see filenames like `logs_LOG_NODE_INDEX_IS_NOT_DEFINED.txt`, it means the process did not receive the `-DLOG_NODE_INDEX` property.

## Build

From project root:

```bash
mvn clean install
```

Jar output:

- `target/bcastnode-1.0-SNAPSHOT.jar`

## Run

### Linux/macOS/Git Bash (`startup.sh`)

Run a range of node ids (example: 4..8):

```bash
./startup.sh config.txt 4 8
```

Run all ids from config:

```bash
./startup.sh config.txt all
```

What it does:
- Reads ids from the **3rd column** of `config.txt` (skips the first line).
- Starts one JVM per id.
- Passes program args: `config.txt <id>`
- Passes VM property: `-DLOG_NODE_INDEX=<id>` (for per-node log files)

### Windows (`startup.bat`)

Run a range of node ids:

```powershell
.\startup.bat config.txt 4 8
```

Run all ids from config:

```powershell
.\startup.bat config.txt all
```

What it does:
- Same behavior as `startup.sh` (reads ids from the 3rd column).
- Starts one JVM per id.
- Passes program args: `config.txt <id>`
- Passes `-DLOG_NODE_INDEX=<id>` so logback writes per-node files.

### Run a single node manually

Example for node id `4`:

```bash
java -DLOG_NODE_INDEX=4 -jar target/bcastnode-1.0-SNAPSHOT.jar config.txt 4
```

## Troubleshooting

### `NodeIndex=<id> not present in config`
You started a node id that does not exist in the config’s 3rd column.  
Fix: run with an id that exists (or update `config.txt`).

### Receiver timeouts / incomplete receives
UDP is best-effort. If messages are sent too fast, packets may be dropped due to buffering limits.
Mitigations:
- Add sender pacing (small sleep between sends).
- Increase socket receive buffer (optional).
- Terminate receiver after N consecutive timeouts if you don’t require “receive everything”.

### All nodes write into the same `*_IS_NOT_DEFINED` files
`-DLOG_NODE_INDEX` wasn’t set for those processes or `logback.xml` doesn’t use `${LOG_NODE_INDEX}`.
Make sure you start nodes via the provided scripts (or pass `-DLOG_NODE_INDEX=<id>` manually).
****
