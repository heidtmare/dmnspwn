package heidtmare.dmnspwn.store;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * How every {@link ModelStore} lays out a model's files, relative to its root:
 * <pre>
 *   &lt;id&gt;.dmn                       the model
 *   &lt;id&gt;.tests.xml                 its test scenarios
 *   .history/&lt;id&gt;/&lt;revision&gt;.dmn   previous versions for undo, numbered so that they sort as text
 *   .meta/&lt;id&gt;.properties         metadata such as the S3 link
 * </pre>
 */
public final class StoreLayout {

    public static final String MODEL_EXT = ".dmn";
    public static final String TESTS_EXT = ".tests.xml";
    public static final String META_EXT = ".properties";
    public static final String HISTORY_DIR = ".history";
    public static final String META_DIR = ".meta";

    private static final Pattern SNAPSHOT = Pattern.compile("\\d{12}\\.dmn");

    private StoreLayout() {
    }

    /** The model id of a file name with extension {@code ext}, if the name is one. */
    public static Optional<String> idOf(String fileName, String ext) {
        if (!fileName.endsWith(ext)) {
            return Optional.empty();
        }
        String id = fileName.substring(0, fileName.length() - ext.length());
        return ModelStore.isValid(id) ? Optional.of(id) : Optional.empty();
    }

    public static String snapshotName(long revision) {
        return "%012d".formatted(revision) + MODEL_EXT;
    }

    public static boolean isSnapshot(String name) {
        return SNAPSHOT.matcher(name).matches();
    }

    /** The revision a snapshot was taken at; {@code name} must be a {@link #isSnapshot snapshot name}. */
    public static long revisionOf(String name) {
        return Long.parseLong(name.substring(0, name.length() - MODEL_EXT.length()));
    }

    public static String requireSnapshot(String name) {
        if (!isSnapshot(name)) {
            throw new IllegalArgumentException("Not a snapshot: " + name);
        }
        return name;
    }

    public static String encode(Properties props) {
        StringWriter out = new StringWriter();
        try {
            props.store(out, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    public static Properties decode(String content) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return props;
    }

    /** The failure of a conditional write of {@code what}, based on {@code current} (null: it was to be new). */
    public static StoreConflictException conflict(String what, ModelStore.Stored current) {
        return new StoreConflictException(current == null ? what + " already exists"
                : what + " was changed by someone else");
    }
}
