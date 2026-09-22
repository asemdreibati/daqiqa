package io.daqiqa.promise.domain;

import java.util.List;
import java.util.Objects;

/** What the calculator concluded: a quote, or the reason there is none. Suggestions come with either. */
public sealed interface PromiseDecision {

    /** At most two, fastest first. */
    List<Suggestion> suggestions();

    record Offered(Quote quote, List<Suggestion> suggestions) implements PromiseDecision {

        public Offered {
            Objects.requireNonNull(quote, "quote");
            suggestions = List.copyOf(suggestions);
        }
    }

    record Unavailable(UnavailableReason reason, List<Suggestion> suggestions) implements PromiseDecision {

        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            suggestions = List.copyOf(suggestions);
        }
    }
}
