package com.remitly.stockexchange.model;

/**
 * Class representing a single stock entry.
 * How data is structured:
 * name = unique name/symbol of the stock
 * quantity = amount of this stock available or owned
 * Note: Used both for representing bank's inventory and user's wallet contents.
 */
public record Stock(String name, int quantity) {}
