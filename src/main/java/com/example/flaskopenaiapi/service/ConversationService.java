package com.example.flaskopenaiapi.service;

import com.example.flaskopenaiapi.exception.ApiStatusException;
import com.example.flaskopenaiapi.exception.AuthenticationException;
import com.example.flaskopenaiapi.exception.RateLimitException;
import com.example.flaskopenaiapi.model.Message;
import com.example.flaskopenaiapi.model.OpenApiRequest;
import com.example.flaskopenaiapi.model.OpenApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, List<Message>> conversationStore = new ConcurrentHashMap<>();

    public ConversationService(@Value("${OPENAI_API_KEY:}") String apiKey) {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public List<Message> getHistory(String sessionId) {
        return conversationStore.getOrDefault(sessionId, Collections.emptyList());
    }

    public OpenApiResponse ask(String sessionId, String userInput) {
        // 1. Get or create history for session
        List<Message> history = conversationStore.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()));

        // 2. Append user input
        history.add(new Message("user", userInput));

        try {
            // 3. Prepare payload (copy history to avoid concurrent modification issues during serialization)
            List<Message> messagesCopy;
            synchronized (history) {
                messagesCopy = new ArrayList<>(history);
            }
            OpenApiRequest requestPayload = new OpenApiRequest("gpt-4o-mini", messagesCopy);

            // 4. Send request to /responses
            OpenApiResponse response = restClient.post()
                    .uri("/responses")
                    .body(requestPayload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        int code = resp.getStatusCode().value();
                        String body = "";
                        try (InputStream is = resp.getBody()) {
                            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        } catch (Exception ignored) {}
                        
                        if (code == 401) {
                            throw new AuthenticationException("OpenAI API Authentication failed.");
                        } else if (code == 429) {
                            throw new RateLimitException("OpenAI API Rate limit / Quota exceeded.");
                        } else {
                            throw new ApiStatusException(code, body);
                        }
                    })
                    .body(OpenApiResponse.class);

            if (response != null && response.getOutputText() != null) {
                // 5. Append assistant reply to history
                history.add(new Message("assistant", response.getOutputText()));
            }

            return response;

        } catch (AuthenticationException | RateLimitException | ApiStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void askStream(String sessionId, String userInput, SseEmitter emitter) {
        // 1. Get or create history
        List<Message> history = conversationStore.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        history.add(new Message("user", userInput));

        // 2. Copy history
        List<Message> messagesCopy;
        synchronized (history) {
            messagesCopy = new ArrayList<>(history);
        }

        // 3. Prepare payload with stream=true
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("model", "gpt-4o-mini");
        requestPayload.put("input", messagesCopy);
        requestPayload.put("stream", true);

        // 4. Asynchronously call OpenAI API and stream response
        CompletableFuture.runAsync(() -> {
            StringBuilder fullText = new StringBuilder();
            try {
                restClient.post()
                        .uri("/responses")
                        .body(requestPayload)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange((req, resp) -> {
                            int code = resp.getStatusCode().value();
                            if (resp.getStatusCode().isError()) {
                                String errorBody = "";
                                try (InputStream is = resp.getBody()) {
                                    errorBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                                } catch (Exception ignored) {}
                                throw new ApiStatusException(code, errorBody);
                            }

                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.getBody(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.startsWith("data: ")) {
                                        String dataStr = line.substring(6).trim();
                                        if (!dataStr.isEmpty()) {
                                            String delta = extractDelta(dataStr);
                                            if (delta != null && !delta.isEmpty()) {
                                                fullText.append(delta);
                                                emitter.send(SseEmitter.event()
                                                        .name("delta")
                                                        .data(delta));
                                            }
                                        }
                                    }
                                }
                            }
                            return null;
                        });

                // 5. Append accumulated assistant response to history
                if (fullText.length() > 0) {
                    history.add(new Message("assistant", fullText.toString()));
                }

                // 6. Signal done and complete
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();

            } catch (ApiStatusException e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getResponseBody()));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });
    }

    private String extractDelta(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.has("delta")) {
                return node.get("delta").asText();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
