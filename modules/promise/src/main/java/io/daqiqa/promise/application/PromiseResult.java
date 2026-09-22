package io.daqiqa.promise.application;

import io.daqiqa.promise.domain.Suggestion;
import io.daqiqa.promise.domain.UnavailableReason;
import java.util.List;
import java.util.Objects;

/** The answer to "how fast can this basket get here?": a signed promise, or the reason there is none. */
public sealed interface PromiseResult {

    /** Faster options by dropping items: at most two, fastest first. */
    List<Suggestion> suggestions();

    record Available(IssuedPromise offer, List<Suggestion> suggestions) implements PromiseResult {

        public Available {
            Objects.requireNonNull(offer, "offer");
            suggestions = List.copyOf(suggestions);
        }
    }

    record Unavailable(UnavailableReason reason, List<Suggestion> suggestions) implements PromiseResult {

        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            suggestions = List.copyOf(suggestions);
        }
    }
}
