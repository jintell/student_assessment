package org.meldtech.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"api", "local"})
@ContextConfiguration(initializers = RuntimeTestEnvironment.class)
@TestPropertySource("classpath:runtime-test.properties")
class StudentAssessmentApplicationTests {

    @Test
    void contextLoads() {}
}
