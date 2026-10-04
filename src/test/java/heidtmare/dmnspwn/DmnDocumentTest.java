package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.DmnFormatException;
import heidtmare.dmnspwn.xml.DmnNamespaces;

class DmnDocumentTest {

    @Test
    void detectsVersions() {
        assertThat(TestModels.loan().version()).isEqualTo("1.5");
        assertThat(TestModels.dish().version()).isEqualTo("1.3");
        assertThat(DmnNamespaces.version(DmnNamespaces.DMN_1_1)).isEqualTo("1.1");
        assertThat(DmnNamespaces.isModel("https://www.omg.org/spec/DMN/20250101/MODEL/")).isTrue();
    }

    @Test
    void roundTripPreservesContentIncludingUnknownExtensions() {
        String xml = TestModels.xml("loan-eligibility").replace("<description>Sample",
                "<extensionElements><vendor:x xmlns:vendor=\"urn:vendor\" keep=\"me\"/></extensionElements><description>Sample")
                .replace("<extensionElements>", "<!-- a comment --><extensionElements>");
        DmnDocument doc = DmnDocument.parse(xml);
        String out = doc.toXml();
        DmnDocument again = DmnDocument.parse(out);

        assertThat(out).contains("urn:vendor").contains("keep=\"me\"").contains("a comment");
        assertThat(new DmnReader(again).elements()).hasSameSizeAs(new DmnReader(doc).elements());
        assertThat(again.toXml()).isEqualTo(out);
        assertThat(out).doesNotContain("\n\n");
    }

    @Test
    void serializingLeavesTheDocumentUnchanged() {
        DmnDocument doc = TestModels.loan();
        int before = doc.definitions().getChildNodes().getLength();
        String first = doc.toXml();
        assertThat(doc.definitions().getChildNodes().getLength()).isEqualTo(before);
        assertThat(doc.toXml()).isEqualTo(first);
    }

    @Test
    void rejectsNonDmnAndDoctypes() {
        assertThatThrownBy(() -> DmnDocument.parse("<foo/>")).isInstanceOf(DmnFormatException.class);
        assertThatThrownBy(() -> DmnDocument.parse("<definitions"))
                .isInstanceOf(DmnFormatException.class).hasMessageContaining("line");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<definitions xmlns=\"" + DmnNamespaces.DMN_1_5 + "\">&x;</definitions>";
        assertThatThrownBy(() -> DmnDocument.parse(xxe)).isInstanceOf(DmnFormatException.class);
    }

    @Test
    void blankModelIsValidDmn15WithDiagram() {
        DmnDocument doc = DmnDocument.parse(DmnDocument.blank("My <model>", "urn:test").toXml());
        assertThat(doc.ns()).isEqualTo(DmnNamespaces.DMN_1_5);
        assertThat(doc.diagrams()).hasSize(1);
        assertThat(new DmnReader(doc).info().name()).isEqualTo("My <model>");
    }
}
