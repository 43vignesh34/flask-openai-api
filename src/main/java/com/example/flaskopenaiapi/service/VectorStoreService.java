package com.example.flaskopenaiapi.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@Service
public class VectorStoreService {

    private static final String DATA_DIR = "./data";
    private static final String CACHE_FILE = "./data/embeddings_cache.json";
    private static final String EMBEDDING_MODEL = "text-embedding-3-small";
    private static final int BATCH_SIZE = 50;

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private List<Chunk> index = Collections.synchronizedList(new ArrayList<>());

    public VectorStoreService(@Value("${OPENAI_API_KEY:}") String apiKey) {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @PostConstruct
    public void init() {
        try {
            loadIndex();
        } catch (Exception e) {
            System.err.println("Failed to load vector index on startup: " + e.getMessage());
        }
    }

    public synchronized void loadIndex() {
        File cacheFile = new File(CACHE_FILE);
        if (cacheFile.exists()) {
            System.out.println("Loading vector store from cache: " + CACHE_FILE);
            try {
                EmbeddingsCache cache = objectMapper.readValue(cacheFile, EmbeddingsCache.class);
                if (cache != null && cache.getChunks() != null) {
                    this.index = Collections.synchronizedList(new ArrayList<>(cache.getChunks()));
                    System.out.println("Loaded " + this.index.size() + " chunks from embeddings cache.");
                    return;
                }
            } catch (Exception e) {
                System.err.println("Error reading cache file, will rebuild index: " + e.getMessage());
            }
        }
        
        // If cache doesn't exist or failed to load, build it
        rebuildIndex();
    }

    public synchronized void rebuildIndex() {
        System.out.println("Rebuilding vector store index...");
        File dataDir = new File(DATA_DIR);
        if (!dataDir.exists() || !dataDir.isDirectory()) {
            System.out.println("Data directory " + DATA_DIR + " does not exist. No files to index.");
            this.index = Collections.synchronizedList(new ArrayList<>());
            return;
        }

        // 1. Gather all chunks from all MD and CSV files in the data directory
        List<String> allNewTexts = new ArrayList<>();
        List<String> chunkSources = new ArrayList<>();
        
        File[] files = dataDir.listFiles((dir, name) -> name.endsWith(".md") || name.endsWith(".csv"));
        if (files == null || files.length == 0) {
            System.out.println("No matching files (.md or .csv) found in " + DATA_DIR);
            this.index = Collections.synchronizedList(new ArrayList<>());
            return;
        }

        for (File file : files) {
            try {
                List<String> fileChunks = chunkFile(file);
                for (String chunkText : fileChunks) {
                    allNewTexts.add(chunkText);
                    chunkSources.add(file.getName());
                }
            } catch (Exception e) {
                System.err.println("Error reading file " + file.getName() + ": " + e.getMessage());
            }
        }

        // 2. Load existing cache to check if we can reuse vectors
        Map<String, List<Double>> existingVectors = new HashMap<>();
        File cacheFile = new File(CACHE_FILE);
        if (cacheFile.exists()) {
            try {
                EmbeddingsCache cache = objectMapper.readValue(cacheFile, EmbeddingsCache.class);
                if (cache != null && cache.getChunks() != null) {
                    for (Chunk c : cache.getChunks()) {
                        existingVectors.put(c.getText(), c.getVector());
                    }
                }
            } catch (Exception e) {
                System.err.println("Could not parse existing cache for reuse: " + e.getMessage());
            }
        }

        // 3. Resolve vectors for all chunks (reuse cache or request from OpenAI)
        List<Chunk> updatedIndex = new ArrayList<>();
        List<String> textsToEmbed = new ArrayList<>();
        List<Integer> indexesToEmbed = new ArrayList<>();

        for (int i = 0; i < allNewTexts.size(); i++) {
            String text = allNewTexts.get(i);
            String source = chunkSources.get(i);
            
            if (existingVectors.containsKey(text)) {
                Chunk cachedChunk = new Chunk();
                cachedChunk.setText(text);
                cachedChunk.setSource(source);
                cachedChunk.setVector(existingVectors.get(text));
                updatedIndex.add(cachedChunk);
            } else {
                // We'll need to fetch this embedding
                textsToEmbed.add(text);
                indexesToEmbed.add(updatedIndex.size());
                // Add a placeholder chunk that will be filled in
                Chunk placeholder = new Chunk();
                placeholder.setText(text);
                placeholder.setSource(source);
                updatedIndex.add(placeholder);
            }
        }

        // 4. Batch request new embeddings from OpenAI
        if (!textsToEmbed.isEmpty()) {
            System.out.println("Generating embeddings for " + textsToEmbed.size() + " new/changed chunks...");
            for (int i = 0; i < textsToEmbed.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, textsToEmbed.size());
                List<String> subList = textsToEmbed.subList(i, end);
                
                try {
                    List<List<Double>> vectors = fetchEmbeddings(subList);
                    for (int j = 0; j < subList.size(); j++) {
                        int indexInUpdated = indexesToEmbed.get(i + j);
                        updatedIndex.get(indexInUpdated).setVector(vectors.get(j));
                    }
                } catch (Exception e) {
                    System.err.println("Failed to fetch batch of embeddings: " + e.getMessage());
                }
            }
        }

        // Filter out any chunks that failed to get a vector
        updatedIndex.removeIf(c -> c.getVector() == null);

        // 5. Save the updated index back to the cache file
        this.index = Collections.synchronizedList(new ArrayList<>(updatedIndex));
        saveIndexToCache();
        System.out.println("Vector index rebuilt with " + this.index.size() + " chunks.");
    }

