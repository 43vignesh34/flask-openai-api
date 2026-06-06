package com.example.flaskopenaiapi.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LiveDataFetcherService — fetches current IPL facts from Cricbuzz / ESPNcricinfo / IPL official
 * at query time and returns a formatted context string for injection into the LLM.
 *
 * Nothing is saved permanently. Data is fetched, used, and discarded.
 */
@Service
public class LiveDataFetcherService {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final int TIMEOUT_MS = 12000;

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'IST'");

    // ─────────────────────────────────────────────────────────────
    // Intent enum
    // ─────────────────────────────────────────────────────────────

    public enum Intent {
        SQUAD, STANDINGS, RESULTS, AUCTION, INJURY, NEWS, PLAYER, STRATEGY
    }

    // ─────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds a live context string for the given user query.
     * Returns null for pure strategy questions that require no live facts.
     */
    public String buildLiveContext(String userQuery) {
        Set<Intent> intents = detectIntents(userQuery);

        // Pure strategy — no live data needed
        if (intents.isEmpty() || (intents.size() == 1 && intents.contains(Intent.STRATEGY))) {
            return null;
        }

        StringBuilder context = new StringBuilder();
        context.append("[LIVE DATA CONTEXT — fetched at ").append(now()).append("]\n");
        context.append("Use ONLY the following data for current IPL facts. ")
               .append("Do NOT use model training memory for squads, results, standings, injuries, or auction prices.\n\n");

        // Discover series slug once per call if needed for structured pages
        String seriesSlug = null;
        boolean needsSeries = intents.contains(Intent.SQUAD)
                || intents.contains(Intent.STANDINGS)
                || intents.contains(Intent.RESULTS);
        if (needsSeries) {
            seriesSlug = discoverCurrentIPLSeriesSlug();
        }

        String teamName   = intents.contains(Intent.SQUAD)  ? extractTeamName(userQuery)   : null;
        String playerName = intents.contains(Intent.PLAYER) ? extractPlayerName(userQuery)  : null;

        boolean anySucceeded = false;

        if (intents.contains(Intent.STANDINGS)) {
            String data = fetchIPLStandings(seriesSlug);
            if (data != null) { context.append(data).append("\n\n"); anySucceeded = true; }
        }

        if (intents.contains(Intent.RESULTS)) {
            String data = fetchIPLResults(seriesSlug);
            if (data != null) { context.append(data).append("\n\n"); anySucceeded = true; }
        }

        if (intents.contains(Intent.SQUAD)) {
            String data = fetchIPLSquad(seriesSlug, teamName);
            if (data != null) { context.append(data).append("\n\n"); anySucceeded = true; }
        }

        if (intents.contains(Intent.AUCTION)) {
            String data = fetchIPLAuction();
            if (data != null) { context.append(data).append("\n\n"); anySucceeded = true; }
        }

        if (intents.contains(Intent.INJURY) || intents.contains(Intent.NEWS)) {
            String data = fetchIPLNews(intents.contains(Intent.INJURY));
            if (data != null) { context.append(data).append("\n\n"); anySucceeded = true; }
        }

        if (intents.contains(Intent.PLAYER) && playerName != null) {
            String data = fetchPlayerProfile(playerName);
            if (data != null) { context.append(data).append("\n\n"); anySucceeded = true; }
        }

        if (!anySucceeded) {
            return buildAllFailedMessage(intents);
        }

        return context.toString().trim();
    }

    // ─────────────────────────────────────────────────────────────
    // Intent detection
    // ─────────────────────────────────────────────────────────────

    Set<Intent> detectIntents(String query) {
        String q = query.toLowerCase();
        Set<Intent> intents = new LinkedHashSet<>();

        if (containsAny(q, "squad", "team", "players", "roster",
                " xi", "lineup", "retain", "playing 11", "playing eleven", "who is in")) {
            intents.add(Intent.SQUAD);
        }
        if (containsAny(q, "standing", "points table", "points-table", "rank",
                "qualif", "position", "top 4", "table", "who is leading")) {
            intents.add(Intent.STANDINGS);
        }
        if (containsAny(q, "result", " match", " won", " lost", "beat",
                " score", "yesterday", "recent match", "last match", "today's match", "final")) {
            intents.add(Intent.RESULTS);
        }
        if (containsAny(q, "auction", "purse", " bid", " crore", "retention",
                "signing", "signed", "bought for", "mega auction")) {
            intents.add(Intent.AUCTION);
        }
        if (containsAny(q, "injur", "ruled out", "fit to play", "return from",
                "unavailable", "concussion", "availability", "miss", "sideline")) {
            intents.add(Intent.INJURY);
        }
        if (containsAny(q, "latest news", "update", "announced", "confirmed",
                "report", "breaking")) {
            intents.add(Intent.NEWS);
        }
        if (containsAny(q, "career", "profile", "stats", "record", "wicket", "run",
                "average", "economy", "strike rate")) {
            intents.add(Intent.PLAYER);
        }
        if (containsAny(q, "strategy", "recommend", "suggest", "analyze", "analyse",
                "build squad", "should i", "best approach", "tactic", "plan", "what makes")) {
            intents.add(Intent.STRATEGY);
        }

        return intents;
    }

