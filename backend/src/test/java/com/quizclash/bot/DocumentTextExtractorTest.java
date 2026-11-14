package com.quizclash.bot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTextExtractorTest {

    @Test
    void shortDocumentIsSentWhole() {
        String text = "Photosynthesis happens in chloroplasts. ".repeat(50);
        assertThat(DocumentTextExtractor.pick(text)).containsExactly(text);
    }

    @Test
    void longDocumentIsSampledFromStartToEnd() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("Section ").append(i).append(". ").append("Some sentence about the topic. ".repeat(60)).append("\n\n");
        }
        List<String> parts = DocumentTextExtractor.pick(sb.toString());

        assertThat(parts).hasSize(DocumentTextExtractor.MAX_CHUNKS);
        assertThat(parts.getFirst()).startsWith("Section 0.");
        assertThat(parts.getLast()).contains("Section 39.");
        assertThat(parts).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(DocumentTextExtractor.CHUNK_CHARS));
    }

    @Test
    void chunksCoverAllText() {
        String text = "One sentence here. ".repeat(2_000);
        String joined = String.join("", DocumentTextExtractor.chunk(text)).replace(" ", "");
        assertThat(joined).isEqualTo(text.replace(" ", ""));
    }

    @Test
    void normalizeCollapsesLayoutWhitespace() {
        assertThat(DocumentTextExtractor.normalize("a  \t b\n\n\n\n c "))
                .isEqualTo("a b\n\n c");
    }
}
