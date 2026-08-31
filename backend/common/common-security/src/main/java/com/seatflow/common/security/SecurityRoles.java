package com.seatflow.common.security;

public final class SecurityRoles {
    public static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";
    public static final String ROLE_STAFF = "ROLE_STAFF";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String SCOPE_METRICS_READ = "SCOPE_metrics.read";

    public static final String CUSTOMER = "CUSTOMER";
    public static final String STAFF = "STAFF";
    public static final String ADMIN = "ADMIN";

    private SecurityRoles() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
