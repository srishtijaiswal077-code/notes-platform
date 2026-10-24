package com.notesplatform.notes_platform;

import tools.jackson.databind.JsonNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.io.ByteArrayInputStream;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class SummaryService {

    private final RestClient client = RestClient.builder()
            .requestFactory(timeoutFactory())
            .build();

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(30000);
        return f;
    }

    public String extractText(byte[] fileBytes, String fileName) throws IOException {
        String name = fileName == null ? "" : fileName.toLowerCase();
        String text;
        if (name.endsWith(".docx")) {
            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(fileBytes));
                 XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
                text = extractor.getText();
            }
        } else {
            try (PDDocument doc = Loader.loadPDF(fileBytes)) {
                text = new PDFTextStripper().getText(doc);
            }
        }
        return text.length() > 20000 ? text.substring(0, 20000) : text;
    }

    public String summarize(String text) {
        String prompt = "Summarize these college notes in 8-10 clear bullet points. "
                + "Highlight key topics and important definitions. "
                + "Use simple English. "
                + "Output plain text only: no markdown, no ** or # symbols, no introduction line. "
                + "Start each point with the bullet character •.\n\nNOTES:\n" + text;

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
        );

        JsonNode response = client.post()
                .uri("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        return response.path("candidates").path(0)
                .path("content").path("parts").path(0)
                .path("text").asText();
    }
}
