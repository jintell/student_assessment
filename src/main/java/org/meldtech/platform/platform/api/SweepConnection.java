package org.meldtech.platform.platform.api;

import io.r2dbc.spi.Statement;

/** Statement-only handle valid for the lifetime of one security-initialized sweep transaction. */
@FunctionalInterface
public interface SweepConnection {

    Statement createStatement(String sql);
}
