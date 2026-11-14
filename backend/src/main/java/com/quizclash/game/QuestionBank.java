package com.quizclash.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizclash.game.model.Question;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Seed questions loaded from {@code classpath:questions.json}. */
@Component
public class QuestionBank {

    private static final Logger log = LoggerFactory.getLogger(QuestionBank.class);

    private final List<Question> all;

    public QuestionBank(ObjectMapper json) throws IOException {
        try (InputStream in = new ClassPathResource("questions.json").getInputStream()) {
            all = List.copyOf(json.readValue(in, new TypeReference<List<Question>>() {}));
        }
        log.info("Loaded {} seed questions", all.size());
    }

    /** A random selection of {@code n} questions for a new room. */
    public List<Question> randomSet(int n) {
        List<Question> copy = new ArrayList<>(all);
        Collections.shuffle(copy);
        return copy.subList(0, Math.min(n, copy.size()));
    }
}
