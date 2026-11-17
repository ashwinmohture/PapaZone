package com.papazone.order.security;

public record AuthenticatedUser(Long customerId, String role) {
    public boolean isAdmin() { return "ADMIN".equals(role); }
}
