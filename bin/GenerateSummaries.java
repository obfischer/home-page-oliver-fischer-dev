///usr/bin/env java --source 25 "$0" "$@" ; exit $?

/*
 * Generate blog post summaries using local Ollama LLM.
 *
 * This script scans Hugo blog posts in AsciiDoc format and generates
 * German summaries using a local Ollama installation.
 *
 * Usage:
 *   ./GenerateSummaries.java [options]
 *   java GenerateSummaries.java [options]
 */

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GenerateSummaries {

    private static final String DEFAULT_MODEL = "llama3:latest";
    private static final String DEFAULT_API_URL = "http://localhost:11434";
    private static final int DEFAULT_MAX_LENGTH = 250;

    private static boolean verbose = false;

    public static void main(String[] args) {
        var options = parseArguments(args);

        if (options.verbose) {
            verbose = true;
        }

        // Initialize Ollama client
        var ollama = new OllamaClient(options.model, DEFAULT_API_URL);

        // Check connection
        log("Prüfe Ollama-Verbindung...");
        if (!ollama.checkConnection()) {
            System.exit(1);
        }

        log("✓ Ollama ist erreichbar (Modell: %s)".formatted(options.model));

        if (options.dryRun) {
            log("✓ Dry-run Modus aktiviert (keine Änderungen werden geschrieben)");
        }

        // Discover posts
        List<Path> posts;
        if (options.target != null) {
            if (!Files.exists(options.target)) {
                error("Fehler: Datei nicht gefunden: " + options.target);
                System.exit(1);
            }
            posts = List.of(options.target);
            log("\nVerarbeite spezifischen Post: " + options.target);
        } else {
            Path contentDir = Path.of("content/posts");
            if (!Files.exists(contentDir)) {
                error("Fehler: Verzeichnis nicht gefunden: " + contentDir);
                System.exit(1);
            }

            log("\nSuche nach Blogposts in %s...".formatted(contentDir));
            var allPosts = discoverPosts(contentDir);
            log("Gefunden: %d Posts".formatted(allPosts.size()));

            // Filter posts
            posts = filterPosts(allPosts, options.force);
            log("Zu verarbeiten: %d Posts".formatted(posts.size()));
        }

        if (posts.isEmpty()) {
            log("\nKeine Posts zu verarbeiten.");
            System.exit(0);
        }

        // Process posts
        log("\n" + "=".repeat(60));
        log("Verarbeite Posts:");
        log("=".repeat(60));

        var results = new ArrayList<ProcessResult>();
        for (int i = 0; i < posts.size(); i++) {
            var post = posts.get(i);
            log("\n[%d/%d] %s".formatted(i + 1, posts.size(), post.getParent().getFileName()));

            var result = processPost(post, ollama, options.dryRun);
            results.add(result);

            if (result.success) {
                String summaryDisplay = result.summary.length() > 80
                    ? result.summary.substring(0, 80) + "..."
                    : result.summary;
                log("  ✓ Zusammenfassung: \"%s\"".formatted(summaryDisplay));
            } else if (result.skipped) {
                log("  ⊘ Übersprungen: %s".formatted(result.error != null ? result.error : "Unbekannter Grund"));
            } else {
                error("  ✗ Fehler: %s".formatted(result.error != null ? result.error : "Unbekannter Fehler"));
            }
        }

        // Print summary
        printReport(results);

        if (options.dryRun) {
            log("\n✓ Dry-run abgeschlossen. Keine Dateien wurden geändert.");
        } else {
            log("\n✓ Verarbeitung abgeschlossen.");
        }
    }

    // ==================== Command Line Parsing ====================

    record Options(
        String model,
        boolean force,
        boolean dryRun,
        Path target,
        boolean verbose
    ) {}

    private static Options parseArguments(String[] args) {
        String model = DEFAULT_MODEL;
        boolean force = false;
        boolean dryRun = false;
        Path target = null;
        boolean verboseMode = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--model" -> {
                    if (i + 1 < args.length) {
                        model = args[++i];
                    } else {
                        error("--model requires an argument");
                        System.exit(1);
                    }
                }
                case "--force" -> force = true;
                case "--dry-run" -> dryRun = true;
                case "--target" -> {
                    if (i + 1 < args.length) {
                        target = Path.of(args[++i]);
                        i++;
                    } else {
                        error("--target requires an argument");
                        System.exit(1);
                    }
                }
                case "--verbose", "-v" -> verboseMode = true;
                case "--help", "-h" -> {
                    printHelp();
                    System.exit(0);
                }
                default -> {
                    error("Unknown option: " + args[i]);
                    System.exit(1);
                }
            }
        }

        return new Options(model, force, dryRun, target, verboseMode);
    }

    private static void printHelp() {
        System.out.println("""
            Verwendung: GenerateSummaries.java [OPTIONEN]

            Generiere Blog-Zusammenfassungen mit Ollama

            Optionen:
              --model MODEL      Ollama Modell (default: llama3:latest)
              --force           Vorhandene Zusammenfassungen überschreiben
              --dry-run         Vorschau ohne Schreiben
              --target DATEI    Spezifischen Post verarbeiten
              --verbose, -v     Detaillierte Ausgabe
              --help, -h        Diese Hilfe anzeigen

            Beispiele:
              ./GenerateSummaries.java                    # Alle Posts ohne Zusammenfassung
              ./GenerateSummaries.java --dry-run          # Vorschau
              ./GenerateSummaries.java --force            # Alle neu generieren
              ./GenerateSummaries.java --target posts/2026/my-post/index.adoc
            """);
    }

    // ==================== Ollama Client ====================

    static class OllamaClient {
        private final String model;
        private final String apiUrl;
        private final HttpClient httpClient;

        OllamaClient(String model, String apiUrl) {
            this.model = model;
            this.apiUrl = apiUrl;
            this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        }

        boolean checkConnection() {
            try {
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;

            } catch (Exception e) {
                error("Ollama ist nicht erreichbar unter " + apiUrl);
                error("Fehler: " + e.getMessage());
                error("\nBitte stellen Sie sicher, dass Ollama läuft:");
                error("  ollama serve");
                return false;
            }
        }

        String generateSummary(String content) {
            var prompt = buildPrompt(content, DEFAULT_MAX_LENGTH);

            var requestBody = """
                {
                    "model": "%s",
                    "prompt": %s,
                    "stream": false,
                    "options": {
                        "temperature": 0.7,
                        "top_p": 0.9
                    }
                }
                """.formatted(model, escapeJson(prompt));

            try {
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/generate"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    error("API-Fehler: HTTP " + response.statusCode());
                    return null;
                }

                // Parse JSON response (simple parsing)
                var body = response.body();
                var responseMatcher = Pattern.compile("\"response\"\\s*:\\s*\"([^\"]+)\"").matcher(body);

                if (responseMatcher.find()) {
                    var summary = unescapeJson(responseMatcher.group(1));
                    return cleanSummary(summary);
                }

                error("Konnte 'response' nicht in API-Antwort finden");
                return null;

            } catch (Exception e) {
                error("Fehler bei der API-Anfrage: " + e.getMessage());
                return null;
            }
        }

        private String buildPrompt(String content, int maxLength) {
            var contentPreview = content.length() > 2000
                ? content.substring(0, 2000)
                : content;

            return """
                Du bist ein professioneller Texter für Blog-Zusammenfassungen.

                Aufgabe: Erstelle eine prägnante Zusammenfassung für den folgenden Blog-Artikel.

                Anforderungen:
                - Länge: 2-3 Sätze (maximal %d Zeichen)
                - Sprache: Deutsch
                - Stil: Professionell und einladend
                - Fokus: Kernaussage und Mehrwert für den Leser
                - Keine Metainformationen (kein "Dieser Artikel...", "In diesem Post...")
                - Antworte NUR mit der Zusammenfassung, keine Erklärungen

                Artikel:
                %s

                Zusammenfassung:""".formatted(maxLength, contentPreview);
        }

        private String cleanSummary(String summary) {
            summary = summary.trim();

            // Remove common prefixes
            var prefixes = List.of(
                "Zusammenfassung:",
                "Summary:",
                "Hier ist die Zusammenfassung:",
                "Die Zusammenfassung lautet:"
            );

            for (var prefix : prefixes) {
                if (summary.startsWith(prefix)) {
                    summary = summary.substring(prefix.length()).trim();
                }
            }

            // Remove quotes if present
            if (summary.startsWith("\"") && summary.endsWith("\"")) {
                summary = summary.substring(1, summary.length() - 1);
            }

            return summary;
        }

        private String escapeJson(String text) {
            return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
        }

        private String unescapeJson(String text) {
            return text
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
        }
    }

    // ==================== Content Extractor ====================

    static class ContentExtractor {
        static String extractContent(Path filePath) throws IOException {
            var content = Files.readString(filePath);
            var body = extractBody(content);
            return cleanAsciidoc(body);
        }

        private static String extractBody(String content) {
            // Check for YAML frontmatter (---)
            var yamlPattern = Pattern.compile("^---\\n(.*?)\\n---\\n(.*)$", Pattern.DOTALL);
            var yamlMatcher = yamlPattern.matcher(content);
            if (yamlMatcher.matches()) {
                return yamlMatcher.group(2);
            }

            // Check for TOML frontmatter (+++)
            var tomlPattern = Pattern.compile("^\\+\\+\\+\\n(.*?)\\n\\+\\+\\+\\n(.*)$", Pattern.DOTALL);
            var tomlMatcher = tomlPattern.matcher(content);
            if (tomlMatcher.matches()) {
                return tomlMatcher.group(2);
            }

            return content;
        }

        private static String cleanAsciidoc(String text) {
            // Remove AsciiDoc directives (:stem:, etc.)
            text = text.replaceAll("(?m)^:[^:\\n]+:.*$", "");

            // Remove comments
            text = text.replaceAll("(?m)^//.*$", "");

            // Remove image blocks
            text = text.replaceAll("(?m)^\\..+\\nimage:[^\\[]+\\[\\]", "");

            // Remove stem formulas
            text = text.replaceAll("stem:\\[[^\\]]*\\]", "");

            // Remove section markers
            text = text.replaceAll("(?m)^=+\\s+", "");

            // Remove bold/italic markers
            text = text.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
            text = text.replaceAll("\\*([^*]+)\\*", "$1");
            text = text.replaceAll("__([^_]+)__", "$1");
            text = text.replaceAll("_([^_]+)_", "$1");

            // Remove multiple blank lines
            text = text.replaceAll("\\n\\s*\\n\\s*\\n", "\n\n");

            return text.strip();
        }
    }

    // ==================== Frontmatter Parser ====================

    static class FrontmatterParser {
        private final Path filePath;
        private final String rawContent;
        private final FrontmatterFormat format;
        private final String frontmatterText;
        private final String body;

        enum FrontmatterFormat {
            YAML, TOML
        }

        FrontmatterParser(Path filePath) throws IOException {
            this.filePath = filePath;
            this.rawContent = Files.readString(filePath);
            this.format = detectFormat();

            var parsed = parse();
            this.frontmatterText = parsed[0];
            this.body = parsed[1];
        }

        private FrontmatterFormat detectFormat() {
            if (rawContent.startsWith("---\n")) {
                return FrontmatterFormat.YAML;
            } else if (rawContent.startsWith("+++\n")) {
                return FrontmatterFormat.TOML;
            } else {
                throw new IllegalArgumentException("Kein gültiges Frontmatter in " + filePath);
            }
        }

        private String[] parse() {
            Pattern pattern;
            if (format == FrontmatterFormat.YAML) {
                pattern = Pattern.compile("^(---\\n.*?\\n---)\\n(.*)$", Pattern.DOTALL);
            } else {
                pattern = Pattern.compile("^(\\+\\+\\+\\n.*?\\n\\+\\+\\+)\\n(.*)$", Pattern.DOTALL);
            }

            var matcher = pattern.matcher(rawContent);
            if (matcher.matches()) {
                return new String[]{matcher.group(1), matcher.group(2)};
            } else {
                throw new IllegalArgumentException("Konnte Frontmatter nicht parsen in " + filePath);
            }
        }

        boolean hasSummary() {
            if (format == FrontmatterFormat.YAML) {
                // Check for summary: "something" or summary: 'something'
                var matcher = Pattern.compile("(?m)^summary:\\s*[\"'](.+)[\"']").matcher(frontmatterText);
                if (matcher.find() && !matcher.group(1).trim().isEmpty()) {
                    return true;
                }

                // Check for summary: something (without quotes, non-empty)
                matcher = Pattern.compile("(?m)^summary:\\s*(.+)$").matcher(frontmatterText);
                if (matcher.find()) {
                    var value = matcher.group(1).trim();
                    if (!value.isEmpty() && !value.equals("\"\"") && !value.equals("''")) {
                        return true;
                    }
                }
            } else {
                // TOML format
                var matcher = Pattern.compile("(?m)^summary\\s*=\\s*[\"'](.+)[\"']").matcher(frontmatterText);
                if (matcher.find() && !matcher.group(1).trim().isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        String updateSummary(String summary) {
            var newFrontmatter = format == FrontmatterFormat.YAML
                ? updateYamlSummary(frontmatterText, summary)
                : updateTomlSummary(frontmatterText, summary);

            return newFrontmatter + "\n" + body;
        }

        private String updateYamlSummary(String frontmatter, String summary) {
            var summaryEscaped = summary.replace("\"", "\\\"");

            // Check if summary field exists
            if (Pattern.compile("(?m)^summary:").matcher(frontmatter).find()) {
                // Replace existing summary
                return frontmatter.replaceAll(
                    "(?m)^summary:.*$",
                    "summary: \"" + summaryEscaped + "\""
                );
            } else {
                // Add summary field after description if it exists
                if (Pattern.compile("(?m)^description:").matcher(frontmatter).find()) {
                    return frontmatter.replaceFirst(
                        "(?m)^(description:.*)$",
                        "$1\nsummary: \"" + summaryEscaped + "\""
                    );
                } else {
                    // Add after title
                    return frontmatter.replaceFirst(
                        "(?m)^(title:.*)$",
                        "$1\nsummary: \"" + summaryEscaped + "\""
                    );
                }
            }
        }

        private String updateTomlSummary(String frontmatter, String summary) {
            var summaryEscaped = summary.replace("\"", "\\\"");

            // Check if summary field exists
            if (Pattern.compile("(?m)^summary\\s*=").matcher(frontmatter).find()) {
                // Replace existing summary
                return frontmatter.replaceAll(
                    "(?m)^summary\\s*=.*$",
                    "summary = \"" + summaryEscaped + "\""
                );
            } else {
                // Add summary field after title
                if (Pattern.compile("(?m)^title\\s*=").matcher(frontmatter).find()) {
                    return frontmatter.replaceFirst(
                        "(?m)^(title\\s*=.*)$",
                        "$1\nsummary = \"" + summaryEscaped + "\""
                    );
                } else {
                    // Add at the beginning after +++
                    return frontmatter.replaceFirst(
                        "\\+\\+\\+\\n",
                        "+++\nsummary = \"" + summaryEscaped + "\"\n"
                    );
                }
            }
        }

        void writeBack(String newContent, boolean dryRun) throws IOException {
            if (dryRun) {
                debug("  [Dry-run] Würde schreiben in: " + filePath);
                return;
            }

            // Create backup
            var backupPath = Path.of(filePath.toString() + ".bak");
            Files.copy(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            debug("  Backup erstellt: " + backupPath);

            // Write new content
            Files.writeString(filePath, newContent);
            debug("  Datei aktualisiert: " + filePath);
        }
    }

    // ==================== Post Discovery & Processing ====================

    private static List<Path> discoverPosts(Path contentDir) {
        try (Stream<Path> paths = Files.walk(contentDir)) {
            return paths
                .filter(p -> p.getFileName().toString().equals("index.adoc"))
                .sorted()
                .collect(Collectors.toList());
        } catch (IOException e) {
            error("Fehler beim Durchsuchen von " + contentDir + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<Path> filterPosts(List<Path> posts, boolean force) {
        var filtered = new ArrayList<Path>();

        for (var post : posts) {
            try {
                var parser = new FrontmatterParser(post);

                if (!force && parser.hasSummary()) {
                    debug("  Überspringe (hat bereits Zusammenfassung): " + post);
                    continue;
                }

                filtered.add(post);

            } catch (Exception e) {
                System.err.println("  Warnung: Konnte " + post + " nicht parsen: " + e.getMessage());
            }
        }

        return filtered;
    }

    record ProcessResult(
        Path path,
        boolean success,
        String summary,
        String error,
        boolean skipped
    ) {}

    private static ProcessResult processPost(Path postPath, OllamaClient ollama, boolean dryRun) {
        try {
            // Extract content
            var content = ContentExtractor.extractContent(postPath);

            if (content.isBlank()) {
                System.err.println("  Warnung: Kein Inhalt gefunden in " + postPath);
                return new ProcessResult(postPath, false, null, "Kein Inhalt", true);
            }

            // Generate summary
            if (verbose) {
                log("  Generiere Zusammenfassung für: " + postPath.getParent().getFileName());
            }

            var summary = ollama.generateSummary(content);

            if (summary == null) {
                error("  Fehler: Konnte keine Zusammenfassung generieren für " + postPath);
                return new ProcessResult(postPath, false, null, "Generierung fehlgeschlagen", false);
            }

            // Update frontmatter
            var parser = new FrontmatterParser(postPath);
            var newContent = parser.updateSummary(summary);
            parser.writeBack(newContent, dryRun);

            return new ProcessResult(postPath, true, summary, null, false);

        } catch (Exception e) {
            error("  Fehler beim Verarbeiten von " + postPath + ": " + e.getMessage());
            return new ProcessResult(postPath, false, null, e.getMessage(), false);
        }
    }

    private static void printReport(List<ProcessResult> results) {
        var total = results.size();
        var succeeded = results.stream().filter(r -> r.success).count();
        var failed = results.stream().filter(r -> r.error != null && !r.skipped).count();
        var skipped = results.stream().filter(r -> r.skipped).count();

        log("\n" + "=".repeat(60));
        log("Zusammenfassung:");
        log("=".repeat(60));
        log("  Verarbeitet: " + total);
        log("  Erfolgreich: " + succeeded);
        log("  Übersprungen: " + skipped);
        log("  Fehlgeschlagen: " + failed);

        if (failed > 0) {
            log("\nFehlgeschlagene Posts:");
            results.stream()
                .filter(r -> r.error != null && !r.skipped)
                .forEach(r -> log("  - %s: %s".formatted(r.path, r.error)));
        }
    }

    // ==================== Logging ====================

    private static void log(String message) {
        System.out.println(message);
    }

    private static void error(String message) {
        System.err.println(message);
    }

    private static void debug(String message) {
        if (verbose) {
            System.out.println(message);
        }
    }
}
