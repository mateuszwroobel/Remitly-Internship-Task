package com.remitly.stockexchange.model;

import java.util.List;

/**
 * Class representing a user wallet in the stock exchange.
 * How it stores data:
 * id = unique identifier of the wallet
 * stocks = list of stocks currently owned by this wallet
 * Note: Wallets are dynamically created when a user buys a stock for the first time.
 */

public record Wallet(String id, List<Stock> stocks) {}
