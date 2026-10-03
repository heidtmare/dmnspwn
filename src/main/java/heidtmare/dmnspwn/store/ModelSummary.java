package heidtmare.dmnspwn.store;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Listing entry for the home page; {@code error} is set for files that cannot be parsed. */
public record ModelSummary(String id, String name, String version, String namespace, int decisions, int elements,
                           Instant updated, String error, String s3Key) {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public String updatedText() {
        return FORMAT.format(updated);
    }
}
