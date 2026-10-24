package com.spendsense.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void formatsValuesAsPgvectorLiteral() throws Exception {
        assertEquals("[0.1,-0.2,0.3]",
                EmbeddingClient.toVectorLiteral(mapper.readTree("[0.1,-0.2,0.3]"), 3));
    }

    @Test
    void rejectsWrongDimensionCount() throws Exception {
        // The model returns 3072 values unless outputDimensionality is sent, and the column
        // is vector(768); failing here beats writing an unusable embedding.
        assertThrows(IllegalStateException.class,
                () -> EmbeddingClient.toVectorLiteral(mapper.readTree("[0.1,0.2]"), 768));
    }
}
