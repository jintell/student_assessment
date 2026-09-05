package org.meldtech.platform.shared.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the reviewed ADR-023 flow used by a synchronous cross-module write handler. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SynchronousAtomicFlow {

    AtomicCrossModuleFlow value();
}
