package com.example.flaskopenaiapi.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.flaskopenaiapi.model.Message;
import com.example.flaskopenaiapi.model.OpenApiRequest;
import com.example.flaskopenaiapi.model.OpenApiResponse;
import com.example.flaskopenaiapi.repository.PlayerRepository;
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
    private PlayerRepository playerRepository;

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private RestClient restClient;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService("", vectorStoreService);
        ReflectionTestUtils.setField(conversationService, "playerRepository", playerRepository);
        ReflectionTestUtils.setField(conversationService, "restClient", restClient);
    }

    @Test
    void testAsk_WhenDbIsEmpty_ShouldInjectIncompleteWarning() {
        // Arrange
        when(playerRepository.count()).thenReturn(0L);

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
        cb.setText("The IPL knowledge base is currently incomplete.");
        item.setContent(Collections.singletonList(cb));
        expectedResponse.setOutput(Collections.singletonList(item));
        
        when(responseSpec.body(OpenApiResponse.class)).thenReturn(expectedResponse);

        // Act
        OpenApiResponse actualResponse = conversationService.ask("session-123", "Analyze KKR squad");

        // Assert
        assertNotNull(actualResponse);
        assertEquals("The IPL knowledge base is currently incomplete.", actualResponse.getOutputText());

        ArgumentCaptor<OpenApiRequest> captor = ArgumentCaptor.forClass(OpenApiRequest.class);
        verify(requestBodySpec).body(captor.capture());
        OpenApiRequest request = captor.getValue();
        assertNotNull(request);

        // Verify system warning is injected
        boolean warningFound = false;
        for (Message msg : request.getInput()) {
            if ("system".equals(msg.getRole()) && msg.getContent().contains("[CRITICAL: KNOWLEDGE BASE INCOMPLETE]")) {
                warningFound = true;
                break;
            }
        }
        assertTrue(warningFound, "Should contain the knowledge base incomplete critical system message");
    }

    @Test
    void testAsk_WhenDbHasData_ShouldRetrieveContextFromVectorStore() {
        // Arrange
        when(playerRepository.count()).thenReturn(10L);

        VectorStoreService.Chunk chunk = new VectorStoreService.Chunk();
        chunk.setText("Rishabh Pant - 27 Cr");
        chunk.setSource("Database: players table");
        VectorStoreService.ChunkScore chunkScore = new VectorStoreService.ChunkScore(chunk, 0.85);

        when(vectorStoreService.search(anyString(), anyInt())).thenReturn(Collections.singletonList(chunkScore));

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
        cb.setText("Rishabh Pant is the captain of LSG.");
        item.setContent(Collections.singletonList(cb));
        expectedResponse.setOutput(Collections.singletonList(item));
        
        when(responseSpec.body(OpenApiResponse.class)).thenReturn(expectedResponse);

        // Act
        OpenApiResponse actualResponse = conversationService.ask("session-123", "Who is LSG captain?");

        // Assert
        assertNotNull(actualResponse);
        assertEquals("Rishabh Pant is the captain of LSG.", actualResponse.getOutputText());

        ArgumentCaptor<OpenApiRequest> captor = ArgumentCaptor.forClass(OpenApiRequest.class);
        verify(requestBodySpec).body(captor.capture());
        OpenApiRequest request = captor.getValue();
        assertNotNull(request);

        // Verify factual context is injected instead of warning
        boolean warningFound = false;
        boolean contextFound = false;
        for (Message msg : request.getInput()) {
            if ("system".equals(msg.getRole())) {
                if (msg.getContent().contains("[CRITICAL: KNOWLEDGE BASE INCOMPLETE]")) {
                    warningFound = true;
                }
                if (msg.getContent().contains("[FACTUAL CONTEXT - CRITICAL FOR SQUADS & INJURIES]")) {
                    contextFound = true;
                }
            }
        }
        assertFalse(warningFound, "Should not contain the warning message");
        assertTrue(contextFound, "Should contain the factual context from VectorStore");
    }
}
