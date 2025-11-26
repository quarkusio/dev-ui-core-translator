package io.quarkus.devui.core.translator;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.UUID;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "translate", mixinStandardHelpOptions = true, description = "Find and print English translations")
@Singleton
public class TranslateCommand implements Runnable {

    private static final Set<String> TRANSLATION_FILE_NAMES = Set.of("en.js", "en-US.js", "en-GB.js");
    private static final Pattern ENTRY_PATTERN = Pattern.compile("['\"]([^'\"]+)['\"]\\s*:\\s*['\"]([^'\"]*)['\"]");
    private static final Pattern STR_ENTRY_PATTERN = Pattern.compile("['\"]([^'\"]+)['\"]\\s*:\\s*str`([^`]*)`");
    private static final BufferedReader STDIN = new BufferedReader(new InputStreamReader(System.in));
    private static final Path MAIN_DEV_UI_I18N = Paths.get("extensions", "devui", "resources", "src", "main", "resources", "dev-ui", "i18n");

    @Parameters(paramLabel = "<root>", arity = "0..1", description = "Root directory to scan recursively")
    Path rootDirectory;

    @Option(names = {"-l", "--language"}, paramLabel = "<lang>",
            description = "Target language to translate values into")
    String targetLanguage;

    @Option(names = {"-c", "--countries"}, split = ",", paramLabel = "<country-codes>",
            description = "Optional comma separated country codes for dialect files (e.g. AT,CH,BE,LI,LU)")
    List<String> targetCountries = new ArrayList<>();

    @Inject
    TranslationAiService translationAiService;

    private UUID memoryId;
    
    @Override
    public void run() {
        memoryId = UUID.randomUUID();
        Path root = resolveRootDirectory();
        targetLanguage = resolveTargetLanguage();
        targetCountries = resolveTargetCountries();
        if (!Files.isDirectory(root)) {
            System.err.printf("Path %s is not a directory%n", root);
            return;
        }

        mergeAndPrintTranslations(root);
    }

    private Path resolveRootDirectory() {
        Path resolved = rootDirectory;
        while (resolved == null) {
            String input = prompt("Enter the path to the Quarkus source root: ");
            if (input != null && !input.isBlank()) {
                Path candidate = Paths.get(input.trim());
                if (Files.isDirectory(candidate)) {
                    resolved = candidate;
                } else {
                    System.err.printf("Path %s is not a directory. Please try again.%n", candidate);
                }
            } else {
                System.err.println("A source path is required.");
            }
        }
        return resolved;
    }

    private String resolveTargetLanguage() {
        String language = targetLanguage;
        while (language == null || language.isBlank()) {
            language = prompt("Which language are we translating to? ");
            if (language == null || language.isBlank()) {
                System.err.println("Please enter a language to translate to.");
            }
        }
        return language.trim();
    }

    private List<String> resolveTargetCountries() {
        if (targetCountries != null && !targetCountries.isEmpty()) {
            targetCountries = sanitizeCountryList(targetCountries);
            return targetCountries;
        }
        String input = prompt("Optional comma separated country codes (excluding the default): ");
        if (input == null || input.isBlank()) {
            targetCountries = new ArrayList<>();
        } else {
            targetCountries = sanitizeCountryList(List.of(input.split(",")));
        }
        return targetCountries;
    }

    private List<String> sanitizeCountryList(List<String> countries) {
        List<String> sanitized = new ArrayList<>();
        for (String country : countries) {
            String cleaned = sanitizeCountryCode(country);
            if (!cleaned.isEmpty() && !sanitized.contains(cleaned)) {
                sanitized.add(cleaned);
            }
        }
        return sanitized;
    }

    private String prompt(String message) {
        Console console = System.console();
        if (console != null) {
            return console.readLine(message);
        }
        System.out.print(message);
        try {
            return STDIN.readLine();
        } catch (IOException e) {
            System.err.printf("Failed to read input: %s%n", e.getMessage());
            return "";
        }
    }

    private boolean isTranslationFile(Path path) {
        return TRANSLATION_FILE_NAMES.contains(path.getFileName().toString());
    }

