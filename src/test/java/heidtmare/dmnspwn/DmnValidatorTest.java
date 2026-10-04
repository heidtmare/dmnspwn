package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import heidtmare.dmnspwn.edit.DmnEditor;
import heidtmare.dmnspwn.edit.LogicType;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.validate.DmnValidator;
import heidtmare.dmnspwn.validate.Issue;
import heidtmare.dmnspwn.xml.DmnDocument;

class DmnValidatorTest {

    private static List<String> messages(DmnDocument doc) {
        return DmnValidator.validate(new DmnReader(DmnDocument.parse(doc.toXml()))).stream()
                .map(Issue::message).toList();
    }

    /** Messages for the loan sample with each {@code from, to} pair of text replaced. */
    private static List<String> messagesWith(String... replacements) {
        String xml = TestModels.xml("loan-eligibility");
        for (int i = 0; i < replacements.length; i += 2) {
            assertThat(xml).contains(replacements[i]);
            xml = xml.replace(replacements[i], replacements[i + 1]);
        }
        return messages(DmnDocument.parse(xml));
    }

    @Test
    void acceptsTheSampleModels() {
        assertThat(messages(TestModels.loan())).isEmpty();
        assertThat(messages(TestModels.dish())).isEmpty();
    }

    @Test
    void reportsDuplicateIdsAndMissingOrRepeatedNames() {
        assertThat(messagesWith("<variable id=\"Credit_Score_var\"", "<variable id=\"Applicant_Age_var\""))
                .contains("Id 'Applicant_Age_var' is used 2 times");
        assertThat(messagesWith("<inputData id=\"Credit_Score\" name=\"Credit Score\">",
                "<inputData id=\"Credit_Score\" name=\" \">")).contains("Missing name");
        assertThat(messagesWith("<inputData id=\"Credit_Score\" name=\"Credit Score\">",
                "<inputData id=\"Credit_Score\" name=\"Applicant Age\">"))
                .contains("Another DRG element is also named 'Applicant Age'");
    }

    @Test
    void reportsMissingLogic() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);
        ed.logic().setType("Eligibility", LogicType.NONE);
        ed.logic().setType("Installment_Calculation", LogicType.NONE);

        assertThat(messages(doc)).contains("Decision has no decision logic",
                "Business knowledge model has no encapsulated logic");
    }

    @Test
    void reportsMalformedDecisionTables() {
        assertThat(messagesWith("<inputEntry><text>&lt; 18</text></inputEntry><inputEntry><text>-</text></inputEntry>",
                "<inputEntry><text>&lt; 18</text></inputEntry>"))
                .contains("Rule 1 has 1 input / 1 output entries, expected 2 / 1");
        assertThat(messagesWith("<inputEntry><text>&lt; 18</text></inputEntry>", "<inputEntry><text/></inputEntry>",
                "<outputEntry><text>\"Low\"</text></outputEntry>", "<outputEntry><text> </text></outputEntry>"))
                .contains("Rule 1 has an empty input entry (use '-' for any)", "Rule 6 has an empty output entry");
        assertThat(messagesWith("<output id=\"DT_Elig_out\" name=\"Eligibility\" typeRef=\"tEligibility\"/>",
                "<output id=\"DT_Elig_out\" typeRef=\"tEligibility\"/><output id=\"DT_Elig_out2\" name=\"Other\"/>"))
                .contains("Every output of a multi-output decision table needs a name");

        String noRules = TestModels.xml("loan-eligibility").replaceAll("(?s)<rule id=\"DT_Elig_r.*?</rule>", "");
        assertThat(messages(DmnDocument.parse(noRules))).contains("Decision table has no rules");
        String noOutput = noRules.replace("<output id=\"DT_Elig_out\" name=\"Eligibility\" typeRef=\"tEligibility\"/>", "");
        assertThat(messages(DmnDocument.parse(noOutput))).contains("Decision table has no output");
    }

    @Test
    void reportsServicesWithoutOutputsAndDisallowedConnections() {
        assertThat(messagesWith("<outputDecision href=\"#Eligibility\"/>", ""))
                .contains("Decision service has no output decisions");
        assertThat(messagesWith("<requiredKnowledge href=\"#Installment_Calculation\"/>",
                "<requiredKnowledge href=\"#Requested_Amount\"/>"))
                .anyMatch(m -> m.endsWith("from Input Data 'Requested Amount' is not allowed"));
    }

    @Test
    void reportsShapesForUnknownElements() {
        assertThat(messagesWith("dmnElementRef=\"Loan_Offer\"", "dmnElementRef=\"Gone\""))
                .anyMatch(m -> m.endsWith("has a shape for unknown element 'Gone'"));
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
