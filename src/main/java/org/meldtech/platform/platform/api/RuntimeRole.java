package org.meldtech.platform.platform.api;

public enum RuntimeRole {
    API("api"),
    WORKER("worker"),
    PINDIST("pindist");

    private final String profile;

    RuntimeRole(String profile) {
        this.profile = profile;
    }

    public String profile() {
        return profile;
    }

    public static RuntimeRole fromProfile(String profile) {
        for (RuntimeRole role : values()) {
            if (role.profile.equals(profile)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown runtime role profile");
    }
}
