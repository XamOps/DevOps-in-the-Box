package in.xammer.aws_cost_api.dto.gemini;

import java.util.List;

// Represents the overall request payload for Gemini
public record GeminiRequest(List<Content> contents) {}