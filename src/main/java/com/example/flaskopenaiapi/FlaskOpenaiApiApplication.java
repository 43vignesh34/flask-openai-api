package com.example.flaskopenaiapi;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FlaskOpenaiApiApplication {

    public static void main(String[] args) {
        // Load variables from .env file into System Properties
        try {
            Dotenv dotenv = Dotenv.configure()
                    .ignoreIfMalformed()
                    .ignoreIfMissing()
                    .load();
            
            dotenv.entries().forEach(entry -> {
                System.setProperty(entry.getKey(), entry.getValue());
            });
        } catch (Exception e) {
            System.err.println("Could not load .env file: " + e.getMessage());
        }

        SpringApplication.run(FlaskOpenaiApiApplication.class, args);
    }
}
