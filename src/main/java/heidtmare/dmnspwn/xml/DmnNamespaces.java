package heidtmare.dmnspwn.xml;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** XML namespaces defined by the OMG DMN specifications (1.1 - 1.5). */
public final class DmnNamespaces {

    public static final String DMN_1_1 = "http://www.omg.org/spec/DMN/20151101/dmn.xsd";
    public static final String DMN_1_2 = "http://www.omg.org/spec/DMN/20180521/MODEL/";
    public static final String DMN_1_3 = "https://www.omg.org/spec/DMN/20191111/MODEL/";
    public static final String DMN_1_4 = "https://www.omg.org/spec/DMN/20211108/MODEL/";
    public static final String DMN_1_5 = "https://www.omg.org/spec/DMN/20230324/MODEL/";
    public static final String LATEST = DMN_1_5;

    public static final String DMNDI_1_2 = "http://www.omg.org/spec/DMN/20180521/DMNDI/";
    public static final String DMNDI_1_3 = "https://www.omg.org/spec/DMN/20191111/DMNDI/";
    public static final String DC = "http://www.omg.org/spec/DMN/20180521/DC/";
    public static final String DI = "http://www.omg.org/spec/DMN/20180521/DI/";

    public static final String FEEL_1_5 = "https://www.omg.org/spec/DMN/20230324/FEEL/";

    private static final Pattern MODEL =
            Pattern.compile("^https?://www\\.omg\\.org/spec/DMN/(\\d{8})/(?:MODEL/?|dmn\\.xsd)$");
    private static final Pattern DMNDI =
            Pattern.compile("^https?://www\\.omg\\.org/spec/DMN/(\\d{8})/DMNDI/?$");
    private static final Pattern FEEL =
            Pattern.compile("^https?://www\\.omg\\.org/spec/(?:DMN/\\d{8}/FEEL/?|FEEL/\\d{8})$");

    private static final Map<String, String> VERSIONS = Map.of(
            "20151101", "1.1",
            "20180521", "1.2",
            "20191111", "1.3",
            "20211108", "1.4",
            "20230324", "1.5");

    private DmnNamespaces() {
    }

    public static boolean isModel(String ns) {
        return ns != null && MODEL.matcher(ns).matches();
    }

    public static boolean isDmnDi(String ns) {
        return ns != null && DMNDI.matcher(ns).matches();
    }

    public static boolean isDc(String ns) {
        return DC.equals(ns);
    }

    public static boolean isDi(String ns) {
        return DI.equals(ns);
    }

    public static boolean isFeel(String ns) {
        return ns != null && FEEL.matcher(ns).matches();
    }

    /** Human readable DMN version ("1.3") for a model namespace. */
    public static String version(String modelNs) {
        if (modelNs == null) {
            return "?";
        }
        Matcher m = MODEL.matcher(modelNs);
        if (!m.matches()) {
            return "?";
        }
        return VERSIONS.getOrDefault(m.group(1), m.group(1));
    }

    public static boolean isLatest(String modelNs) {
        return LATEST.equals(modelNs);
    }

    /** DMN 1.1 predates DMNDI; diagram interchange exists from 1.2 onwards. */
    public static boolean supportsDmndi(String modelNs) {
        return !DMN_1_1.equals(modelNs);
    }

    /** Version-aware feature check: boxed conditional / iterators / filter exist from DMN 1.4. */
    public static boolean supportsBoxedExtensions(String modelNs) {
        String v = version(modelNs);
        return v.compareTo("1.4") >= 0 && v.length() == 3;
    }

    public static String dmndiFor(String modelNs) {
        return DMN_1_2.equals(modelNs) ? DMNDI_1_2 : DMNDI_1_3;
    }
}
