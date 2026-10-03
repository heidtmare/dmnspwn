package heidtmare.dmnspwn.xml;

/**
 * A DMN element reference ({@code href="#id"} or {@code href="namespace#id"}).
 *
 * @param namespace the referenced model namespace, empty for local references
 * @param id        the referenced element id
 */
public record Href(String namespace, String id) {

    public static Href parse(String href) {
        if (href == null) {
            return new Href("", "");
        }
        int hash = href.lastIndexOf('#');
        return hash < 0 ? new Href("", href.trim()) : new Href(href.substring(0, hash).trim(), href.substring(hash + 1).trim());
    }

    public static String local(String id) {
        return "#" + id;
    }

    /** Whether this reference points into the model with the given {@code definitions/@namespace}. */
    public boolean isLocal(String modelNamespace) {
        return namespace.isEmpty() || namespace.equals(modelNamespace);
    }
}
