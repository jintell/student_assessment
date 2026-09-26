package org.meldtech.platform.shared.api;

/**
 * Marks a query port that can access tenant-scoped data.
 *
 * <p>Every declared query method must take the tenant identifier as a parameter. During the
 * identifier is the definitive {@code TenantId} supplied by the shared kernel.
 */
public interface TenantScopedQuery {}