    private void saveIndexToCache() {
        try {
            EmbeddingsCache cache = new EmbeddingsCache();
            synchronized (index) {
                cache.setChunks(new ArrayList<>(index));
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(CACHE_FILE), cache);
            System.out.println("Saved vector index to cache: " + CACHE_FILE);
        } catch (Exception e) {
            System.err.println("Failed to save vector index cache: " + e.getMessage());
        }
    }

    private List<List<Double>> fetchEmbeddings(List<String> texts) {
        EmbeddingRequest requestBody = new EmbeddingRequest(EMBEDDING_MODEL, texts);
        EmbeddingResponse response = restClient.post()
                .uri("/embeddings")
                .body(requestBody)
                .retrieve()
                .body(EmbeddingResponse.class);

        if (response == null || response.getData() == null) {
            throw new RuntimeException("Empty response from OpenAI Embeddings API");
        }

        // Ensure data is sorted by index
        response.getData().sort(Comparator.comparingInt(EmbeddingData::getIndex));
        
        List<List<Double>> vectors = new ArrayList<>();
        for (EmbeddingData data : response.getData()) {
            vectors.add(data.getEmbedding());
        }
        return vectors;
    }

    public List<ChunkScore> search(String queryText, int topK) {
        if (index.isEmpty()) {
            System.out.println("Vector index is empty. Returning empty context.");
            return Collections.emptyList();
        }

        try {
            // Fetch query embedding
            List<List<Double>> queryVectors = fetchEmbeddings(Collections.singletonList(queryText));
            if (queryVectors.isEmpty()) return Collections.emptyList();
            List<Double> queryVector = queryVectors.get(0);

            // Compute cosine similarity in memory
            List<ChunkScore> results = new ArrayList<>();
            synchronized (index) {
                for (Chunk chunk : index) {
                    double similarity = cosineSimilarity(queryVector, chunk.getVector());
                    results.add(new ChunkScore(chunk, similarity));
                }
            }

            // Sort descending by score
            results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

            // Take topK
            return results.subList(0, Math.min(topK, results.size()));
        } catch (Exception e) {
            System.err.println("Search failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private double cosineSimilarity(List<Double> vectorA, List<Double> vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.size(); i++) {
            double a = vectorA.get(i);
            double b = vectorB.get(i);
            dotProduct += a * b;
            normA += a * a;
            normB += b * b;
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private List<String> chunkFile(File file) throws IOException {
        List<String> chunks = new ArrayList<>();
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        String filename = file.getName();
        
        if (filename.endsWith(".csv")) {
            String[] lines = content.split("\r?\n");
            if (lines.length > 1) {
                String header = lines[0];
                for (int i = 1; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (!line.isEmpty()) {
                        chunks.add("[Source: " + filename + "] " + header + " -> " + line);
                    }
                }
            }
        } else {
            // Split by double newlines to segment paragraphs and section blocks
            String[] paragraphs = content.split("\n\n");
            StringBuilder currentChunk = new StringBuilder();
            
            for (String p : paragraphs) {
                p = p.trim();
                if (p.isEmpty()) continue;
                
                // If appending this paragraph exceeds standard size (~800 chars), emit current chunk
                if (currentChunk.length() + p.length() > 800) {
                    if (currentChunk.length() > 0) {
                        chunks.add("[Source: " + filename + "]\n" + currentChunk.toString().trim());
                        currentChunk.setLength(0);
                    }
                }
                currentChunk.append(p).append("\n\n");
            }
            if (currentChunk.length() > 0) {
                chunks.add("[Source: " + filename + "]\n" + currentChunk.toString().trim());
            }
        }
        return chunks;
    }

    // --- Helper Classes for JSON and Processing ---

    public static class Chunk {
        private String source;
        private String text;
        private List<Double> vector;

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public List<Double> getVector() { return vector; }
        public void setVector(List<Double> vector) { this.vector = vector; }
    }

    public static class EmbeddingsCache {
        private List<Chunk> chunks = new ArrayList<>();

        public List<Chunk> getChunks() { return chunks; }
        public void setChunks(List<Chunk> chunks) { this.chunks = chunks; }
    }

    public static class ChunkScore {
        private final Chunk chunk;
        private final double score;

        public ChunkScore(Chunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }

        public Chunk getChunk() { return chunk; }
        public double getScore() { return score; }
    }

    private static class EmbeddingRequest {
        private final String model;
        private final List<String> input;

        public EmbeddingRequest(String model, List<String> input) {
            this.model = model;
            this.input = input;
        }

        public String getModel() { return model; }
        public List<String> getInput() { return input; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingResponse {
        private List<EmbeddingData> data;

        public List<EmbeddingData> getData() { return data; }
        public void setData(List<EmbeddingData> data) { this.data = data; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingData {
        private int index;
        private List<Double> embedding;

        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        public List<Double> getEmbedding() { return embedding; }
        public void setEmbedding(List<Double> embedding) { this.embedding = embedding; }
    }
}
