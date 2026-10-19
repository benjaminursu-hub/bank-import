package io.github.benjaminursu.bankimport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Tag("docker")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BankImportApplicationTests {

    @Test
    void contextLoads() {
    }

}
