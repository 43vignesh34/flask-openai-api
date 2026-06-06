package com.example.flaskopenaiapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "OPENAI_API_KEY=test-key-placeholder"
})
class FlaskOpenaiApiApplicationTests {

    @Test
    void contextLoads() {
        // Verify Spring Application Context loads successfully
    }
}
