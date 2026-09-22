package io.daqiqa.ordering.adapter.in.web;

import io.daqiqa.ordering.application.CheckoutRejection;
import io.daqiqa.ordering.application.CheckoutResponseRenderer;
import io.daqiqa.ordering.application.StoredResponse;
import io.daqiqa.ordering.domain.Order;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders checkout outcomes with the application's {@link JsonMapper}, the one Spring MVC writes responses with, so a
 * stored response is exactly what the controller would have produced; the controller then sends these bytes as they
 * are, for the first request and for every replay.
 */
@Component
class JsonCheckoutResponseRenderer implements CheckoutResponseRenderer {

    private static final URI INSTANCE = URI.create(OrderController.PATH);

    private final JsonMapper json;

    JsonCheckoutResponseRenderer(JsonMapper json) {
        this.json = json;
    }

    @Override
    public StoredResponse orderPlaced(Order order) {
        return new StoredResponse(HttpStatus.CREATED.value(), json.writeValueAsString(OrderJson.from(order)));
    }

    @Override
    public StoredResponse rejected(CheckoutRejection rejection) {
        ProblemDetail problem = Problems.rejected(rejection);
        // Spring MVC sets the instance to the request path on the problems it renders itself; so do stored ones.
        problem.setInstance(INSTANCE);
        return new StoredResponse(problem.getStatus(), json.writeValueAsString(problem));
    }
}
