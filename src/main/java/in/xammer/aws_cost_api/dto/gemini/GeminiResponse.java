package in.xammer.aws_cost_api.dto.gemini;

import java.util.List;

// Represents the top-level response from Gemini
public record GeminiResponse(List<Candidate> candidates, PromptFeedback promptFeedback) {}