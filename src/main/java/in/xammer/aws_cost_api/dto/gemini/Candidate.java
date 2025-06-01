package in.xammer.aws_cost_api.dto.gemini;

import java.util.List;

// Represents a candidate response from the model
public record Candidate(Content content, String finishReason, Integer index, List<SafetyRating> safetyRatings) {}