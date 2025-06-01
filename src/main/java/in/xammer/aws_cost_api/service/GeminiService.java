package in.xammer.aws_cost_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.xammer.aws_cost_api.dto.ServiceCost;
import in.xammer.aws_cost_api.dto.gemini.Content;
import in.xammer.aws_cost_api.dto.gemini.GeminiRequest;
import in.xammer.aws_cost_api.dto.gemini.GeminiResponse;
import in.xammer.aws_cost_api.dto.gemini.Part;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder; // Import URLEncoder
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets; // Import StandardCharsets
import java.util.List;
import java.util.stream.Collectors;

@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GeminiService(ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = objectMapper;
    }

    public String getOptimizationSuggestions(String accountName, String accountId, List<ServiceCost> services) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equalsIgnoreCase("YOUR_GEMINI_API_KEY_HERE")) {
            System.err.println("Gemini API Key is not configured. Please set gemini.api.key in application.properties.");
            return "Error: Gemini API Key not configured on the server. Please contact support.";
        }

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("You are an AWS cost optimization expert. ");
        promptBuilder.append("For AWS account '").append(accountName).append("' (ID: ").append(accountId).append("), ");
        promptBuilder.append("the following services incurred costs in the selected period (USD):\n");

        List<ServiceCost> servicesWithCost = services.stream()
            .filter(service -> service.cost() != null && service.cost().compareTo(BigDecimal.ZERO) > 0)
            .collect(Collectors.toList());

        if (servicesWithCost.isEmpty()) {
            return "No significant costs reported for this account in the selected period. No specific optimization tips to provide based on current data.";
        }

        servicesWithCost.forEach(service -> promptBuilder.append("- ")
                .append(service.name())
                .append(": $")
                .append(service.cost().toPlainString())
                .append("\n"));
        
        promptBuilder.append("\nPlease provide 3-5 concise, actionable cost optimization suggestions tailored to these services. ");
        promptBuilder.append("Focus on practical advice. Format the suggestions as a bulleted list (e.g., using '*' or '-'). Do not include a preamble or a closing statement, just the bullet points.");

        String prompt = promptBuilder.toString();
        System.out.println("Gemini Prompt for account " + accountId + ":\n" + prompt);

        try {
            GeminiRequest geminiRequest = new GeminiRequest(List.of(new Content(List.of(new Part(prompt)))));
            String requestBody = objectMapper.writeValueAsString(geminiRequest);

            // --- MODIFICATION: URL Encode the API Key ---
            String encodedApiKey = URLEncoder.encode(apiKey.trim(), StandardCharsets.UTF_8);
            String fullApiUrl = apiUrl + "?key=" + encodedApiKey;
            System.out.println("Constructed Gemini API URL: " + fullApiUrl); // For debugging

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullApiUrl)) // Use the URL with the encoded key
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
}