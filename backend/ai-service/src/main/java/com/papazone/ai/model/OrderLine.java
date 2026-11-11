package com.papazone.ai.model;

/** One line item inside an order. menuItemId may be null for legacy rows. */
public record OrderLine(Long menuItemId, String name, double unitPrice, int quantity) {
}
