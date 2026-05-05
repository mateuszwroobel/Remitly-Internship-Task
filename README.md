# Stock Exchange Simulator

A project simulating the operation of a distributed stock exchange. The system manages user portfolios, a bank inventory, and logs buy/sell transactions.

A detailed description of architectural decisions can be found in a separate file: [ARCHITECTURE.md](ARCHITECTURE.md).

## System Architecture and Operation

The system consists of three nodes (backends) and a Load Balancer, all written in pure Java.  
HTTP traffic is handled by the built-in server `com.sun.net.httpserver.HttpServer`.

### 1. Load Balancer (L7)

Handles incoming REST requests from users and distributes them across available nodes.  
To minimize concurrency issues, it uses a *modulo-based sticky routing* strategy — requests related to a given stock (e.g., AAPL) are always routed to the same node under stable network conditions.  

The Load Balancer includes active health checks and dynamically restores recovered nodes back into the pool.

### 2. P2P Replication

When a node successfully processes a transaction, it asynchronously broadcasts it to the remaining nodes (*Fire-and-Forget pattern*).  

Receiving nodes enforce updates to their local state without re-validating business rules.

### 3. State Management

State is stored entirely in memory (*In-Memory*).  
Access to resources is protected using a *lock-striping* mechanism (separate `ReentrantLock` per stock).  

This enables full concurrency for operations as long as they do not concern the same asset.  

Idempotency (protection against duplicate request processing) is ensured by a non-blocking LRU cache implemented using `ConcurrentHashMap` combined with a queue.

## Advantages

- **Performance** – Server, HTTP client, and health checks run on lightweight Virtual Threads.
- **High Availability** – The system handles sudden node failures (simulated via `/chaos`). The Load Balancer skips failed instances without breaking client connections.
- **Automatic Bootstrapping** – After restart or failure, a node asynchronously fetches the full in-memory snapshot from peers before rejoining the Load Balancer pool. This prevents permanent data corruption after memory loss.
- **High Concurrency** – Lock-striping eliminates global synchronization bottlenecks and prevents the entire market from blocking under heavy load.

## Limitations

- **No Strong Consistency (Write Skew)** – During node failures, replication delays, or traffic rebalancing, inconsistencies between nodes may occur. The system does not implement a distributed consensus algorithm (e.g., Raft).
- **In-Memory State Only** – The entire exchange state is volatile. A full cluster restart results in irreversible data loss.

## How to Run

The project is fully containerized and uses Docker Compose.

### Linux / macOS (ARM64 & x64)
```bash
./start.sh 8080
```
**Windows (x64)**
```cmd
start.bat 8080
```
