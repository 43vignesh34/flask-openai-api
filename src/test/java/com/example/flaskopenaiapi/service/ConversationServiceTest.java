package com.example.flaskopenaiapi.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.flaskopenaiapi.model.Message;
import com.example.flaskopenaiapi.model.OpenApiRequest;
import com.example.flaskopenaiapi.model.OpenApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.Collections;

@ExtendWith(MockitoExtension.class)
public class ConversationServiceTest {

    @Mock
    private LiveDataFetcherService liveDataFetcher;

    @Mock
    private RestClient restClient;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService("");
        ReflectionTestUtils.setField(conversationService, "liveDataFetcher", liveDataFetcher);
        ReflectionTestUtils.setField(conversationService, "restClient", restClient);
    }

    @Test
    void testAsk_WhenLiveRetrievalFails_ShouldInjectFailureContext() {
        // Arrange
        String failureContext = "[LIVE RETRIEVAL FAILED]\nCould not fetch live IPL data for: SQUAD at 2026-06-06 13:00 IST.\n"
                + "Do NOT answer current IPL facts from model memory.";
        when(liveDataFetcher.buildLiveContext(anyString())).thenReturn(failureContext);

        RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(OpenApiRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);

        OpenApiResponse expectedResponse = new OpenApiResponse();
        OpenApiResponse.OutputItem item = new OpenApiResponse.OutputItem();
        OpenApiResponse.ContentBlock cb = new OpenApiResponse.ContentBlock();
        cb.setType("output_text");
        cb.setText("I wasn't able to fetch the latest IPL data right now.");
        item.setContent(Collections.singletonList(cb));
        expectedResponse.setOutput(Collections.singletonList(item));
        when(responseSpec.body(OpenApiResponse.class)).thenReturn(expectedResponse);

        // Act
        OpenApiResponse actualResponse = conversationService.ask("session-1", "What is the current KKR squad?");

        // Assert
        assertNotNull(actualResponse);
        assertEquals("I wasn't able to fetch the latest IPL data right now.", actualResponse.getOutputText());

        ArgumentCaptor<OpenApiRequest> captor = ArgumentCaptor.forClass(OpenApiRequest.class);
        verify(requestBodySpec).body(captor.capture());
        OpenApiRequest request = captor.getValue();
        assertNotNull(request);

        // Verify failure context was injected into the request
        boolean failureContextFound = request.getInput().stream()
                .anyMatch(msg -> "system".equals(msg.getRole())
                        && msg.getContent().contains("[LIVE RETRIEVAL FAILED]"));
        assertTrue(failureContextFound, "Request should contain [LIVE RETRIEVAL FAILED] system message");
    }

    @Test
    void testAsk_WhenPureStrategyQuery_NoLiveContextInjected() {
        // Arrange — pure strategy query returns null from live fetcher
        when(liveDataFetcher.buildLiveContext(anyString())).thenReturn(null);

        RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(OpenApiRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);

        OpenApiResponse expectedResponse = new OpenApiResponse();
        OpenApiResponse.OutputItem item = new OpenApiResponse.OutputItem();
        OpenApiResponse.ContentBlock cb = new OpenApiResponse.ContentBlock();
        cb.setType("output_text");
        cb.setText("A good death bowler needs to be able to hit yorkers consistently.");
        item.setContent(Collections.singletonList(cb));
        expectedResponse.setOutput(Collections.singletonList(item));
        when(responseSpec.body(OpenApiResponse.class)).thenReturn(expectedResponse);

        // Act
        OpenApiResponse actualResponse = conversationService.ask("session-2", "What makes a good death bowler?");

        // Assert
        assertNotNull(actualResponse);

        ArgumentCaptor<OpenApiRequest> captor = ArgumentCaptor.forClass(OpenApiRequest.class);
        verify(requestBodySpec).body(captor.capture());
        OpenApiRequest request = captor.getValue();

        // No LIVE DATA CONTEXT injection for pure strategy query.
        // The live fetcher returned null → no extra system message should appear beyond the static system_prompt.txt.
        // We distinguish by checking for the timestamp header "fetched at" which only appears in live injections.
        boolean hasLiveContextInjection = request.getInput().stream()
                .anyMatch(msg -> "system".equals(msg.getRole())
                        && msg.getContent().contains("fetched at")
                        && msg.getContent().contains("[LIVE DATA CONTEXT"));
        assertFalse(hasLiveContextInjection, "Pure strategy query should NOT inject live fetched context system message");
    }

    @Test
    void testAsk_WhenLiveContextSucceeds_ShouldInjectDataIntoRequest() {
        // Arrange
        String liveCtx = "[LIVE DATA CONTEXT — fetched at 2026-06-06 13:00 IST]\n"
                + "=== SOURCE: Cricbuzz | IPL Points Table | FETCHED: 2026-06-06 13:00 IST ===\n"
                + "1. Royal Challengers Bengaluru | P:14 W:9 L:5 Pts:18\n"
                + "2. Gujarat Titans | P:14 W:8 L:6 Pts:16";
        when(liveDataFetcher.buildLiveContext(anyString())).thenReturn(liveCtx);

        RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec requestBodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(OpenApiRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);

        OpenApiResponse expectedResponse = new OpenApiResponse();
        OpenApiResponse.OutputItem item = new OpenApiResponse.OutputItem();
        OpenApiResponse.ContentBlock cb = new OpenApiResponse.ContentBlock();
        cb.setType("output_text");
        cb.setText("According to Cricbuzz, RCB leads the table with 18 points.");
        item.setContent(Collections.singletonList(cb));
        expectedResponse.setOutput(Collections.singletonList(item));
        when(responseSpec.body(OpenApiResponse.class)).thenReturn(expectedResponse);

        // Act
        OpenApiResponse actualResponse = conversationService.ask("session-3", "Who is leading the points table?");

        // Assert
        assertNotNull(actualResponse);

        ArgumentCaptor<OpenApiRequest> captor = ArgumentCaptor.forClass(OpenApiRequest.class);
        verify(requestBodySpec).body(captor.capture());
        OpenApiRequest request = captor.getValue();

        boolean hasLiveContext = request.getInput().stream()
                .anyMatch(msg -> "system".equals(msg.getRole())
                        && msg.getContent().contains("[LIVE DATA CONTEXT"));
        assertTrue(hasLiveContext, "Request should contain live data context from Cricbuzz");
    }
}
