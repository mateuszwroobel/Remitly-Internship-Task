package com.remitly.stockexchange.model;

/**
 * Class responsible for tracking operations in the system.
 * How an entry is created:
 * type = "buy" or "sell" depending on the operation
 * wallet_id = identifier of the wallet performing the operation
 * stock_name = name of the traded stock
 * Note: The audit log serves as a chronological record of all valid transactions.
 */
public record AuditEntry(String type, String wallet_id, String stock_name) {}
