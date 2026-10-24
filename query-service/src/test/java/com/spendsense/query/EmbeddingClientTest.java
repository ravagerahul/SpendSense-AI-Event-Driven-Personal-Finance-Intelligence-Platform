package com.spendsense.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void formatsValuesAsPgvectorLiteral() throws Exception {
        assertEquals("[0.1,-0.2,0.3]",
                EmbeddingClient.toVectorLiteral(mapper.readTree("[0.1,-0.2,0.3]"), 3));
    }

    @Test
    void rejectsWrongDimensionCount() throws Exception {
        // Guards against forgetting outputDimensionality: the model returns 3072 by default
        // and the column is vector(768).
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EmbeddingClient.toVectorLiteral(mapper.readTree("[0.1,0.2]"), 768));
        assertTrue(e.getMessage().contains("768"));
    }

    @Test
    void rejectsMissingEmbedding() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> EmbeddingClient.toVectorLiteral(mapper.readTree("{}").path("values"), 768));
    }
}
