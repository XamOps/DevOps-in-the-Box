package in.xammer.aws_cost_api.dto.gemini;

import java.util.List;

// Represents a piece of content, which can have multiple parts (e.g., text)
public record Content(String role, List<Part> parts) {
    // Compact constructor for user role (used when sending a request)
    public Content(List<Part> parts) {
        this("user", parts);
    }
}