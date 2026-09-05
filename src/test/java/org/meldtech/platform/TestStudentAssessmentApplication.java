package org.meldtech.platform;

import org.springframework.boot.SpringApplication;

public class TestStudentAssessmentApplication {

    public static void main(String[] args) {
        SpringApplication.from(CbtPlatformApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
