package in.xammer.aws_cost_api.dto.gemini;

import java.util.List;

// Represents feedback on the prompt itself
public record PromptFeedback(List<SafetyRating> safetyRatings) {}