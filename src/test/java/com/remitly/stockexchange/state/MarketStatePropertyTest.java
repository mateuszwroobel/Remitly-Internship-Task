package com.remitly.stockexchange.state;

import com.remitly.stockexchange.model.Stock;
import net.jqwik.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property-based testing for MarketState.
 * Demonstrates advanced testing methodology.
 */
class MarketStatePropertyTest {

    @Property
    void totalStockQuantityMustRemainConstantUnderValidOperations(
            @ForAll("validOperations") List<String> operations
    ) {
        // given
        MarketState state = new MarketState();
        int initialBankAAPL = 1000;
        state.setBankState(List.of(new Stock("AAPL", initialBankAAPL)));
        String walletId = "wallet-1";

        // when
        for (String op : operations) {
            if (op.equals("BUY")) {
                state.buyStock(walletId, "AAPL");
            } else if (op.equals("SELL")) {
                state.sellStock(walletId, "AAPL");
            }
        }

        // then: Invariants
        // 1. Total stock across bank and wallet must ALWAYS equal initial bank stock
        int bankQuantity = state.getBankState().stream()
                .filter(s -> s.name().equals("AAPL"))
                .mapToInt(Stock::quantity)
                .findFirst().orElse(0);
        
        int walletQuantity = state.getWalletStockQuantity(walletId, "AAPL");

        assertEquals(initialBankAAPL, bankQuantity + walletQuantity, 
                "Total shares of AAPL in system must be conserved");
                
        // 2. Quantities cannot be negative
        assertTrue(bankQuantity >= 0, "Bank stock cannot be negative");
        assertTrue(walletQuantity >= 0, "Wallet stock cannot be negative");
    }

    @Provide
    Arbitrary<List<String>> validOperations() {
        return Arbitraries.of("BUY", "SELL").list().ofMinSize(10).ofMaxSize(100);
    }
    
    private void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
