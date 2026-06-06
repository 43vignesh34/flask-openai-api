package com.example.flaskopenaiapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class DataIngestionService {

    private static final String DATA_DIR = "./data";
    private static final String CRICBUZZ_SQUADS_URL = "https://www.cricbuzz.com/cricket-series/7476/ipl-2024/squads";
    private static final String CRICSHEET_ZIP_URL = "https://cricsheet.org/downloads/ipl_male_json.zip";
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(DATA_DIR));
            ensureFallbackData();
        } catch (Exception e) {
            System.err.println("Failed to initialize data directory or write fallback data: " + e.getMessage());
        }
    }

    public synchronized void ingestAll() {
        System.out.println("Starting full IPL data ingestion pipeline...");
        
        // 1. Scrape squads from Cricbuzz
        try {
            scrapeCricbuzzSquads();
        } catch (Exception e) {
            System.err.println("Cricbuzz squads scraping failed: " + e.getMessage() + ". Using local fallback.");
        }

        // 2. Fetch news/updates from Cricbuzz news or ESPNcricinfo
        try {
            scrapeCricbuzzNews();
        } catch (Exception e) {
            System.err.println("News scraping failed: " + e.getMessage() + ". Using local fallback.");
        }

        // 3. Download and parse Cricsheet matches
        try {
            downloadAndParseCricsheet();
        } catch (Exception e) {
            System.err.println("Cricsheet data ingestion failed: " + e.getMessage() + ". Using local fallback.");
        }

        System.out.println("IPL data ingestion pipeline completed.");
    }

    private void scrapeCricbuzzSquads() throws Exception {
        System.out.println("Scraping squads from Cricbuzz: " + CRICBUZZ_SQUADS_URL);
        
        Connection connection = Jsoup.connect(CRICBUZZ_SQUADS_URL)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(10000);
        Document doc = connection.get();
        
        // Find squad links
        Elements links = doc.select("a[href*=/squads/]");
        if (links.isEmpty()) {
            throw new IOException("No squad links found on the Cricbuzz page.");
        }

        StringBuilder squadsMarkdown = new StringBuilder("# Scraped IPL 2024 Squads from Cricbuzz\n\n");
        
        for (Element link : links) {
            String relativeUrl = link.attr("href");
            String teamName = link.text().trim();
            if (teamName.isEmpty() || relativeUrl.contains("series")) continue;

            String squadUrl = "https://www.cricbuzz.com" + relativeUrl;
            System.out.println("Fetching squad for: " + teamName + " from " + squadUrl);
            
            try {
                Document squadDoc = Jsoup.connect(squadUrl)
                        .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .timeout(10000)
                        .get();
                
                squadsMarkdown.append("## ").append(teamName).append("\n\n");
                
                // Get player names and details
                Elements playerElements = squadDoc.select(".cb-col-50");
                for (Element pe : playerElements) {
                    String playerName = pe.select("a").text().trim();
                    String role = pe.select(".cb-font-12").text().trim();
                    if (!playerName.isEmpty()) {
                        squadsMarkdown.append("- **").append(playerName).append("**");
                        if (!role.isEmpty()) {
                            squadsMarkdown.append(" (").append(role).append(")");
                        }
                        squadsMarkdown.append("\n");
                    }
                }
                squadsMarkdown.append("\n");
            } catch (Exception e) {
                System.err.println("Failed to fetch squad details for " + teamName + ": " + e.getMessage());
            }
        }
        
        Files.writeString(Paths.get(DATA_DIR, "scraped_squads.md"), squadsMarkdown.toString(), StandardCharsets.UTF_8);
        System.out.println("Saved scraped squads to " + DATA_DIR + "/scraped_squads.md");
    }

    private void scrapeCricbuzzNews() throws Exception {
        String newsUrl = "https://www.cricbuzz.com/cricket-news";
        System.out.println("Scraping news from Cricbuzz: " + newsUrl);
        
        Document doc = Jsoup.connect(newsUrl)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(10000)
                .get();

        Elements newsHeaders = doc.select(".cb-nws-hdln-link");
        if (newsHeaders.isEmpty()) {
            throw new IOException("No news headers found.");
        }

        StringBuilder newsMarkdown = new StringBuilder("# Scraped Cricbuzz Cricket News\n\n");
        int count = 0;
        for (Element header : newsHeaders) {
            String title = header.text().trim();
            String link = "https://www.cricbuzz.com" + header.attr("href");
            if (!title.isEmpty()) {
                newsMarkdown.append("### ").append(title).append("\n");
                newsMarkdown.append("Link: [").append(link).append("](").append(link).append(")\n\n");
                count++;
                if (count >= 15) break; // Limit news chunks
            }
        }

        Files.writeString(Paths.get(DATA_DIR, "scraped_news.md"), newsMarkdown.toString(), StandardCharsets.UTF_8);
        System.out.println("Saved scraped news to " + DATA_DIR + "/scraped_news.md");
    }

    private static class MatchSummary {
        String date;
        String content;

        public MatchSummary(String date, String content) {
            this.date = date;
            this.content = content;
        }
    }

    private void downloadAndParseCricsheet() throws Exception {
        System.out.println("Downloading Cricsheet ZIP from: " + CRICSHEET_ZIP_URL);
        
        URL url = new URL(CRICSHEET_ZIP_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)");

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("Failed to download ZIP file. HTTP response code: " + responseCode);
        }

        StringBuilder matchesMarkdown = new StringBuilder("# Cricsheet Recent IPL Matches\n\n");
        List<MatchSummary> matchSummaries = new ArrayList<>();

        try (InputStream is = conn.getInputStream();
             ZipInputStream zis = new ZipInputStream(is)) {
            
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".json")) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        bos.write(buffer, 0, len);
                    }
                    byte[] jsonBytes = bos.toByteArray();

                    try {
                        JsonNode root = objectMapper.readTree(jsonBytes);
                        JsonNode infoNode = root.get("info");
                        if (infoNode != null) {
                            JsonNode datesNode = infoNode.get("dates");
                            String matchDate = "";
                            if (datesNode != null && datesNode.isArray() && datesNode.size() > 0) {
                                matchDate = datesNode.get(0).asText();
                            }

                            if (matchDate.length() >= 4) {
                                try {
                                    int year = Integer.parseInt(matchDate.substring(0, 4));
                                    if (year >= 2024) {
                                        StringBuilder singleMatchSb = new StringBuilder();
                                        parseSingleMatch(root, matchDate, singleMatchSb);
                                        matchSummaries.add(new MatchSummary(matchDate, singleMatchSb.toString()));
                                    }
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    } catch (Exception parseException) {
                        System.err.println("Failed to parse JSON file " + entry.getName() + ": " + parseException.getMessage());
                    }
                }
                zis.closeEntry();
            }
        }

        if (!matchSummaries.isEmpty()) {
            // Sort matches chronologically
            matchSummaries.sort(Comparator.comparing(m -> m.date));
            
            // Collect the latest 15 matches (at the end of the sorted list)
            int startIdx = Math.max(0, matchSummaries.size() - 15);
            for (int i = startIdx; i < matchSummaries.size(); i++) {
                matchesMarkdown.append(matchSummaries.get(i).content);
            }

            Files.writeString(Paths.get(DATA_DIR, "cricsheet_recent_matches.md"), matchesMarkdown.toString(), StandardCharsets.UTF_8);
            System.out.println("Saved " + (matchSummaries.size() - startIdx) + " recent (2024+) Cricsheet matches to " + DATA_DIR + "/cricsheet_recent_matches.md");
        } else {
            System.out.println("No recent (2024+) matches found in Cricsheet zip.");
        }
    }

    private void parseSingleMatch(JsonNode root, String matchDate, StringBuilder sb) {
        JsonNode info = root.get("info");
        JsonNode teams = info.get("teams");
        String teamA = teams.get(0).asText();
        String teamB = teams.get(1).asText();
        
        String venue = info.has("venue") ? info.get("venue").asText() : "Unknown Venue";
        String city = info.has("city") ? info.get("city").asText() : "";
        
        String winner = "No Result / Draw";
        String margin = "";
        if (info.has("outcome")) {
            JsonNode outcome = info.get("outcome");
            if (outcome.has("winner")) {
                winner = outcome.get("winner").asText();
                if (outcome.has("by")) {
                    JsonNode by = outcome.get("by");
                    if (by.has("runs")) {
                        margin = "by " + by.get("runs").asText() + " runs";
                    } else if (by.has("wickets")) {
                        margin = "by " + by.get("wickets").asText() + " wickets";
                    }
                }
            } else if (outcome.has("result")) {
                winner = "Match tied (" + outcome.get("result").asText() + ")";
            }
        }

        String playerOfMatch = "";
        if (info.has("player_of_match")) {
            JsonNode pom = info.get("player_of_match");
            if (pom.isArray() && pom.size() > 0) {
                playerOfMatch = pom.get(0).asText();
            }
        }

        sb.append("### IPL Match on ").append(matchDate).append("\n");
        sb.append("- **Teams**: ").append(teamA).append(" vs ").append(teamB).append("\n");
        sb.append("- **Venue**: ").append(venue).append(city.isEmpty() ? "" : ", " + city).append("\n");
        sb.append("- **Winner**: ").append(winner).append(" ").append(margin).append("\n");
        if (!playerOfMatch.isEmpty()) {
            sb.append("- **Player of the Match**: ").append(playerOfMatch).append("\n");
        }
        sb.append("\n");
    }

    private void ensureFallbackData() throws IOException {
        Path squadsPath = Paths.get(DATA_DIR, "squads.md");
        if (!Files.exists(squadsPath)) {
            String squadsFallback = "# IPL Squads (2024 - 2026 Baseline & Key Signings)\n\n" +
                    "## Lucknow Super Giants (LSG)\n" +
                    "- **Rishabh Pant** (Captain, Wicketkeeper-Batsman, Indian) - 27 Cr (Record 2025 Signing)\n" +
                    "- **KL Rahul** (Batsman, Indian) - 17 Cr\n" +
                    "- **Nicholas Pooran** (Wicketkeeper-Batsman, Overseas/West Indies) - 16 Cr\n" +
                    "- **Marcus Stoinis** (All-rounder, Overseas/Australia) - 9.2 Cr\n" +
                    "- **Mayank Yadav** (Bowler, Indian) - 0.2 Cr\n" +
                    "- **Ravi Bishnoi** (Bowler, Indian) - 4 Cr\n" +
                    "- **Ayush Badoni** (Batsman, Indian) - 0.2 Cr\n\n" +
                    "## Punjab Kings (PBKS)\n" +
                    "- **Shreyas Iyer** (Captain, Batsman, Indian) - 26.75 Cr (2025 Mega Auction Signing)\n" +
                    "- **Sam Curran** (All-rounder, Overseas/England) - 18.5 Cr\n" +
                    "- **Liam Livingstone** (All-rounder, Overseas/England) - 11.5 Cr\n" +
                    "- **Shashank Singh** (Batsman, Indian) - 0.2 Cr\n" +
                    "- **Harshal Patel** (Bowler, Indian) - 11.75 Cr\n" +
                    "- **Arshdeep Singh** (Bowler, Indian) - 4 Cr\n\n" +
                    "## Kolkata Knight Riders (KKR)\n" +
                    "- **Venkatesh Iyer** (All-rounder, Indian) - 23.75 Cr (2025 Mega Auction Signing)\n" +
                    "- **Sunil Narine** (All-rounder, Overseas/West Indies) - 6 Cr\n" +
                    "- **Andre Russell** (All-rounder, Overseas/West Indies) - 12 Cr\n" +
                    "- **Rinku Singh** (Batsman, Indian) - 0.55 Cr\n" +
                    "- **Phil Salt** (Wicketkeeper-Batsman, Overseas/England) - 1.5 Cr\n" +
                    "- **Harshit Rana** (Bowler, Indian) - 0.2 Cr\n" +
                    "- **Varun Chakravarthy** (Bowler, Indian) - 8 Cr\n\n" +
                    "## Chennai Super Kings (CSK)\n" +
                    "- **Ruturaj Gaikwad** (Captain, Batsman) - 6 Cr\n" +
                    "- **MS Dhoni** (Wicketkeeper-Batsman, Indian) - 12 Cr\n" +
                    "- **Ravindra Jadeja** (All-rounder, Indian) - 16 Cr\n" +
                    "- **Matheesha Pathirana** (Bowler, Overseas/Sri Lanka) - 0.2 Cr\n" +
                    "- **Shivam Dube** (All-rounder, Indian) - 4 Cr\n" +
                    "- **Rachin Ravindra** (All-rounder, Overseas/New Zealand) - 1.8 Cr\n" +
                    "- **Daryl Mitchell** (All-rounder, Overseas/New Zealand) - 14 Cr\n" +
                    "- **Deepak Chahar** (Bowler, Indian) - 14 Cr\n\n" +
                    "## Mumbai Indians (MI)\n" +
                    "- **Hardik Pandya** (Captain, All-rounder, Indian) - 15 Cr\n" +
                    "- **Rohit Sharma** (Batsman, Indian) - 16 Cr\n" +
                    "- **Jasprit Bumrah** (Bowler, Indian) - 12 Cr\n" +
                    "- **Suryakumar Yadav** (Batsman, Indian) - 8 Cr (2025 MVP)\n" +
                    "- **Ishan Kishan** (Wicketkeeper-Batsman, Indian) - 15.25 Cr\n" +
                    "- **Tilak Varma** (Batsman, Indian) - 1.7 Cr\n\n" +
                    "## Royal Challengers Bengaluru (RCB)\n" +
                    "- **Faf du Plessis** (Captain, Batsman, Overseas/South Africa) - 7 Cr\n" +
                    "- **Virat Kohli** (Batsman, Indian) - 15 Cr\n" +
                    "- **Glenn Maxwell** (All-rounder, Overseas/Australia) - 11 Cr\n" +
                    "- **Cameron Green** (All-rounder, Overseas/Australia) - 17.5 Cr\n" +
                    "- **Rajat Patidar** (Batsman, Indian) - 0.2 Cr\n" +
                    "- **Mohammed Siraj** (Bowler, Indian) - 7 Cr\n" +
                    "- **Yash Dayal** (Bowler, Indian) - 5 Cr\n\n" +
                    "## Rajasthan Royals (RR)\n" +
                    "- **Sanju Samson** (Captain, Wicketkeeper-Batsman, Indian) - 14 Cr\n" +
                    "- **Yashasvi Jaiswal** (Batsman, Indian) - 4 Cr\n" +
                    "- **Jos Buttler** (Batsman, Overseas/England) - 10 Cr\n" +
                    "- **Riyan Parag** (Batsman/All-rounder, Indian) - 3.8 Cr\n" +
                    "- **Yuzvendra Chahal** (Bowler, Indian) - 6.5 Cr\n" +
                    "- **Trent Boult** (Bowler, Overseas/New Zealand) - 8 Cr\n" +
                    "- **Vaibhav Sooryavanshi** (Batsman, Indian) - 1.1 Cr (2026 Orange Cap Winner)\n\n" +
                    "## Sunrisers Hyderabad (SRH)\n" +
                    "- **Pat Cummins** (Captain, Bowler, Overseas/Australia) - 20.5 Cr\n" +
                    "- **Travis Head** (Batsman, Overseas/Australia) - 6.8 Cr\n" +
                    "- **Abhishek Sharma** (Batsman/All-rounder, Indian) - 6.5 Cr\n" +
                    "- **Heinrich Klaasen** (Wicketkeeper-Batsman, Overseas/South Africa) - 5.25 Cr\n" +
                    "- **Bhuvneshwar Kumar** (Bowler, Indian) - 4.2 Cr\n" +
                    "- **T. Natarajan** (Bowler, Indian) - 4 Cr\n\n" +
                    "## Gujarat Titans (GT)\n" +
                    "- **Shubman Gill** (Captain, Batsman, Indian) - 8 Cr\n" +
                    "- **Rashid Khan** (All-rounder, Overseas/Afghanistan) - 15 Cr\n" +
                    "- **Sai Sudharsan** (Batsman, Indian) - 0.2 Cr (2025 Orange Cap Winner)\n" +
                    "- **Kagiso Rabada** (Bowler, Overseas/South Africa) - 9.25 Cr (2026 Purple Cap Winner)\n" +
                    "- **Prasidh Krishna** (Bowler, Indian) - 8 Cr (2025 Purple Cap Winner)\n" +
                    "- **David Miller** (Batsman, Overseas/South Africa) - 3 Cr\n\n" +
                    "## Delhi Capitals (DC)\n" +
                    "- **Axar Patel** (Captain, All-rounder, Indian) - 9 Cr\n" +
                    "- **Kuldeep Yadav** (Bowler, Indian) - 2 Cr\n" +
                    "- **Jake Fraser-McGurk** (Batsman, Overseas/Australia) - 0.5 Cr\n" +
                    "- **Tristan Stubbs** (Wicketkeeper-Batsman, Overseas/South Africa) - 0.5 Cr\n" +
                    "- **Khaleel Ahmed** (Bowler, Indian) - 5.25 Cr\n";
            Files.writeString(squadsPath, squadsFallback, StandardCharsets.UTF_8);
            System.out.println("Wrote fallback squads data to " + squadsPath);
        }

        Path statsPath = Paths.get(DATA_DIR, "player_stats.csv");
        if (!Files.exists(statsPath)) {
            String statsFallback = "PlayerName,Team,Matches,Runs,StrikeRate,Wickets,EconomyRate\n" +
                    "Vaibhav Sooryavanshi,RR,16,776,164.2,0,0.0\n" +
                    "Sai Sudharsan,GT,16,759,145.8,0,0.0\n" +
                    "Virat Kohli,RCB,15,741,154.7,0,0.0\n" +
                    "Kagiso Rabada,GT,17,0,0.0,29,7.4\n" +
                    "Prasidh Krishna,GT,15,0,0.0,25,8.2\n" +
                    "Ruturaj Gaikwad,CSK,14,583,141.2,0,0.0\n" +
                    "Travis Head,SRH,15,567,191.6,0,0.0\n" +
                    "Abhishek Sharma,SRH,16,484,204.2,2,10.2\n" +
                    "Heinrich Klaasen,SRH,16,479,171.1,0,0.0\n" +
                    "Suryakumar Yadav,MI,14,512,166.4,0,0.0\n" +
                    "Sanju Samson,RR,16,531,153.5,0,0.0\n" +
                    "Riyan Parag,RR,16,573,149.2,0,0.0\n" +
                    "Sunil Narine,KKR,15,488,180.7,17,6.7\n" +
                    "Andre Russell,KKR,15,222,185.0,19,8.6\n" +
                    "Jasprit Bumrah,MI,13,0,0.0,20,6.5\n" +
                    "Harshal Patel,PBKS,14,0,0.0,24,9.7\n";
            Files.writeString(statsPath, statsFallback, StandardCharsets.UTF_8);
            System.out.println("Wrote fallback player stats data to " + statsPath);
        }

        Path iplInfoPath = Paths.get(DATA_DIR, "ipl_info.md");
        if (!Files.exists(iplInfoPath)) {
            String infoFallback = "# IPL Tournament General Information & Season Summaries\n\n" +
                    "## IPL 2026 Season Standings & Records\n" +
                    "- **Champions**: Royal Challengers Bengaluru (RCB) won their second consecutive title, defeating Gujarat Titans (GT) by 5 wickets in the final on May 31, 2026, at the Narendra Modi Stadium in Ahmedabad.\n" +
                    "- **Runner-up**: Gujarat Titans (GT)\n" +
                    "- **Orange Cap Winner**: Vaibhav Sooryavanshi (RR) - 776 runs in 16 matches.\n" +
                    "- **Purple Cap Winner**: Kagiso Rabada (GT) - 29 wickets in 17 matches.\n\n" +
                    "## IPL 2025 Season Standings & Records\n" +
                    "- **Champions**: Royal Challengers Bengaluru (RCB) won their maiden IPL title, defeating Punjab Kings (PBKS) in the final.\n" +
                    "- **Runner-up**: Punjab Kings (PBKS)\n" +
                    "- **Orange Cap Winner**: Sai Sudharsan (GT) - 759 runs in 16 matches.\n" +
                    "- **Purple Cap Winner**: Prasidh Krishna (GT) - 25 wickets in 15 matches.\n" +
                    "- **Most Valuable Player (MVP)**: Suryakumar Yadav (MI).\n\n" +
                    "## IPL 2024 Season Standings & Records\n" +
                    "- **Champions**: Kolkata Knight Riders (KKR) won the IPL 2024 final at Chennai on May 26, 2024, defeating Sunrisers Hyderabad (SRH) by 8 wickets.\n" +
                    "- **Runner-up**: Sunrisers Hyderabad (SRH)\n" +
                    "- **Orange Cap Winner**: Virat Kohli (RCB) - 741 runs in 15 matches.\n" +
                    "- **Purple Cap Winner**: Harshal Patel (PBKS) - 24 wickets in 14 matches.\n" +
                    "- **Most Valuable Player (MVP)**: Sunil Narine (KKR).\n\n" +
                    "## IPL Core Roster & Auction Rules\n" +
                    "- **Squad size limit**: Minimum of 18 players, maximum of 25 players.\n" +
                    "- **Overseas player limit**: Maximum of 8 overseas players in the squad.\n" +
                    "- **Playing XI rule**: Maximum of 4 overseas players can be included in the starting playing XI. If a team starts with fewer than 4 overseas players, they can substitute in an overseas player as an Impact Player as long as the total overseas count in the match never exceeds 4.\n" +
                    "- **Impact Player Rule**: Teams name 5 substitutes at the toss. One of these substitutes can be introduced as an 'Impact Player' at any stage of the match (at the start of an innings, when a wicket falls, or at the end of an over). The player being substituted out cannot participate further in the match.\n" +
                    "- **Double DRS Review Rule**: Players can review wide ball and waist-high no-ball decisions using the Decision Review System (DRS).\n" +
                    "- **Two Bouncers per Over Rule**: Bowlers are allowed to bowl up to two short-pitched deliveries (bouncers) per over.\n";
            Files.writeString(iplInfoPath, infoFallback, StandardCharsets.UTF_8);
            System.out.println("Wrote fallback IPL info data to " + iplInfoPath);
        }
    }
}

