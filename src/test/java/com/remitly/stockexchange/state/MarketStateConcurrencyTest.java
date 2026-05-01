package com.remitly.stockexchange.state;

import com.remitly.stockexchange.model.Stock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Concurrency tests to prove the ReentrantLock guarantees thread safety.
 */
class MarketStateConcurrencyTest {

    @Test
    void shouldMaintainConsistencyUnderHighConcurrency() throws InterruptedException {
        // given
        MarketState state = new MarketState();
        int initialStock = 5000;
        state.setBankState(List.of(new Stock("AAPL", initialStock)));
        
        int threadCount = 100;
        int operationsPerThread = 50; // Each thread does 50 buys
        
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        
        AtomicInteger successfulBuys = new AtomicInteger(0);

        // when
        for (int i = 0; i < threadCount; i++) {
            final String walletId = "wallet-" + (i % 10); // 10 distinct wallets
            
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    for (int j = 0; j < operationsPerThread; j++) {
                        if (state.buyStock(walletId, "AAPL")) {
                            successfulBuys.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        // Unleash the chaos
        startLatch.countDown();
        
        // Wait for finish
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // then
        // We attempted 5000 buys (100 * 50) and bank had 5000 exactly. All should succeed.
        assertEquals(initialStock, successfulBuys.get());
        
        // Bank should be exactly empty
        int bankRemaining = state.getBankState().stream()
                .filter(s -> s.name().equals("AAPL"))
                .mapToInt(Stock::quantity)
                .findFirst().orElse(0);
        assertEquals(0, bankRemaining);
        
        // Audit log should perfectly match successful operations
        assertEquals(initialStock, state.getAuditLog().size());
    }
}
