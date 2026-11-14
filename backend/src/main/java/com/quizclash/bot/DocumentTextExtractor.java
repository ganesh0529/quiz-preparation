package com.quizclash.bot;

import com.quizclash.game.GameException;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns an uploaded file (PDF, Word, PowerPoint, text, ...) into plain text
 * with Spring AI's Tika document reader, then picks the excerpts that are
 * sent to the model.
 */
@Component
public class DocumentTextExtractor {

    /** Documents up to this size are sent whole. */
    static final int WHOLE_DOCUMENT_CHARS = 24_000;
    /** Longer documents are cut into chunks of about this size... */
    static final int CHUNK_CHARS = 5_000;
    /** ...and this many chunks, spread across the document, are sent. */
    static final int MAX_CHUNKS = 4;
    /** Fewer characters than this means there is nothing to quiz on (often a scanned PDF). */
    static final int MIN_CHARS = 200;

    public record Excerpts(String fileName, int totalChars, List<String> parts) {}

    public Excerpts extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new GameException(HttpStatus.BAD_REQUEST, "Choose a file to upload");
        }
        String fileName = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();

        String text;
        try {
            byte[] bytes = file.getBytes();
            // Tika uses the file name as a hint to detect the format.
            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };
            text = new TikaDocumentReader(resource).get().stream()
                    .map(Document::getText)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.joining("\n\n"));
        } catch (IOException | RuntimeException e) {
            throw new GameException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not read " + fileName + ". Try a PDF, Word, PowerPoint or text file.");
        }

        text = normalize(text);
        if (text.length() < MIN_CHARS) {
            throw new GameException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not enough text found in " + fileName
                            + ". If it is a scanned PDF (pictures of pages), it has no text to read.");
        }
        return new Excerpts(fileName, text.length(), pick(text));
    }

    /** Collapse runs of spaces and blank lines left over from PDF layout. */
    static String normalize(String text) {
        return text.replace(' ', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n")
                .trim();
    }

    /**
     * Short documents: the whole text. Long documents: up to MAX_CHUNKS chunks
     * spread evenly from start to end, so questions cover the whole document
     * instead of only its first pages.
     */
    static List<String> pick(String text) {
        if (text.length() <= WHOLE_DOCUMENT_CHARS) return List.of(text);

        List<String> chunks = chunk(text);
        if (chunks.size() <= MAX_CHUNKS) return chunks;

        List<String> picked = new ArrayList<>();
        for (int i = 0; i < MAX_CHUNKS; i++) {
            int index = (int) Math.round(i * (chunks.size() - 1) / (double) (MAX_CHUNKS - 1));
            picked.add(chunks.get(index));
        }
        return picked;
    }

    /** Cut into ~CHUNK_CHARS pieces, preferring to break at a paragraph, then at a sentence. */
    static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_CHARS, text.length());
            if (end < text.length()) {
                int para = text.lastIndexOf("\n\n", end);
                int sentence = text.lastIndexOf(". ", end);
                if (para > start + CHUNK_CHARS / 2) end = para;
                else if (sentence > start + CHUNK_CHARS / 2) end = sentence + 1;
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) chunks.add(piece);
            start = end;
        }
        return chunks;
    }
}
