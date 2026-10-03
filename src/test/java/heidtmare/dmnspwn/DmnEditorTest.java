package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import heidtmare.dmnspwn.diagram.DiagramBuilder;
import heidtmare.dmnspwn.edit.DmnEditException;
import heidtmare.dmnspwn.edit.DmnEditor;
import heidtmare.dmnspwn.edit.Forms.DecisionTableForm;
import heidtmare.dmnspwn.edit.Forms.ElementForm;
import heidtmare.dmnspwn.edit.Forms.ItemDefinitionForm;
import heidtmare.dmnspwn.edit.Forms.ParametersForm;
import heidtmare.dmnspwn.edit.Forms.RuleRow;
import heidtmare.dmnspwn.edit.Forms.ServiceForm;
import heidtmare.dmnspwn.edit.LogicType;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.model.ExpressionView;
import heidtmare.dmnspwn.validate.DmnValidator;
import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.DmnNamespaces;

class DmnEditorTest {

    /** Serializes and re-reads, as every web request does. */
    private static DmnReader reread(DmnDocument doc) {
        return new DmnReader(DmnDocument.parse(doc.toXml()));
    }

    @Test
    void addsElementsWithVariablesAndShapes() {
        DmnDocument doc = DmnDocument.blank("Test", "urn:test");
        DmnEditor ed = new DmnEditor(doc);
        String input = ed.addElement(ElementKind.INPUT_DATA, "Age", null);
        String decision = ed.addElement(ElementKind.DECISION, "Adult", null);
        ed.connect(input, decision);

        DmnReader reader = reread(doc);
        assertThat(reader.element(decision).orElseThrow().requires()).singleElement()
                .satisfies(c -> assertThat(c.kind()).isEqualTo(ConnectionKind.INFORMATION));
        var diagram = DiagramBuilder.build(reader, null);
        assertThat(diagram.fromDmndi()).isTrue();
        assertThat(diagram.nodes()).hasSize(2);
        assertThat(diagram.edges()).hasSize(1);
        assertThat(doc.toXml()).contains("<variable").contains("name=\"Adult\"");
    }

