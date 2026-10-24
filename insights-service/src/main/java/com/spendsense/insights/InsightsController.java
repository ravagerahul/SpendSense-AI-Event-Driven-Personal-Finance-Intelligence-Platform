package com.spendsense.insights;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    private final InsightsGenerator generator;

    public InsightsController(InsightsGenerator generator) {
        this.generator = generator;
    }

    @GetMapping("/latest")
    public Map<String, Object> latest() throws Exception {
        return generator.latest();
    }

    /**
     * Runs the weekly job on demand. Exists so the summary can be exercised without waiting for
     * Sunday; it calls exactly the same method the scheduler does, so a green result here means
     * the scheduled path works too. Regenerating a period overwrites it rather than appending.
     */
    @PostMapping("/generate")
    public Map<String, Object> generate() throws Exception {
        return generator.generate();
    }
}
