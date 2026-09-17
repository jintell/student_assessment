package org.meldtech.migrationverify.port;

import java.nio.file.Path;
import org.meldtech.migrationverify.core.VolumetricProfile;

@FunctionalInterface
public interface VolumetricProfileLoader {

    VolumetricProfile load(Path profilePath);
}
