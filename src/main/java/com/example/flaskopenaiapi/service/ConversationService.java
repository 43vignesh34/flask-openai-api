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
import org.springframework.beans.factory.annotation.Autowired;
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
    private String systemPrompt = "";

    @Autowired
    private LiveDataFetcherService liveDataFetcher;

    public ConversationService(@Value("${OPENAI_API_KEY:}") String apiKey) {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        // Load the system prompt from the classpath
        try (InputStream is = getClass().getResourceAsStream("/system_prompt.txt")) {
            if (is != null) {
                this.systemPrompt = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            } else {
                System.err.println("system_prompt.txt resource not found!");
            }
        } catch (Exception e) {
            System.err.println("Error reading system_prompt.txt: " + e.getMessage());
        }
    }

    public List<Message> getHistory(String sessionId) {
        return conversationStore.getOrDefault(sessionId, Collections.emptyList());
    }

    public OpenApiResponse ask(String sessionId, String userInput) {
        List<Message> history = conversationStore.computeIfAbsent(
                sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        history.add(new Message("user", userInput));

        try {
            List<Message> messagesCopy = new ArrayList<>();

            // 1. System prompt
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messagesCopy.add(new Message("system", systemPrompt));
            }

            // 2. Live data context (fetched now, discarded after use)
            String liveContext = fetchLiveContext(userInput);
            if (liveContext != null && !liveContext.isBlank()) {
                messagesCopy.add(new Message("system", liveContext));
            }

            // 3. Conversation history
            synchronized (history) {
                messagesCopy.addAll(history);
            }

            OpenApiRequest requestPayload = new OpenApiRequest("gpt-4o-mini", messagesCopy);

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

                        if (code == 401) throw new AuthenticationException("OpenAI API Authentication failed.");
                        else if (code == 429) throw new RateLimitException("OpenAI API Rate limit / Quota exceeded.");
                        else throw new ApiStatusException(code, body);
                    })
                    .body(OpenApiResponse.class);

            if (response != null && response.getOutputText() != null) {
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
        List<Message> history = conversationStore.computeIfAbsent(
                sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        history.add(new Message("user", userInput));

        // 1. System prompt
        List<Message> messagesCopy = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messagesCopy.add(new Message("system", systemPrompt));
        }

        // 2. Live data context — fetched synchronously before streaming starts
        String liveContext = fetchLiveContext(userInput);
        if (liveContext != null && !liveContext.isBlank()) {
            messagesCopy.add(new Message("system", liveContext));
        }

        // 3. Conversation history
        synchronized (history) {
            messagesCopy.addAll(history);
        }

        // 4. Stream request payload
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("model", "gpt-4o-mini");
        requestPayload.put("input", messagesCopy);
        requestPayload.put("stream", true);

        // 5. Async streaming call to OpenAI
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

                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(resp.getBody(), StandardCharsets.UTF_8))) {
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

                // Save full assistant response to history
                if (fullText.length() > 0) {
                    history.add(new Message("assistant", fullText.toString()));
                }

                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();

            } catch (ApiStatusException e) {
                try { emitter.send(SseEmitter.event().name("error").data(e.getResponseBody())); }
                catch (Exception ignored) {}
                emitter.completeWithError(e);
            } catch (Exception e) {
                try { emitter.send(SseEmitter.event().name("error").data(e.getMessage())); }
                catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // Live context helper
    // ─────────────────────────────────────────────────────────────

    private String fetchLiveContext(String userInput) {
        try {
            System.out.println("Fetching live IPL context for query: " + userInput.substring(0, Math.min(80, userInput.length())));
            String ctx = liveDataFetcher.buildLiveContext(userInput);
            if (ctx != null) {
                System.out.println("Live context fetched (" + ctx.length() + " chars).");
            } else {
                System.out.println("No live context needed (pure strategy query).");
            }
            return ctx;
        } catch (Exception e) {
            System.err.println("fetchLiveContext failed: " + e.getMessage());
            return "[LIVE RETRIEVAL FAILED]\n"
                 + "An unexpected error occurred fetching live IPL data: " + e.getMessage() + "\n"
                 + "Do NOT answer current IPL facts from model memory. Inform the user live data is unavailable.\n";
        }
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
