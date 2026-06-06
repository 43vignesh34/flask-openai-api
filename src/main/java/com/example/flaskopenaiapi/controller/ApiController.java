package com.example.flaskopenaiapi.controller;

import com.example.flaskopenaiapi.exception.ApiStatusException;
import com.example.flaskopenaiapi.exception.AuthenticationException;
import com.example.flaskopenaiapi.exception.RateLimitException;
import com.example.flaskopenaiapi.model.Message;
import com.example.flaskopenaiapi.model.OpenApiResponse;
import com.example.flaskopenaiapi.service.ConversationService;
import com.example.flaskopenaiapi.service.DataIngestionService;
import com.example.flaskopenaiapi.service.VectorStoreService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ApiController {

    private final ConversationService conversationService;
    private final DataIngestionService dataIngestionService;
    private final VectorStoreService vectorStoreService;

    public ApiController(ConversationService conversationService,
                         DataIngestionService dataIngestionService,
                         VectorStoreService vectorStoreService) {
        this.conversationService = conversationService;
        this.dataIngestionService = dataIngestionService;
        this.vectorStoreService = vectorStoreService;
    }


    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(@RequestBody Map<String, String> request) {
        String sessionId = request.get("session_id");
        String input = request.get("input");

        if (sessionId == null || input == null) {
            Map<String, Object> errorBody = new HashMap<>();
            errorBody.put("error", "Missing 'session_id' or 'input' fields in request body");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody);
        }

        OpenApiResponse response = conversationService.ask(sessionId, input);
        List<Message> history = conversationService.getHistory(sessionId);

        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("role", "assistant");
        responseBody.put("response", response.getOutputText());
        responseBody.put("history", history);

        return ResponseEntity.ok(responseBody);
    }

    @PostMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody Map<String, String> request) {
        SseEmitter emitter = new SseEmitter(180000L); // 3 minutes timeout
        String sessionId = request.get("session_id");
        String input = request.get("input");

        if (sessionId == null || input == null) {
            emitter.completeWithError(new IllegalArgumentException("Missing session_id or input"));
            return emitter;
        }

        conversationService.askStream(sessionId, input, emitter);
        return emitter;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest() {
        dataIngestionService.ingestAll();
        vectorStoreService.rebuildIndex();

        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("status", "success");
        responseBody.put("message", "Data ingested and vector index rebuilt successfully.");
        return ResponseEntity.ok(responseBody);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException e) {
        System.err.println("AUTHENTICATION ERROR: " + e.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitException e) {
        System.err.println("RATE LIMIT / QUOTA ERROR: " + e.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    @ExceptionHandler(ApiStatusException.class)
    public ResponseEntity<Map<String, Object>> handleApiStatus(ApiStatusException e) {
        System.err.println("OPENAI API STATUS ERROR");
        System.err.println("Status Code: " + e.getStatusCode());
        System.err.println("Response: " + e.getResponseBody());
        
        Map<String, Object> body = new HashMap<>();
        body.put("status_code", e.getStatusCode());
        body.put("error", e.getResponseBody());
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
        System.err.println("GENERAL ERROR: " + e.getClass().getName() + " " + e.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
