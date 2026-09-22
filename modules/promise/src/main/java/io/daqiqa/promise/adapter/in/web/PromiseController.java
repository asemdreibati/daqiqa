package io.daqiqa.promise.adapter.in.web;

import io.daqiqa.promise.adapter.in.web.PromiseJson.PromiseRequest;
import io.daqiqa.promise.adapter.in.web.PromiseJson.PromiseResponse;
import io.daqiqa.promise.application.PromiseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/promises")
class PromiseController {

    private final PromiseService promises;

    PromiseController(PromiseService promises) {
        this.promises = promises;
    }

    /** "We cannot deliver this" is a normal answer (200, UNAVAILABLE), not an error. */
    @PostMapping
    PromiseResponse promise(@Valid @RequestBody PromiseRequest request) {
        return PromiseResponse.from(promises.promise(request.geoPoint(), request.lines()));
    }
}
