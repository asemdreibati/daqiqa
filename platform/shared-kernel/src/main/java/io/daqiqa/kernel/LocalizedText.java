package io.daqiqa.kernel;

import java.util.Objects;

/** Text in the two languages every Gulf customer-facing surface needs: English and Arabic. */
public record LocalizedText(String en, String ar) {

    public LocalizedText {
        Objects.requireNonNull(en, "en");
        Objects.requireNonNull(ar, "ar");
        if (en.isBlank() || ar.isBlank()) {
            throw new IllegalArgumentException("Both English and Arabic text are required");
        }
    }

    public static LocalizedText of(String en, String ar) {
        return new LocalizedText(en, ar);
    }
}
