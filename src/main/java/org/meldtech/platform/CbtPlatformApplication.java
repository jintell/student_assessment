package org.meldtech.platform;

import org.meldtech.platform.migration.MigrationApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CbtPlatformApplication {

    public static void main(String[] args) {
        if (MigrationApplication.isRequested(args)) {
            MigrationApplication.run(args);
            return;
        }
        SpringApplication.run(CbtPlatformApplication.class, args);
    }
}
