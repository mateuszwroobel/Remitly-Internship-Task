package com.remitly.stockexchange.state;

import com.remitly.stockexchange.model.Stock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketStateTest {

    private MarketState state;

    @BeforeEach
    void setUp() {
        state = new MarketState();
    }

    @Test
    void shouldProperlyInitializeBankState() {
        // given
        List<Stock> newStocks = List.of(
                new Stock("AAPL", 100),
                new Stock("GOOG", 50)
        );

        // when
        state.setBankState(newStocks);

        // then
        List<Stock> bankState = state.getBankState();
        assertEquals(2, bankState.size());
        assertTrue(bankState.contains(new Stock("AAPL", 100)));
        assertTrue(bankState.contains(new Stock("GOOG", 50)));
    }

    @Test
    void shouldSuccessfullyBuyStockIfAvailable() {
        // given
        state.setBankState(List.of(new Stock("AAPL", 10)));
        String walletId = "wallet-1";

        // when
        boolean success = state.buyStock(walletId, "AAPL");

        // then
        assertTrue(success);
        
        // Bank should have 9 left
        assertTrue(state.getBankState().contains(new Stock("AAPL", 9)));
        
        // Wallet should have 1
        assertEquals(1, state.getWalletStockQuantity(walletId, "AAPL"));
        assertEquals(1, state.getWallet(walletId).stocks().size());
        
        // Audit log should have exactly one entry
        assertEquals(1, state.getAuditLog().size());
        assertEquals("buy", state.getAuditLog().get(0).type());
        assertEquals(walletId, state.getAuditLog().get(0).wallet_id());
        assertEquals("AAPL", state.getAuditLog().get(0).stock_name());
    }

    @Test
    void shouldFailToBuyIfBankEmpty() {
        // given
        state.setBankState(List.of(new Stock("AAPL", 0)));
        
        // when
        boolean success = state.buyStock("wallet-1", "AAPL");

        // then
        assertFalse(success);
        assertEquals(0, state.getWalletStockQuantity("wallet-1", "AAPL"));
        assertEquals(0, state.getAuditLog().size()); // Failed ops not logged
    }

    @Test
    void shouldSuccessfullySellStockIfOwned() {
        // given
        state.setBankState(List.of(new Stock("AAPL", 10)));
        state.buyStock("wallet-1", "AAPL");
        assertEquals(1, state.getWalletStockQuantity("wallet-1", "AAPL"));

        // when
        boolean success = state.sellStock("wallet-1", "AAPL");

        // then
        assertTrue(success);
        
        // Wallet should have 0
        assertEquals(0, state.getWalletStockQuantity("wallet-1", "AAPL"));
        
        // Bank should have 10 again (9 + 1)
        assertTrue(state.getBankState().contains(new Stock("AAPL", 10)));
        
        // Audit log should have 2 entries (buy, then sell)
        assertEquals(2, state.getAuditLog().size());
        assertEquals("sell", state.getAuditLog().get(1).type());
    }

    @Test
    void shouldFailToSellIfNotOwned() {
        // given
        state.setBankState(List.of(new Stock("AAPL", 10)));
        
        // when
        boolean success = state.sellStock("wallet-1", "AAPL"); // Has none

        // then
        assertFalse(success);
        assertTrue(state.getBankState().contains(new Stock("AAPL", 10)));
        assertEquals(0, state.getAuditLog().size());
    }

    @Test
    void shouldThrowExceptionOnUnknownStock() {
        // given
        state.setBankState(List.of(new Stock("AAPL", 10)));
        
        // when & then
        assertThrows(IllegalArgumentException.class, () -> state.buyStock("w1", "UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> state.sellStock("w1", "UNKNOWN"));
    }

    @Test
    void shouldHandleIdempotencyCorrectly() {
        // given
        state.setBankState(List.of(new Stock("AAPL", 10)));
        String requestId = "req-123";

        // when - first call
        boolean firstCall = state.buyStock("w1", "AAPL", requestId);
        
        // then - should succeed and mutate
        assertTrue(firstCall);
        assertEquals(9, state.getBankState().getFirst().quantity());
        assertEquals(1, state.getAuditLog().size());

        // when - exact same call again (retry)
        boolean secondCall = state.buyStock("w1", "AAPL", requestId);

        // then - should return true (idempotent success) but NOT mutate state again
        assertTrue(secondCall);
        assertEquals(9, state.getBankState().getFirst().quantity()); // Still 9!
        assertEquals(1, state.getAuditLog().size()); // Still 1!
    }

    @Test
    void shouldAllowDifferentRequests() {
        // given
        state.setBankState(List.of(new Stock("AAPL", 10)));

        // when - two different requests
        state.buyStock("w1", "AAPL", "req-1");
        state.buyStock("w1", "AAPL", "req-2");

        // then - both applied
        assertEquals(8, state.getBankState().getFirst().quantity());
        assertEquals(2, state.getAuditLog().size());
    }
}
