package com.example.flaskopenaiapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "OPENAI_API_KEY=test-key-placeholder"
})
class FlaskOpenaiApiApplicationTests {

    @Test
    void contextLoads() {
        // Verify Spring Application Context loads successfully
    }
}
