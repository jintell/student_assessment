package org.meldtech.platform.platform.api;

import java.util.EnumSet;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.Assert;

final class RoleScopedCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String[] profiles = metadata.getAnnotations().get(RoleScoped.class).getStringArray("value");
        Assert.isTrue(profiles.length > 0, "@RoleScoped requires at least one runtime role");
        var roles = EnumSet.noneOf(RuntimeRole.class);
        for (String profile : profiles) {
            Assert.isTrue(
                    roles.add(RuntimeRole.fromProfile(profile)),
                    "@RoleScoped requires distinct runtime roles");
        }
        return context.getEnvironment().matchesProfiles(profiles);
    }
}
