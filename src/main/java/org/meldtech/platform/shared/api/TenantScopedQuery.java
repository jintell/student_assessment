package org.meldtech.platform.shared.api;

/**
 * Marks a query port that can access tenant-scoped data.
 *
 * <p>Every declared query method must take the tenant identifier as a parameter. During the
 * foundation phase that identifier is {@code RequestTenantId}; FEAT-PLAT-003 replaces it with the
 * definitive {@code TenantId} without changing this contract.
 */
public interface TenantScopedQuery {}
