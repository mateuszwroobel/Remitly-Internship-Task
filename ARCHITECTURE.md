# System Architecture and Design Decisions

This document summarizes the key architectural decisions. The goal was to create a solution that is simple, yet resilient and efficient.

### 1. Preventing "Write Skew" (L7 Sharding)
Instead of complex consensus algorithms (e.g., Raft) or distributed locks, I applied a pragmatic approach at Layer 7 (Load Balancer). 
The application uses **Modulo-based sticky routing**: requests regarding a specific asset (e.g., `AAPL`) are hashed and always directed to the same node. This guarantees that in a stable cluster, operations for a given asset are processed sequentially on a single machine, drastically reducing the risk of state overwriting (Write Skew).

### 2. Lock-striping instead of Global Locks
The state of the exchange and portfolios is stored In-Memory. Instead of using the `synchronized` keyword or a single global lock on the entire `MarketState` class (which would kill performance under high traffic), I used the **Lock-striping** technique. 
Each stock has its own `ReentrantLock` instance. Thanks to this, transactions involving different stocks are executed 100% concurrently.

### 3. Asynchronous P2P Replication
To maintain a High Availability (HA) setup, each node broadcasts state mutations to other peers in the cluster after a local mutation, using the **Fire-and-Forget** pattern. This happens completely asynchronously to avoid blocking the client with slow cross-node network communication. 
If a peer suddenly disappears (failure), the others do not hang, and the Load Balancer routes traffic exclusively to healthy nodes.

### 4. Virtual Threads
The entire HTTP server, request broadcasting via `HttpClient`, and the background node health checks are based on lightweight Virtual Threads introduced in Java 21. They eliminate the need for thread pool configuration and perform excellently in I/O operations.

### 5. Idempotency without memory leaks
To protect against transactions repeated over the network, I used the `X-Request-ID` header. Memory of processed requests is based on a highly concurrent **LRU Cache** (built on `ConcurrentHashMap.newKeySet()` and a queue). This solution is thread-safe and does not block the set during reads, while protecting the server from "Out of Memory" errors by automatically dropping the oldest entries after exceeding a limit of 10,000 requests.