    @Test
    void enforcesConnectionRulesDuplicatesAndCycles() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);
        assertThatThrownBy(() -> ed.connect("Loan_Offer", "Applicant_Age"))
                .isInstanceOf(DmnEditException.class).hasMessageContaining("does not allow");
        assertThatThrownBy(() -> ed.connect("Applicant_Age", "Risk_Category"))
                .hasMessageContaining("already connected");
        assertThatThrownBy(() -> ed.connect("Loan_Offer", "Risk_Category")).hasMessageContaining("cycle");
        assertThat(ed.connect("Installment_Calculation", "Eligibility")).isEqualTo(ConnectionKind.KNOWLEDGE);
        assertThat(ed.connect("Lending_Policy", "Note_Minors")).isEqualTo(ConnectionKind.ASSOCIATION);
    }

    @Test
    void deletingAnElementRemovesReferencesAndDiagramInterchange() {
        DmnDocument doc = TestModels.loan();
        new DmnEditor(doc).deleteElement("Risk_Category");
        String xml = doc.toXml();
        assertThat(xml).doesNotContain("#Risk_Category").doesNotContain("dmnElementRef=\"Risk_Category\"")
                .doesNotContain("IR_Risk_Elig").doesNotContain("IR_Age_Risk");
        assertThat(DmnValidator.validate(reread(doc))).noneMatch(i -> i.isError());
    }

    @Test
    void disconnectsById() {
        DmnDocument doc = TestModels.loan();
        new DmnEditor(doc).disconnect("IR_Income_Elig");
        assertThat(doc.toXml()).doesNotContain("IR_Income_Elig");
    }

    @Test
    void disconnectsRequirementsWithoutIds() {
        DmnDocument doc = TestModels.dish();
        DmnReader reader = new DmnReader(doc);
        String ref = reader.element("Dish").orElseThrow().requires().getFirst().ref();
        assertThat(ref).contains("|");
        new DmnEditor(doc).disconnect(ref);
        assertThat(reread(doc).element("Dish").orElseThrow().requires()).hasSize(1);
    }

    @Test
    void editsDecisionTablesStructurally() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);

        DecisionTableForm form = new DecisionTableForm();
        form.setHitPolicy("COLLECT");
        form.setAggregation("COUNT");
        RuleRow row = new RuleRow();
        row.setInputs(List.of("< 21", "-"));
        row.setOutputs(List.of("\"High\""));
        row.setAnnotations(List.of("changed"));
        form.setRules(List.of(row));
        form.setAction("addInput:0");
        ed.saveDecisionTable("Risk_Category", form);

        var table = (ExpressionView.DecisionTable) reread(doc).element("Risk_Category").orElseThrow().logic();
        assertThat(table.hitPolicyCode()).isEqualTo("C#");
        assertThat(table.inputs()).hasSize(3);
        assertThat(table.rules().getFirst().inputs()).containsExactly("< 21", "-", "-");
        assertThat(table.rules().getFirst().annotations()).containsExactly("changed");
        assertThat(table.rules()).allMatch(r -> r.inputs().size() == 3);

        for (String action : List.of("addOutput", "addRule:0", "duplicateRule:1", "moveRuleDown:0", "deleteInput:1",
                "deleteAnnotation:0", "addAnnotation", "deleteOutput:1", "deleteRule:7")) {
            DecisionTableForm f = new DecisionTableForm();
            f.setHitPolicy("UNIQUE");
            f.setAction(action);
            ed.saveDecisionTable("Risk_Category", f);
        }
        table = (ExpressionView.DecisionTable) reread(doc).element("Risk_Category").orElseThrow().logic();
        assertThat(table.inputs()).hasSize(2);
        assertThat(table.outputs()).hasSize(1);
        assertThat(table.annotations()).hasSize(1);
        assertThat(table.rules()).hasSize(7);
        assertThat(table.rules()).allMatch(r -> r.inputs().size() == 2 && r.outputs().size() == 1
                && r.annotations().size() == 1);
        assertThat(table.hitPolicy()).isEqualTo("UNIQUE");
        assertThat(table.aggregation()).isNull();
    }

    @Test
    void createsEveryLogicTypeAndSeedsDecisionTablesFromRequirements() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);
        for (LogicType type : LogicType.available(doc.ns())) {
            ed.setLogicType("Eligibility", type);
            ExpressionView logic = reread(doc).element("Eligibility").orElseThrow().logic();
            if (type == LogicType.NONE) {
                assertThat(logic).isNull();
            } else {
                assertThat(logic).isNotNull();
            }
        }
        ed.setLogicType("Eligibility", LogicType.DECISION_TABLE);
        var table = (ExpressionView.DecisionTable) reread(doc).element("Eligibility").orElseThrow().logic();
        assertThat(table.inputs()).extracting(i -> i.expression()).containsExactly("Risk Category", "Monthly Income");

        ed.setLogicType("Loan_Offer", LogicType.INVOCATION);
        var inv = (ExpressionView.Invocation) reread(doc).element("Loan_Offer").orElseThrow().logic();
        assertThat(inv.function()).isEqualTo("Installment Calculation");
        assertThat(inv.bindings()).hasSize(3);
        assertThat(DmnValidator.validate(reread(doc))).noneMatch(i -> i.isError());
    }

    @Test
    void replacesLogicFromXmlUsingDocumentPrefixes() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);
        ed.replaceLogicXml("Eligibility", "<literalExpression><text>\"Eligible\"</text></literalExpression>");
        assertThat(reread(doc).element("Eligibility").orElseThrow().logic())
                .isEqualTo(new ExpressionView.Literal(null, null, "\"Eligible\"", null));
        assertThat(doc.toXml()).doesNotContain("<literalExpression xmlns");
        assertThatThrownBy(() -> ed.replaceLogicXml("Eligibility", "<foo/>")).isInstanceOf(DmnEditException.class);
        assertThatThrownBy(() -> ed.replaceLogicXml("Eligibility", "<literalExpression>"))
                .isInstanceOf(DmnEditException.class);
    }

    @Test
    void editsPropertiesParametersServicesAndTypes() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);

        ElementForm props = new ElementForm();
        props.setName("Risk Class");
        props.setTypeRef("string");
        props.setQuestion("Q?");
        ed.updateElement("Risk_Category", props);

        ParametersForm params = new ParametersForm();
        params.setAction("addParameter");
        ed.saveParameters("Installment_Calculation", params);

        ServiceForm service = new ServiceForm();
        service.setOutputDecisions(List.of("Loan_Offer"));
        service.setEncapsulatedDecisions(List.of("Eligibility", "Risk_Category"));
        service.setInputData(List.of("Requested_Amount"));
        ed.updateService("Eligibility_Service", service);
        ServiceForm bad = new ServiceForm();
        bad.setInputData(List.of("Loan_Offer"));
        assertThatThrownBy(() -> ed.updateService("Eligibility_Service", bad)).isInstanceOf(DmnEditException.class);

        String path = ed.addItemDefinition("tPerson", null, false);
        ItemDefinitionForm type = new ItemDefinitionForm();
        type.setName("tPerson");
        type.setAction("addComponent");
        ed.saveItemDefinition(path, type);

        DmnReader reader = reread(doc);
        var risk = reader.element("Risk_Category").orElseThrow();
        assertThat(risk.name()).isEqualTo("Risk Class");
        assertThat(risk.typeRef()).isEqualTo("string");
        assertThat(risk.question()).isEqualTo("Q?");
        assertThat(reader.element("Installment_Calculation").orElseThrow().parameters()).hasSize(4);
        assertThat(reader.element("Eligibility_Service").orElseThrow().service().encapsulatedDecisions()).hasSize(2);
        assertThat(reader.itemDefinitions().getLast().components()).hasSize(1);
        assertThat(doc.toXml()).contains("<variable id=\"Risk_Category_var\" name=\"Risk Class\" typeRef=\"string\"/>");
    }

    @Test
    void movesShapesAndReroutesEdges() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);
        Element diagram = ed.diagrams().ensureDiagram(null);
        ed.diagrams().move(diagram, "Eligibility_Service", 300, 150, null, null);

        var view = DiagramBuilder.build(reread(doc), null);
        var risk = view.nodes().stream().filter(n -> n.elementId().equals("Risk_Category")).findFirst().orElseThrow();
        assertThat(risk.bounds().x()).isEqualTo(340);
        var edge = view.edges().stream().filter(e -> e.ref().equals("IR_Risk_Elig")).findFirst().orElseThrow();
        assertThat(edge.points().getFirst().x()).isEqualTo(430);
    }

    @Test
    void materialisesAutoLayoutAsDmndiOnFirstLayoutEdit() {
        DmnDocument doc = TestModels.dish();
        DmnEditor ed = new DmnEditor(doc);
        Element diagram = ed.diagrams().ensureDiagram(null);
        ed.diagrams().move(diagram, "Dish", 0, 0, 200.0, 100.0);
        var view = DiagramBuilder.build(reread(doc), null);
        assertThat(view.fromDmndi()).isTrue();
        assertThat(view.nodes()).hasSize(8);
        assertThat(view.edges()).hasSize(6);
        assertThat(doc.toXml()).contains(DmnNamespaces.DMNDI_1_3);
    }

    @Test
    void convertsOlderModelsToDmn15() {
        DmnDocument doc = TestModels.dish();
        new DmnEditor(doc).convertToLatest();
        DmnDocument converted = DmnDocument.parse(doc.toXml());
        assertThat(converted.version()).isEqualTo("1.5");
        assertThat(new DmnReader(converted).elements()).hasSize(8);

        String v11 = """
                <definitions xmlns="http://www.omg.org/spec/DMN/20151101/dmn.xsd" xmlns:feel="http://www.omg.org/spec/FEEL/20140401"
                  id="d" name="Old" namespace="urn:old">
                  <inputData id="a" name="A"><variable name="A" typeRef="feel:number"/></inputData>
                </definitions>""";
        DmnDocument old = DmnDocument.parse(v11);
        assertThat(old.supportsDmndi()).isFalse();
        assertThatThrownBy(() -> new DmnEditor(old).diagrams().ensureDiagram(null)).isInstanceOf(DmnEditException.class);
        new DmnEditor(old).convertToLatest();
        DmnReader reader = new DmnReader(DmnDocument.parse(old.toXml()));
        assertThat(reader.info().version()).isEqualTo("1.5");
        assertThat(reader.element("a").orElseThrow().typeRef()).isEqualTo("number");
    }
}
