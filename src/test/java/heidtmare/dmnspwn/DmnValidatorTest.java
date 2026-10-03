package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.validate.DmnValidator;
import heidtmare.dmnspwn.validate.Issue;
import heidtmare.dmnspwn.xml.DmnDocument;

class DmnValidatorTest {

    private static List<String> messages(DmnDocument doc) {
        return DmnValidator.validate(new DmnReader(DmnDocument.parse(doc.toXml()))).stream()
                .map(Issue::message).toList();
    }

    @Test
    void acceptsBuiltInTypesAndTheirAliases() {
        DmnDocument doc = TestModels.loan();
        var variables = doc.dom().getElementsByTagNameNS("*", "variable");
        ((Element) variables.item(0)).setAttribute("typeRef", "dateTime");
        ((Element) variables.item(1)).setAttribute("typeRef", "no such type");

        assertThat(messages(doc)).noneMatch(m -> m.contains("'dateTime'"))
                .contains("Unknown type 'no such type'");
    }

    @Test
    void namesTheMissingEndOfAConnection() {
        DmnDocument doc = TestModels.loan();
        Element association = doc.create("association");
        association.setAttribute("id", "Dangling");
        Element source = doc.create("sourceRef");
        source.setAttribute("href", "#Applicant_Age");
        Element target = doc.create("targetRef");
        target.setAttribute("href", "#Nowhere");
        association.appendChild(source);
        association.appendChild(target);
        doc.definitions().appendChild(association);

        assertThat(messages(doc)).anyMatch(m -> m.endsWith("references missing element 'Nowhere'"));
    }

    @Test
    void reportsRequirementCycles() {
        DmnDocument doc = TestModels.loan();
        Element requirement = doc.create("informationRequirement");
        Element ref = doc.create("requiredDecision");
        ref.setAttribute("href", "#Loan_Offer");
        requirement.appendChild(ref);
        doc.insert(new DmnReader(doc).nodeElements().get("Risk_Category"), requirement);

        assertThat(messages(doc)).contains("Requirement cycle involving this element");
        assertThat(messages(TestModels.loan())).doesNotContain("Requirement cycle involving this element");
    }
}
