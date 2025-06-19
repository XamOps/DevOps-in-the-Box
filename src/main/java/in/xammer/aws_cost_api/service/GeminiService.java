package in.xammer.aws_cost_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import in.xammer.aws_cost_api.dto.ServiceCost;
import in.xammer.aws_cost_api.dto.gemini.Content;
import in.xammer.aws_cost_api.dto.gemini.GeminiRequest;
import in.xammer.aws_cost_api.dto.gemini.GeminiResponse;
import in.xammer.aws_cost_api.dto.gemini.Part;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // A record to define a composite key for the cache
    private record AccountDataCacheKey(String accountName, String accountId, List<ServiceCost> services) {}

    // A Caffeine cache to store suggestions and avoid repeated API calls
    private final Cache<AccountDataCacheKey, String> suggestionCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(1000)
            .build();

    public GeminiService(ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = objectMapper;
    }

    /**
     * Gets optimization suggestions for a given account.
     * Results are cached to avoid redundant API calls.
     */
    public String getOptimizationSuggestions(String accountName, String accountId, List<ServiceCost> services) {
        AccountDataCacheKey key = new AccountDataCacheKey(accountName, accountId, services);
        // Get from cache or call fetchSuggestions if not present
        return suggestionCache.get(key, k -> fetchSuggestions(k.accountName, k.accountId, k.services));
    }

    /**
     * Fetches suggestions from the Gemini API. This is called by the caching layer when a result is not in the cache.
     */
    private String fetchSuggestions(String accountName, String accountId, List<ServiceCost> services) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equalsIgnoreCase("YOUR_GEMINI_API_KEY_HERE")) {
            System.err.println("Gemini API Key is not configured. Please set gemini.api.key in application.properties.");
            return "Error: Gemini API Key not configured on the server. Please contact support.";
        }

        // Build the prompt using the dedicated helper method
        String prompt = buildOptimizationPrompt(accountName, accountId, services);
        System.out.println("Gemini Prompt for account " + accountId + ":\n" + prompt);

        try {
            GeminiRequest geminiRequest = new GeminiRequest(List.of(new Content(List.of(new Part(prompt)))));
            String requestBody = objectMapper.writeValueAsString(geminiRequest);

            // URL Encode the API Key to handle special characters
            String encodedApiKey = URLEncoder.encode(apiKey.trim(), StandardCharsets.UTF_8);
            String fullApiUrl = apiUrl + "?key=" + encodedApiKey;
            System.out.println("Constructed Gemini API URL: " + fullApiUrl); // For debugging

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fullApiUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                GeminiResponse geminiResponse = objectMapper.readValue(response.body(), GeminiResponse.class);
                if (geminiResponse != null && geminiResponse.candidates() != null && !geminiResponse.candidates().isEmpty() &&
                        geminiResponse.candidates().get(0).content() != null &&
                        geminiResponse.candidates().get(0).content().parts() != null &&
                        !geminiResponse.candidates().get(0).content().parts().isEmpty()) {
                    return geminiResponse.candidates().get(0).content().parts().get(0).text();
                } else {
                    System.err.println("Gemini response structure unexpected: " + response.body());
                    return "Could not extract suggestions from Gemini response. The structure was not as expected.";
                }
            } else {
                System.err.println("Gemini API Error - Status: " + response.statusCode() + ", Body: " + response.body());
                return "Error fetching suggestions from Gemini (Status: " + response.statusCode() + "). Please check server logs.";
            }
        } catch (Exception e) {
            System.err.println("Exception calling Gemini API: " + e.getMessage());
            e.printStackTrace();
            return "Error calling Gemini API: " + e.getMessage();
        }
    }

    /**
     * Builds a structured prompt for the Gemini API to request cost optimization suggestions.
     */
    private String buildOptimizationPrompt(String accountName, String accountId, List<ServiceCost> services) {
        final BigDecimal MIN_SERVICE_COST = new BigDecimal("10.00");

        // Filter for services with a cost greater than the minimum threshold
        List<ServiceCost> significantServices = services.stream()
                .filter(sc -> sc.cost() != null && sc.cost().compareTo(MIN_SERVICE_COST) > 0)
                .sorted(Comparator.comparing(ServiceCost::cost).reversed())
                .limit(5) // Focus on the top 5 most expensive services
                .toList();

        StringBuilder prompt = new StringBuilder("You are an AWS cost optimization expert. Provide 3-5 concrete suggestions for account: ");
        prompt.append(accountName).append(" (").append(accountId).append(").\n\n");

        if (significantServices.isEmpty()) {
            prompt.append("There are no services with costs over $10.00 in the selected period. Provide general AWS cost-saving best practices.");
        } else {
            prompt.append("Top services by cost (over $10):\n");
            significantServices.forEach(s ->
                    prompt.append("- ").append(s.name())
                            .append(": $").append(s.cost().setScale(2, RoundingMode.HALF_UP))
                            .append("\n"));
        }

        prompt.append("\nSuggestions should:\n");
        prompt.append("1. Be actionable and specific.\n");
        prompt.append("2. Focus on the highest cost services first (if available).\n");
        prompt.append("3. Reference specific AWS service features or tools (e.g., Savings Plans, S3 Intelligent-Tiering, Graviton instances).\n");
        prompt.append("4. If possible, give a rough estimate of potential savings (e.g., percentage).\n");
        prompt.append("5. Be formatted as a bulleted or numbered list.\n");

        return prompt.toString();
    }
}