    private void mergeAndPrintTranslations(Path root) {
        Map<Path, Map<String, TranslationEntry>> mergedByI18nFolder = new LinkedHashMap<>();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isTranslationFile)
                    .forEach(file -> mergeFileIntoGroup(mergedByI18nFolder, file));
        } catch (IOException e) {
            System.err.printf("Failed to walk directory %s: %s%n", root, e.getMessage());
            return;
        }

        mergedByI18nFolder.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> printGroup(entry.getKey(), entry.getValue()));
    }

    private void mergeFileIntoGroup(Map<Path, Map<String, TranslationEntry>> mergedByI18nFolder, Path file) {
        Optional<Path> i18nFolder = findI18nFolder(file);
        if (i18nFolder.isEmpty()) {
            return;
        }

        Map<String, TranslationEntry> translations = mergedByI18nFolder.computeIfAbsent(i18nFolder.get(), k -> new LinkedHashMap<>());
        try {
            String content = Files.readString(file);
            Map<String, TranslationEntry> fileTranslations = parseTranslations(content);
            fileTranslations.forEach(translations::put); // later files overwrite earlier ones if duplicated
        } catch (IOException e) {
            System.err.printf("Failed to read %s: %s%n", file, e.getMessage());
        }
    }

    private void printGroup(Path i18nFolder, Map<String, TranslationEntry> translations) {
        System.out.printf("%n== %s ==%n", i18nFolder);
        String languageCode = deriveLanguageCode(targetLanguage);
        Path baseTargetFile = i18nFolder.resolve(languageCode + ".js");
        if (Files.exists(baseTargetFile)) {
            System.out.printf("Skipping %s because %s already exists%n", i18nFolder, baseTargetFile.getFileName());
            return;
        }

        Map<String, TranslationEntry> translatedEntries = new LinkedHashMap<>();
        translations.forEach((key, value) -> {
            String translated = translateValue(value.value(), targetLanguage);
            translatedEntries.put(key, new TranslationEntry(translated, value.template()));
            System.out.printf("%s = %s -> %s%n", key, value.value(), translated);
        });
        writeTranslationFile(i18nFolder, translatedEntries, languageCode);

        boolean isMainDevUi = i18nFolder.endsWith(MAIN_DEV_UI_I18N);
        Optional<String> defaultCountry = findDefaultCountryCode(languageCode)
                .map(this::sanitizeCountryCode)
                .filter(code -> !code.isEmpty());
        if (isMainDevUi) {
            defaultCountry.ifPresent(code -> writeTranslationFile(i18nFolder, Map.of(), languageCode + "-" + code, true));
        }

        for (String country : targetCountries) {
            String countryCode = sanitizeCountryCode(country);
            if (countryCode.isEmpty()) {
                continue;
            }
            if (defaultCountry.isPresent() && defaultCountry.get().equalsIgnoreCase(countryCode)) {
                continue;
            }
            String localeLabel = buildLocaleLabel(languageCode, countryCode);
            Map<String, TranslationEntry> dialectTranslations = new LinkedHashMap<>();
            translations.forEach((key, value) -> {
                String translated = translateValue(value.value(), localeLabel);
                dialectTranslations.put(key, new TranslationEntry(translated, value.template()));
            });
            Map<String, TranslationEntry> diff = diffTranslations(translatedEntries, dialectTranslations);
            writeTranslationFile(i18nFolder, diff, languageCode + "-" + countryCode, false);
        }
    }

    private Optional<Path> findI18nFolder(Path file) {
        Path current = file.toAbsolutePath().normalize();
        while (current != null) {
            if (current.getFileName() != null && "i18n".equals(current.getFileName().toString())) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private Map<String, TranslationEntry> parseTranslations(String content) {
        Map<String, TranslationEntry> translations = new LinkedHashMap<>();
        Matcher matcher = ENTRY_PATTERN.matcher(content);
        while (matcher.find()) {
            translations.put(matcher.group(1), new TranslationEntry(matcher.group(2), false));
        }
        Matcher strMatcher = STR_ENTRY_PATTERN.matcher(content);
        while (strMatcher.find()) {
            translations.put(strMatcher.group(1), new TranslationEntry(strMatcher.group(2), true));
        }
        return translations;
    }

    private String translateValue(String value, String targetLabel) {
        // Ensure request context is active for LangChain4j client
        ManagedContext requestContext = Arc.container().requestContext();
        boolean activatedHere = false;
        if (!requestContext.isActive()) {
            requestContext.activate();
            activatedHere = true;
        }
        if (translationAiService == null) {
            return "<translation service unavailable>";
        }
        try {
            return translationAiService.translate(memoryId.toString(), targetLabel, value);
        } catch (Exception e) {
            System.err.printf("Failed to translate \"%s\": %s%n", value, e.getMessage());
            return "<translation error>";
        } finally {
            if (activatedHere) {
                requestContext.terminate();
            }
        }
    }

    private void writeTranslationFile(Path i18nFolder, Map<String, TranslationEntry> translatedEntries, String fileStem) {
        writeTranslationFile(i18nFolder, translatedEntries, fileStem, false);
    }

    private void writeTranslationFile(Path i18nFolder, Map<String, TranslationEntry> translatedEntries, String fileStem, boolean allowEmpty) {
        if (translatedEntries.isEmpty() && !allowEmpty) {
            Path targetFile = i18nFolder.resolve(fileStem + ".js");
            System.out.printf("Skipping %s (no entries to write)%n", targetFile);
            return;
        }
        Path targetFile = i18nFolder.resolve(fileStem + ".js");
        StringBuilder builder = new StringBuilder();
        boolean needsStrImport = translatedEntries.values().stream().anyMatch(TranslationEntry::template);
        if (needsStrImport) {
            builder.append("import { str } from '@lit/localize';\n\n");
        }
        builder.append("export const templates = {\n");
        translatedEntries.forEach((key, value) -> {
            builder.append("    '").append(key).append("': ");
            if (value.template()) {
                builder.append("str`").append(escapeBackticks(normalizeTemplatePlaceholders(value.value()))).append("`");
            } else {
                builder.append("'").append(escapeSingleQuotes(value.value())).append("'");
            }
            builder.append(",\n");
        });
        builder.append("};\n");
        try {
            Files.writeString(targetFile, builder.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.printf("Written %s%n", targetFile);
        } catch (IOException e) {
            System.err.printf("Failed to write %s: %s%n", targetFile, e.getMessage());
        }
    }

    private String deriveLanguageCode(String languageName) {
        if (languageName == null || languageName.isBlank()) {
            return "translation";
        }
        // Try find a locale whose display language matches the provided name
        String nameLower = languageName.toLowerCase(Locale.ROOT);
        for (Locale locale : Locale.getAvailableLocales()) {
            if (locale.getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT).equals(nameLower)) {
                String code = locale.getLanguage();
                return code.isBlank() ? sanitizeToCode(languageName) : code;
            }
        }
        return sanitizeToCode(languageName);
    }

    private String sanitizeToCode(String languageName) {
        String cleaned = languageName.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (cleaned.length() > 3) {
            cleaned = cleaned.substring(0, 3);
        }
        return cleaned.isBlank() ? "translation" : cleaned;
    }

    private String sanitizeCountryCode(String country) {
        if (country == null) {
            return "";
        }
        String cleaned = country.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        if (cleaned.length() > 3) {
            cleaned = cleaned.substring(0, 3);
        }
        return cleaned;
    }

    private Optional<String> findDefaultCountryCode(String languageCode) {
        String languageNameLower = targetLanguage == null ? "" : targetLanguage.toLowerCase(Locale.ROOT);
        for (Locale locale : Locale.getAvailableLocales()) {
            if (locale.getCountry().isEmpty()) {
                continue;
            }
            if (locale.getLanguage().equalsIgnoreCase(languageCode)
                    || locale.getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT).equals(languageNameLower)) {
                return Optional.of(locale.getCountry());
            }
        }
        return Optional.empty();
    }

    private String buildLocaleLabel(String languageCode, String countryCode) {
        Locale locale = countryCode.isEmpty() ? new Locale(languageCode) : new Locale(languageCode, countryCode);
        String display = locale.getDisplayName(Locale.ENGLISH);
        return display.isBlank() ? languageCode + (countryCode.isEmpty() ? "" : "-" + countryCode) : display;
    }

    private Map<String, TranslationEntry> diffTranslations(Map<String, TranslationEntry> base, Map<String, TranslationEntry> variant) {
        Map<String, TranslationEntry> diff = new LinkedHashMap<>();
        variant.forEach((key, value) -> {
            TranslationEntry baseValue = base.get(key);
            if (baseValue == null || !baseValue.equals(value)) {
                diff.put(key, value);
            }
        });
        return diff;
    }

    private String escapeSingleQuotes(String value) {
        return value.replace("'", "\\'");
    }

    private String escapeBackticks(String value) {
        return value.replace("`", "\\`");
    }

    private String normalizeTemplatePlaceholders(String value) {
        // Convert occurrences like $0 to ${0} while leaving ${0} intact
        return value.replaceAll("\\$(?!\\{)(\\d+)", "\\${$1}");
    }

    private record TranslationEntry(String value, boolean template) {
    }
}