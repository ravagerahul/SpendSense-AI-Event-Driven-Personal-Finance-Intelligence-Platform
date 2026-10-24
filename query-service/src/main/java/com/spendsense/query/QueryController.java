package com.spendsense.query;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final SpendQueryService service;

    public QueryController(SpendQueryService service) {
        this.service = service;
    }

    @PostMapping
    public Map<String, Object> ask(@RequestBody Map<String, String> body) {
        return service.answer(body.get("question"));
    }
}