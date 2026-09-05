package org.meldtech.platform.shared.api;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks a published synchronous module boundary.
 *
 * <p>Every method declared by an extending interface must return a reactive type and carry {@link
 * Transactional} with {@link Propagation#MANDATORY}. Query methods must additionally set {@link
 * Transactional#readOnly()} to {@code true}. The initiating slice owns the transaction; a module
 * API never opens, suspends, commits, or rolls back one.
 */
public interface ModuleApi {}
