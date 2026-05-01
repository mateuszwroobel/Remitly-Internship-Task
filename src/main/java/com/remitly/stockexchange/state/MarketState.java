package com.remitly.stockexchange.state;

import com.remitly.stockexchange.model.AuditEntry;
import com.remitly.stockexchange.model.Stock;
import com.remitly.stockexchange.model.Wallet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Class responsible for maintaining the mutable state of the market.
 * How the state is managed:
 * bankStocks = tracks quantity of stocks available in the bank
 * wallets = tracks stocks owned by each user (walletId -> (stockName -> quantity))
 * auditLog = chronological list of all buy/sell transactions
 * Then concurrency is handled using a ReentrantLock to ensure thread-safe operations.
 * State is modified when: available stock > 0 and stock is known in the system.
 * Note: Includes idempotency protection to prevent duplicate processing of replicated requests.
 */
public class MarketState {
    private final Map<String, Integer> bankStocks = new java.util.concurrent.ConcurrentHashMap<>();
    // walletId -> (stockName -> quantity)
    private final Map<String, Map<String, Integer>> wallets = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<AuditEntry> auditLog = new java.util.concurrent.CopyOnWriteArrayList<>();
    
    // Set of all stocks that have ever been introduced by the bank
    private final Set<String> knownStocks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    
    // Lock striping by stock name
    private final Map<String, ReentrantLock> stockLocks = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Idempotency guard for max 10k operations with lock-free eviction
    private final Set<String> processedRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Queue<String> requestOrder = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger cacheSize = new java.util.concurrent.atomic.AtomicInteger(0);

    private volatile boolean ready = false;

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public Map<String, Object> dumpState() {
        return Map.of(
            "bankStocks", new HashMap<>(bankStocks),
            "wallets", new HashMap<>(wallets),
            "auditLog", new ArrayList<>(auditLog),
            "processedRequests", new ArrayList<>(processedRequests),
            "requestOrder", new ArrayList<>(requestOrder),
            "knownStocks", new ArrayList<>(knownStocks)
        );
    }

    @SuppressWarnings("unchecked")
    public void restoreFromDump(Map<String, Object> dump) {
        if (dump == null || dump.isEmpty()) return;
        
        bankStocks.clear();
        if (dump.containsKey("bankStocks")) {
            bankStocks.putAll((Map<String, Integer>) dump.get("bankStocks"));
        }
        
        wallets.clear();
        if (dump.containsKey("wallets")) {
            Map<String, Map<String, Integer>> w = (Map<String, Map<String, Integer>>) dump.get("wallets");
            w.forEach((k, v) -> wallets.put(k, new java.util.concurrent.ConcurrentHashMap<>(v)));
        }
        
        auditLog.clear();
        if (dump.containsKey("auditLog")) {
            List<Map<String, String>> logs = (List<Map<String, String>>) dump.get("auditLog");
            for (Map<String, String> entry : logs) {
                auditLog.add(new AuditEntry(entry.get("action"), entry.get("walletId"), entry.get("stockName")));
            }
        }
        
        processedRequests.clear();
        requestOrder.clear();
        cacheSize.set(0);
        if (dump.containsKey("requestOrder")) {
            List<String> order = (List<String>) dump.get("requestOrder");
            requestOrder.addAll(order);
        }
        if (dump.containsKey("processedRequests")) {
            List<String> reqs = (List<String>) dump.get("processedRequests");
            processedRequests.addAll(reqs);
            cacheSize.set(processedRequests.size());
        }
        
        knownStocks.clear();
        if (dump.containsKey("knownStocks")) {
            List<String> stocks = (List<String>) dump.get("knownStocks");
            knownStocks.addAll(stocks);
        }
    }
    private void recordRequest(String requestId) {
        if (requestId != null && processedRequests.add(requestId)) {
            requestOrder.offer(requestId);
            int currentSize = cacheSize.incrementAndGet();
            while (currentSize > 10000) {
                String oldest = requestOrder.poll();
                if (oldest != null) {
                    processedRequests.remove(oldest);
                    currentSize = cacheSize.decrementAndGet();
                } else {
                    break;
                }
            }
        }
    }
    private ReentrantLock getLockForStock(String stockName) {
        return stockLocks.computeIfAbsent(stockName, k -> new ReentrantLock());
    }

    public void setBankState(List<Stock> newStocks) {
        for (Stock s : newStocks) {
            ReentrantLock stockLock = getLockForStock(s.name());
            stockLock.lock();
            try {
                int current = bankStocks.getOrDefault(s.name(), 0);
                bankStocks.put(s.name(), current + s.quantity());
                knownStocks.add(s.name());
            } finally {
                stockLock.unlock();
            }
        }
    }

    public List<Stock> getBankState() {
        return bankStocks.entrySet().stream()
                .map(e -> new Stock(e.getKey(), e.getValue()))
                .toList();
    }

    public Wallet getWallet(String walletId) {
        Map<String, Integer> userStocks = wallets.getOrDefault(walletId, Map.of());
        List<Stock> stocks = userStocks.entrySet().stream()
                .map(e -> new Stock(e.getKey(), e.getValue()))
                .toList();
        return new Wallet(walletId, stocks);
    }

