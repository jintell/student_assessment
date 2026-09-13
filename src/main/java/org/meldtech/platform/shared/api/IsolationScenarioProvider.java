package org.meldtech.platform.shared.api;

import java.util.Set;

/** Supplies the complete operation catalogue consumed by tenant-isolation tests. */
public interface IsolationScenarioProvider {

    String routeId();

    Set<IsolationOperation> operations();
}
