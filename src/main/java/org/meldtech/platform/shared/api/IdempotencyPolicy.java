package org.meldtech.platform.shared.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotencyPolicy {

    boolean createsDurableRecord();

    IdempotencyMechanism mechanism();

    String databaseProtection() default "";
}