    public Integer getWalletStockQuantity(String walletId, String stockName) {
        return wallets.getOrDefault(walletId, Map.of()).getOrDefault(stockName, 0);
    }

    public List<AuditEntry> getAuditLog() {
        return List.copyOf(auditLog);
    }

    public boolean buyStock(String walletId, String stockName) {
        return buyStock(walletId, stockName, null);
    }

    /**
     * Executes a buy operation with idempotency protection.
     * 
     * @return true if successful or already processed, false if not enough stock in bank
     * @throws IllegalArgumentException if stock is completely unknown
     */
    public boolean buyStock(String walletId, String stockName, String requestId) {
        if (requestId != null && processedRequests.contains(requestId)) return true;

        if (!knownStocks.contains(stockName)) {
            throw new IllegalArgumentException("Unknown stock: " + stockName);
        }

        ReentrantLock stockLock = getLockForStock(stockName);
        stockLock.lock();
        try {
            if (requestId != null && processedRequests.contains(requestId)) return true;

            int availableInBank = bankStocks.getOrDefault(stockName, 0);
            if (availableInBank <= 0) {
                return false; // Bad Request (400) - No stock in bank
            }

            // Deduct from bank
            bankStocks.put(stockName, availableInBank - 1);

            // Add to wallet
            wallets.putIfAbsent(walletId, new java.util.concurrent.ConcurrentHashMap<>());
            Map<String, Integer> userStocks = wallets.get(walletId);
            userStocks.put(stockName, userStocks.getOrDefault(stockName, 0) + 1);

            // Audit
            auditLog.add(new AuditEntry("buy", walletId, stockName));
            recordRequest(requestId);
            return true;
        } finally {
            stockLock.unlock();
        }
    }

    public boolean sellStock(String walletId, String stockName) {
        return sellStock(walletId, stockName, null);
    }

    /**
     * Executes a sell operation with idempotency protection.
     * 
     * @return true if successful or already processed, false if wallet doesn't have the stock
     * @throws IllegalArgumentException if stock is completely unknown
     */
    public boolean sellStock(String walletId, String stockName, String requestId) {
        if (requestId != null && processedRequests.contains(requestId)) return true;

        if (!knownStocks.contains(stockName)) {
            throw new IllegalArgumentException("Unknown stock: " + stockName);
        }

        ReentrantLock stockLock = getLockForStock(stockName);
        stockLock.lock();
        try {
            if (requestId != null && processedRequests.contains(requestId)) return true;

            wallets.putIfAbsent(walletId, new java.util.concurrent.ConcurrentHashMap<>());
            Map<String, Integer> userStocks = wallets.get(walletId);
            
            int userQuantity = userStocks.getOrDefault(stockName, 0);
            if (userQuantity <= 0) {
                return false; // Bad Request (400) - No stock in wallet
            }

            // Deduct from wallet
            userStocks.put(stockName, userQuantity - 1);

            // Add to bank
            bankStocks.put(stockName, bankStocks.getOrDefault(stockName, 0) + 1);

            // Audit
            auditLog.add(new AuditEntry("sell", walletId, stockName));
            recordRequest(requestId);
            return true;
        } finally {
            stockLock.unlock();
        }
    }

    /**
     * Applies a buy operation from replication safely.
     */
    public void forceBuyStock(String walletId, String stockName, String requestId) {
        if (requestId != null && processedRequests.contains(requestId)) return;
        
        ReentrantLock stockLock = getLockForStock(stockName);
        stockLock.lock();
        try {
            if (requestId != null && processedRequests.contains(requestId)) return;
            
            int availableInBank = bankStocks.getOrDefault(stockName, 0);
            if (availableInBank <= 0) return; // Prevent negative state

            bankStocks.put(stockName, availableInBank - 1);
            wallets.putIfAbsent(walletId, new java.util.concurrent.ConcurrentHashMap<>());
            Map<String, Integer> userStocks = wallets.get(walletId);
            userStocks.put(stockName, userStocks.getOrDefault(stockName, 0) + 1);
            auditLog.add(new AuditEntry("buy", walletId, stockName));
            recordRequest(requestId);
        } finally {
            stockLock.unlock();
        }
    }

    /**
     * Applies a sell operation from replication safely.
     */
    public void forceSellStock(String walletId, String stockName, String requestId) {
        if (requestId != null && processedRequests.contains(requestId)) return;
        
        ReentrantLock stockLock = getLockForStock(stockName);
        stockLock.lock();
        try {
            if (requestId != null && processedRequests.contains(requestId)) return;
            
            wallets.putIfAbsent(walletId, new java.util.concurrent.ConcurrentHashMap<>());
            Map<String, Integer> userStocks = wallets.get(walletId);
            
            int userQuantity = userStocks.getOrDefault(stockName, 0);
            if (userQuantity <= 0) return; // Prevent negative state

            userStocks.put(stockName, userQuantity - 1);
            bankStocks.put(stockName, bankStocks.getOrDefault(stockName, 0) + 1);
            auditLog.add(new AuditEntry("sell", walletId, stockName));
            recordRequest(requestId);
        } finally {
            stockLock.unlock();
        }
    }
}
