package org.meldtech.platform.platform.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/** Declares the runtime roles allowed to instantiate a component or factory-created bean. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(RoleScopedCondition.class)
public @interface RoleScoped {
    /** One or more distinct profile names from {@link RuntimeRole}. */
    String[] value();
}