    // ─────────────────────────────────────────────────────────────
    // Series discovery
    // ─────────────────────────────────────────────────────────────

    /**
     * Crawls Cricbuzz /cricket-series to find the most recent IPL series slug.
     * Returns a slug like "cricket-series/9237/indian-premier-league-2025"
     */
    private String discoverCurrentIPLSeriesSlug() {
        String[] pagesToTry = {
            "https://www.cricbuzz.com/cricket-series",
            "https://www.cricbuzz.com/cricket-series?type=International",
            "https://www.cricbuzz.com"
        };

        for (String pageUrl : pagesToTry) {
            try {
                Document doc = jsoupGet(pageUrl);
                if (doc == null) continue;

                // Look for IPL series links (pattern: /cricket-series/NNNN/slug)
                Elements links = doc.select("a[href*=indian-premier-league], a[href*=ipl]");

                String bestSlug = null;
                int bestYear   = 0;

                for (Element link : links) {
                    String href = link.attr("href");
                    if (!href.matches(".*/cricket-series/\\d+/.*")) continue;

                    // Extract highest 4-digit year from the href
                    Matcher m = Pattern.compile("(20\\d{2})").matcher(href);
                    int year = 0;
                    while (m.find()) {
                        int y = Integer.parseInt(m.group(1));
                        if (y > year) year = y;
                    }
                    if (year > bestYear) {
                        bestYear = year;
                        // Strip leading slash to get a relative slug
                        bestSlug = href.replaceFirst("^/", "");
                    }
                }

                if (bestSlug != null) {
                    System.out.println("Discovered IPL series slug: " + bestSlug + " (year " + bestYear + ")");
                    return bestSlug;
                }

            } catch (Exception e) {
                System.err.println("discoverCurrentIPLSeriesSlug failed for " + pageUrl + ": " + e.getMessage());
            }
        }

        System.err.println("Could not discover current IPL series slug from Cricbuzz.");
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // Fetchers
    // ─────────────────────────────────────────────────────────────

    private String fetchIPLStandings(String seriesSlug) {
        String url = seriesSlug != null
                ? "https://www.cricbuzz.com/" + seriesSlug + "/points-table"
                : "https://www.cricbuzz.com/cricket-series";
        try {
            Document doc = jsoupGet(url);
            if (doc == null) return failMsg("IPL Points Table", url);

            StringBuilder sb = new StringBuilder();
            sb.append("=== SOURCE: Cricbuzz | IPL Points Table | FETCHED: ").append(now()).append(" ===\n");

            // Try multiple CSS selectors for robustness
            Elements rows = doc.select(".cb-srs-pnts tr");
            if (rows.isEmpty()) rows = doc.select("table.cb-srs-pnts-tbl tr");
            if (rows.isEmpty()) rows = doc.select("table tr");

            int rowsAdded = 0;
            for (Element row : rows) {
                Elements cells = row.select("td, th");
                if (!cells.isEmpty()) {
                    String rowText = cells.stream()
                            .map(Element::text)
                            .filter(t -> !t.isBlank())
                            .collect(Collectors.joining(" | "));
                    if (!rowText.isBlank()) {
                        sb.append(rowText).append("\n");
                        rowsAdded++;
                    }
                }
            }

            if (rowsAdded <= 1) return failMsg("IPL Points Table", url);
            return sb.toString();

        } catch (Exception e) {
            System.err.println("fetchIPLStandings failed: " + e.getMessage());
            return failMsg("IPL Points Table", url);
        }
    }

    private String fetchIPLResults(String seriesSlug) {
        String url = seriesSlug != null
                ? "https://www.cricbuzz.com/" + seriesSlug + "/results"
                : "https://www.cricbuzz.com/cricket-match/live-matches";
        try {
            Document doc = jsoupGet(url);
            if (doc == null) return failMsg("IPL Recent Results", url);

            StringBuilder sb = new StringBuilder();
            sb.append("=== SOURCE: Cricbuzz | IPL Recent Results | FETCHED: ").append(now()).append(" ===\n");

            // Multiple possible selectors for match result cards
            Elements cards = doc.select(".cb-col-100.cb-plyr-tbody, .cb-series-matches, .cb-col.cb-col-100.cb-ltst-wgt-hmscg");
            if (cards.isEmpty()) cards = doc.select(".cb-mtch-lst.cb-col-100");

            int count = 0;
            for (Element card : cards) {
                String text = card.text().trim();
                if (!text.isEmpty() && text.length() > 10) {
                    sb.append("• ").append(text).append("\n");
                    if (++count >= 10) break;
                }
            }

            if (count == 0) {
                // Last resort: grab any text block that looks like a result
                Elements allText = doc.select("div:contains(won), div:contains(beat)");
                for (Element el : allText) {
                    String t = el.ownText().trim();
                    if (t.length() > 15) { sb.append("• ").append(t).append("\n"); if (++count >= 10) break; }
                }
            }

            if (count == 0) return failMsg("IPL Recent Results", url);
            return sb.toString();

        } catch (Exception e) {
            System.err.println("fetchIPLResults failed: " + e.getMessage());
            return failMsg("IPL Recent Results", url);
        }
    }

    private String fetchIPLSquad(String seriesSlug, String teamName) {
        String squadsUrl = seriesSlug != null
                ? "https://www.cricbuzz.com/" + seriesSlug + "/squads"
                : null;

        try {
            // If no series slug, try Cricbuzz search
            if (squadsUrl == null && teamName != null) {
                squadsUrl = "https://www.cricbuzz.com/search?q=" +
                        URLEncoder.encode("IPL " + teamName + " squad", StandardCharsets.UTF_8);
            }
            if (squadsUrl == null) return failMsg("IPL Squads", "cricbuzz.com");

            Document squadsIndexDoc = jsoupGet(squadsUrl);
            if (squadsIndexDoc == null) return failMsg("IPL Squads", squadsUrl);

            StringBuilder sb = new StringBuilder();
            sb.append("=== SOURCE: Cricbuzz | IPL Squads | FETCHED: ").append(now()).append(" ===\n");

            // Find individual team squad links on the squads index page
            Elements teamLinks = squadsIndexDoc.select("a[href*=/squads/]");
            if (teamLinks.isEmpty()) teamLinks = squadsIndexDoc.select("a[href*=squad]");

            int teamsAdded = 0;
            for (Element teamLink : teamLinks) {
                String teamTitle = teamLink.text().trim();
                String teamHref  = teamLink.attr("href");
                if (teamTitle.isEmpty() || teamHref.isEmpty()) continue;
                if (teamHref.contains("series")) continue; // Skip series-level links

                // Filter by team name if user asked for a specific team
                if (teamName != null && !matchesTeam(teamName, teamTitle)) continue;

                String squadUrl = teamHref.startsWith("http")
                        ? teamHref
                        : "https://www.cricbuzz.com" + teamHref;

                Document squadDoc = jsoupGet(squadUrl);
                if (squadDoc == null) continue;

                sb.append("\n## ").append(teamTitle).append("\n");

                Elements players = squadDoc.select(".cb-col-50, .cb-plyr-nme, .cb-player-card-left");
                int playerCount = 0;
                for (Element player : players) {
                    String name = player.select("a").text().trim();
                    String role = player.select(".cb-font-12").text().trim();
                    if (name.isEmpty()) { name = player.text().trim(); }
                    if (!name.isEmpty()) {
                        sb.append("  - ").append(name);
                        if (!role.isEmpty()) sb.append(" (").append(role).append(")");
                        sb.append("\n");
                        playerCount++;
                    }
                }

                if (playerCount > 0) {
                    teamsAdded++;
                    if (teamName != null) break;                  // Specific team — stop after first match
                    if (teamsAdded >= 3) break;                   // General query — limit to 3 teams
                }
            }

            if (teamsAdded == 0) return failMsg("IPL Squads" + (teamName != null ? " for " + teamName : ""), squadsUrl);
            return sb.toString();

        } catch (Exception e) {
            System.err.println("fetchIPLSquad failed: " + e.getMessage());
            return failMsg("IPL Squads", squadsUrl != null ? squadsUrl : "cricbuzz.com");
        }
    }

    private String fetchIPLAuction() {
        // Try IPL official first
        String[] urls = {
            "https://www.iplt20.com/auction",
            "https://www.cricbuzz.com/cricket-news/ipl-auction",
            "https://www.cricbuzz.com/cricket-news"
        };

        for (String url : urls) {
            try {
                Document doc = jsoupGet(url);
                if (doc == null) continue;

                StringBuilder sb = new StringBuilder();
                sb.append("=== SOURCE: ").append(url.contains("iplt20") ? "IPL Official (iplt20.com)" : "Cricbuzz")
                  .append(" | IPL Auction | FETCHED: ").append(now()).append(" ===\n");

                // Try various selectors
                Elements items = doc.select(".auction-card, .player-auction, .squad-auction, .cb-nws-hdln-link, article");
                int count = 0;
                for (Element item : items) {
                    String text = item.text().trim();
                    if (!text.isEmpty() && text.length() > 15) {
                        // Filter auction-relevant content if on news page
                        if (url.contains("cricket-news") && !text.toLowerCase().contains("auction")
                                && !text.toLowerCase().contains("ipl") && !text.toLowerCase().contains("crore")) {
                            continue;
                        }
                        sb.append("• ").append(text).append("\n");
                        if (++count >= 12) break;
                    }
                }

                if (count > 0) return sb.toString();

            } catch (Exception e) {
                System.err.println("fetchIPLAuction failed for " + url + ": " + e.getMessage());
            }
        }
        return failMsg("IPL Auction Information", "iplt20.com / cricbuzz.com");
    }

    private String fetchIPLNews(boolean injuryFocus) {
        String url = "https://www.cricbuzz.com/cricket-news";
        try {
            Document doc = jsoupGet(url);
            if (doc == null) return failMsg(injuryFocus ? "IPL Injury News" : "IPL News", url);

            StringBuilder sb = new StringBuilder();
            sb.append("=== SOURCE: Cricbuzz | ")
              .append(injuryFocus ? "IPL Injury & Availability News" : "IPL Latest News")
              .append(" | FETCHED: ").append(now()).append(" ===\n");

            Elements newsItems = doc.select(".cb-nws-hdln-link, .cb-nws-hdln");
            int count = 0;
            for (Element item : newsItems) {
                String text = item.text().trim();
                if (text.isEmpty()) continue;
                String tl = text.toLowerCase();

                if (injuryFocus) {
                    if (!containsAny(tl, "injur", "ruled out", "fit", "return", "unavailable", "concussion", "miss")) {
                        continue;
                    }
                } else {
                    // For general news, keep IPL-related headlines
                    if (!tl.contains("ipl") && !tl.contains("cricket") && count > 5) continue;
                }

                sb.append("• ").append(text).append("\n");
                if (++count >= 15) break;
            }

            if (count == 0) return failMsg(injuryFocus ? "IPL Injury News" : "IPL News", url);
            return sb.toString();

        } catch (Exception e) {
            System.err.println("fetchIPLNews failed: " + e.getMessage());
            return failMsg(injuryFocus ? "IPL Injury News" : "IPL News", url);
        }
    }

    private String fetchPlayerProfile(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        try {
            String searchUrl = "https://www.cricbuzz.com/search?q="
                    + URLEncoder.encode(playerName, StandardCharsets.UTF_8);
            Document searchDoc = jsoupGet(searchUrl);
            if (searchDoc == null) return failMsg("Player: " + playerName, searchUrl);

            // Find a player profile link from search results
            Elements profileLinks = searchDoc.select("a[href*=/profiles/]");
            if (profileLinks.isEmpty()) return failMsg("Player: " + playerName, searchUrl);

            String profileUrl = "https://www.cricbuzz.com" + profileLinks.first().attr("href");
            Document profileDoc = jsoupGet(profileUrl);
            if (profileDoc == null) return failMsg("Player: " + playerName, profileUrl);

            StringBuilder sb = new StringBuilder();
            sb.append("=== SOURCE: Cricbuzz | Player Profile: ").append(playerName)
              .append(" | FETCHED: ").append(now()).append(" ===\n");

            // Basic info block
            Element infoBlock = profileDoc.selectFirst(".cb-col-100.cb-plyr-form, .cb-col-67.cb-col-rt");
            if (infoBlock != null) sb.append(infoBlock.text().trim()).append("\n\n");

            // Stats tables
            Elements statRows = profileDoc.select(".cb-col-100.cb-ltst-wgt-hmscg table tr");
            for (Element row : statRows) {
                String rowText = row.text().trim();
                if (!rowText.isBlank()) sb.append(rowText).append("\n");
            }

            return sb.toString();

        } catch (Exception e) {
            System.err.println("fetchPlayerProfile failed for " + playerName + ": " + e.getMessage());
            return failMsg("Player: " + playerName, "cricbuzz.com");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // JSoup wrapper
    // ─────────────────────────────────────────────────────────────

    private Document jsoupGet(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("Connection", "keep-alive")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Cache-Control", "max-age=0")
                    .referrer("https://www.google.com/search?q=IPL+2026+cricket")
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .get();
        } catch (Exception e) {
            System.err.println("jsoupGet(" + url + ") failed: " + e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private String now() {
        return LocalDateTime.now(ZoneId.of("Asia/Kolkata")).format(TIMESTAMP_FMT);
    }

    private String failMsg(String dataType, String url) {
        return "=== [LIVE RETRIEVAL FAILED: " + dataType + "] ===\n"
             + "Source attempted: " + url + "\n"
             + "Attempted at: " + now() + "\n"
             + "IMPORTANT: Do NOT answer this fact from model memory. "
             + "Tell the user: live IPL data could not be fetched right now — please try again in a moment.\n";
    }

    private String buildAllFailedMessage(Set<Intent> intents) {
        String types = intents.stream()
                .filter(i -> i != Intent.STRATEGY)
                .map(Intent::name)
                .collect(Collectors.joining(", "));
        return "[LIVE RETRIEVAL FAILED]\n"
             + "Could not fetch live IPL data for: " + types + " at " + now() + ".\n"
             + "Do NOT answer current IPL facts from model training memory. "
             + "Inform the user: live data is temporarily unavailable — please try again shortly.\n";
    }

    private String extractTeamName(String query) {
        String q = query.toLowerCase();
        // Check abbreviations and full names
        if (containsAny(q, "kkr", "kolkata")) return "kolkata";
        if (containsAny(q, " mi ", "mumbai indians", "mumbai")) return "mumbai";
        if (containsAny(q, "csk", "chennai")) return "chennai";
        if (containsAny(q, "rcb", "royal challengers", "bangalore", "bengaluru")) return "royal challengers";
        if (containsAny(q, " rr ", "rajasthan royals", "rajasthan")) return "rajasthan";
        if (containsAny(q, "srh", "sunrisers", "hyderabad")) return "sunrisers";
        if (containsAny(q, "pbks", "punjab kings", "punjab")) return "punjab";
        if (containsAny(q, "lsg", "lucknow super giants", "lucknow")) return "lucknow";
        if (containsAny(q, " gt ", "gujarat titans", "gujarat")) return "gujarat";
        if (containsAny(q, " dc ", "delhi capitals", "delhi")) return "delhi";
        return null; // No specific team detected — fetch will return multiple teams
    }

    private String extractPlayerName(String query) {
        // Look for consecutive capitalized words (likely a player name)
        Matcher m = Pattern.compile("\\b([A-Z][a-z]+(?:\\s[A-Z][a-z]+)+)\\b").matcher(query);
        List<String> candidates = new ArrayList<>();
        while (m.find()) {
            String candidate = m.group(1);
            // Skip obvious non-player words
            if (!containsAny(candidate.toLowerCase(), "what", "how", "who", "when", "where",
                    "the ", "ipl", "current", "latest", "recent", "best", "analyze")) {
                candidates.add(candidate);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private boolean matchesTeam(String searchTerm, String teamTitle) {
        if (teamTitle == null) return false;
        String tl = teamTitle.toLowerCase();
        String st = searchTerm.toLowerCase();
        return tl.contains(st) || st.contains(tl) || isAbbreviationFor(st, tl);
    }

    private boolean isAbbreviationFor(String abbrev, String fullName) {
        Map<String, List<String>> abbrevMap = Map.of(
            "kkr",  List.of("kolkata"),
            "mi",   List.of("mumbai"),
            "csk",  List.of("chennai"),
            "rcb",  List.of("royal challengers", "bangalore", "bengaluru"),
            "rr",   List.of("rajasthan"),
            "srh",  List.of("sunrisers", "hyderabad"),
            "pbks", List.of("punjab"),
            "lsg",  List.of("lucknow"),
            "gt",   List.of("gujarat"),
            "dc",   List.of("delhi")
        );
        List<String> synonyms = abbrevMap.get(abbrev);
        if (synonyms == null) return false;
        return synonyms.stream().anyMatch(fullName::contains);
    }
}
