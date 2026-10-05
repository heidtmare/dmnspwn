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
import heidtmare.dmnspwn.edit.LogicEditor.LogicType;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.model.ExpressionView;
import heidtmare.dmnspwn.model.HitPolicy;
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
        ed.connections().connect(input, decision);

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
        assertThatThrownBy(() -> ed.connections().connect("Loan_Offer", "Applicant_Age"))
                .isInstanceOf(DmnEditException.class).hasMessageContaining("does not allow");
        assertThatThrownBy(() -> ed.connections().connect("Applicant_Age", "Risk_Category"))
                .hasMessageContaining("already connected");
        assertThatThrownBy(() -> ed.connections().connect("Loan_Offer", "Risk_Category")).hasMessageContaining("cycle");
        assertThat(ed.connections().connect("Installment_Calculation", "Eligibility")).isEqualTo(ConnectionKind.KNOWLEDGE);
        assertThat(ed.connections().connect("Lending_Policy", "Note_Minors")).isEqualTo(ConnectionKind.ASSOCIATION);
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
        new DmnEditor(doc).connections().disconnect("IR_Income_Elig");
        assertThat(doc.toXml()).doesNotContain("IR_Income_Elig");
    }

    @Test
    void disconnectsRequirementsWithoutIds() {
        DmnDocument doc = TestModels.dish();
        DmnReader reader = new DmnReader(doc);
        String ref = reader.element("Dish").orElseThrow().requires().getFirst().ref();
        assertThat(ref).contains("|");
        new DmnEditor(doc).connections().disconnect(ref);
        assertThat(reread(doc).element("Dish").orElseThrow().requires()).hasSize(1);
    }

    /** Has no id (copied from an entry without one) or a generated id with the expected prefix. */
    private static boolean idLike(Element e, String prefix) {
        return !e.hasAttribute("id") || e.getAttribute("id").startsWith(prefix);
    }

    @Test
    void duplicatedRulesGetIdsLikeNewRules() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);
        for (String action : List.of("addRule:0", "duplicateRule:1")) {
            DecisionTableForm f = new DecisionTableForm();
            f.setHitPolicy("UNIQUE");
            f.setAction(action);
            ed.tables().save("Risk_Category", f);
        }

        Element table = ed.logic().expression("Risk_Category").orElseThrow();
        Element added = doc.children(table, "rule").get(1);
        Element duplicate = doc.children(table, "rule").get(2);
        assertThat(doc.children(duplicate, "inputEntry")).allMatch(e -> e.hasAttribute("id"));
        for (Element rule : List.of(duplicate, added)) {
            assertThat(rule.getAttribute("id")).startsWith("DecisionRule_");
            assertThat(doc.children(rule, "inputEntry")).allMatch(e -> idLike(e, "UnaryTests_"));
            assertThat(doc.children(rule, "outputEntry")).allMatch(e -> idLike(e, "LiteralExpression_"));
        }
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
        ed.tables().save("Risk_Category", form);

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
            ed.tables().save("Risk_Category", f);
        }
        table = (ExpressionView.DecisionTable) reread(doc).element("Risk_Category").orElseThrow().logic();
        assertThat(table.inputs()).hasSize(2);
        assertThat(table.outputs()).hasSize(1);
        assertThat(table.annotations()).hasSize(1);
        assertThat(table.rules()).hasSize(7);
        assertThat(table.rules()).allMatch(r -> r.inputs().size() == 2 && r.outputs().size() == 1
                && r.annotations().size() == 1);
        assertThat(table.hitPolicy()).isEqualTo(HitPolicy.UNIQUE);
        assertThat(table.aggregation()).isNull();
    }

    @Test
    void readsBlankHitPoliciesAsUniqueAndRejectsUnknownOnes() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);
        DecisionTableForm form = new DecisionTableForm();
        form.setHitPolicy("");
        ed.tables().save("Risk_Category", form);
        var table = (ExpressionView.DecisionTable) reread(doc).element("Risk_Category").orElseThrow().logic();
        assertThat(table.hitPolicy()).isEqualTo(HitPolicy.UNIQUE);

        form.setHitPolicy("RULE ORDER");
        ed.tables().save("Risk_Category", form);
        table = (ExpressionView.DecisionTable) reread(doc).element("Risk_Category").orElseThrow().logic();
        assertThat(table.hitPolicyCode()).isEqualTo("R");

        form.setHitPolicy("SOMETIMES");
        assertThatThrownBy(() -> ed.tables().save("Risk_Category", form))
                .isInstanceOf(DmnEditException.class).hasMessageContaining("Unknown hit policy");
    }

    @Test
    void rejectsUnknownOrIncompleteFormActions() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);
        DecisionTableForm table = new DecisionTableForm();
        table.setHitPolicy("UNIQUE");
        for (var c : List.of(new String[] {"explode", "Unknown action"}, new String[] {"deleteRule", "needs a row"},
                new String[] {"deleteRule:x", "Invalid row"}, new String[] {"deleteRule:-1", "Invalid row"},
                new String[] {"deleteRule:99", "out of range"})) {
            table.setAction(c[0]);
            assertThatThrownBy(() -> ed.tables().save("Risk_Category", table))
                    .isInstanceOf(DmnEditException.class).hasMessageContaining(c[1]);
        }
        ParametersForm params = new ParametersForm();
        params.setAction("deleteRule:0");
        assertThatThrownBy(() -> ed.logic().saveParameters("Installment_Calculation", params))
                .hasMessageContaining("Unknown action");
        ItemDefinitionForm type = new ItemDefinitionForm();
        type.setName("tLoan");
        type.setAction("moveComponentUp");
        assertThatThrownBy(() -> ed.types().save("0", type)).hasMessageContaining("needs a row");
    }

    @Test
    void createsEveryLogicTypeAndSeedsDecisionTablesFromRequirements() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);
        for (LogicType type : LogicType.available(doc.ns())) {
            ed.logic().setType("Eligibility", type);
            ExpressionView logic = reread(doc).element("Eligibility").orElseThrow().logic();
            if (type == LogicType.NONE) {
                assertThat(logic).isNull();
            } else {
                assertThat(logic).isNotNull();
            }
        }
        ed.logic().setType("Eligibility", LogicType.DECISION_TABLE);
        var table = (ExpressionView.DecisionTable) reread(doc).element("Eligibility").orElseThrow().logic();
        assertThat(table.inputs()).extracting(i -> i.expression()).containsExactly("Risk Category", "Monthly Income");

        ed.logic().setType("Loan_Offer", LogicType.INVOCATION);
        var inv = (ExpressionView.Invocation) reread(doc).element("Loan_Offer").orElseThrow().logic();
        assertThat(inv.function()).isEqualTo("Installment Calculation");
        assertThat(inv.bindings()).hasSize(3);
        assertThat(DmnValidator.validate(reread(doc))).noneMatch(i -> i.isError());
    }

    @Test
    void replacesLogicFromXmlUsingDocumentPrefixes() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);
        ed.logic().replaceXml("Eligibility", "<literalExpression><text>\"Eligible\"</text></literalExpression>");
        assertThat(reread(doc).element("Eligibility").orElseThrow().logic())
                .isEqualTo(new ExpressionView.Literal(null, null, "\"Eligible\"", null));
        assertThat(doc.toXml()).doesNotContain("<literalExpression xmlns");
        assertThatThrownBy(() -> ed.logic().replaceXml("Eligibility", "<foo/>")).isInstanceOf(DmnEditException.class);
        assertThatThrownBy(() -> ed.logic().replaceXml("Eligibility", "<literalExpression>"))
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
        ed.logic().saveParameters("Installment_Calculation", params);

        ServiceForm service = new ServiceForm();
        service.setOutputDecisions(List.of("Loan_Offer"));
        service.setEncapsulatedDecisions(List.of("Eligibility", "Risk_Category"));
        service.setInputData(List.of("Requested_Amount"));
        ed.updateService("Eligibility_Service", service);
        ServiceForm bad = new ServiceForm();
        bad.setInputData(List.of("Loan_Offer"));
        assertThatThrownBy(() -> ed.updateService("Eligibility_Service", bad)).isInstanceOf(DmnEditException.class);

        String path = ed.types().add("tPerson", null, false);
        ItemDefinitionForm type = new ItemDefinitionForm();
        type.setName("tPerson");
        type.setAction("addComponent");
        ed.types().save(path, type);

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
        ed.diagrams().move(null, "Eligibility_Service", 300, 150, null, null);

        var view = DiagramBuilder.build(reread(doc), null);
        var risk = view.nodes().stream().filter(n -> n.elementId().equals("Risk_Category")).findFirst().orElseThrow();
        assertThat(risk.bounds().x()).isEqualTo(340);
        var edge = view.edges().stream().filter(e -> e.ref().equals("IR_Risk_Elig")).findFirst().orElseThrow();
        assertThat(edge.points().getFirst().x()).isEqualTo(430);
    }

    @Test
    void hidesAndReportsShapesWithMalformedCoordinatesUntilLaidOut() {
        DmnDocument doc = DmnDocument.parse(TestModels.xml("loan-eligibility")
                .replace("<dc:Bounds x=\"200\" y=\"150\"", "<dc:Bounds x=\"oops\" y=\"150\""));
        var view = DiagramBuilder.build(reread(doc), null);
        assertThat(view.nodes()).noneMatch(n -> "Eligibility_Service".equals(n.elementId()));
        assertThat(DmnValidator.validate(reread(doc)))
                .anyMatch(i -> "Eligibility_Service".equals(i.elementId()) && i.message().contains("invalid coordinates"));

        new DmnEditor(doc).diagrams().resetLayout(null);
        assertThat(DiagramBuilder.build(reread(doc), null).nodes())
                .anyMatch(n -> "Eligibility_Service".equals(n.elementId()));
        assertThat(DmnValidator.validate(reread(doc))).noneMatch(i -> i.message().contains("invalid coordinates"));
    }

    @Test
    void materialisesAutoLayoutAsDmndiOnFirstLayoutEdit() {
        DmnDocument doc = TestModels.dish();
        DmnEditor ed = new DmnEditor(doc);
        ed.diagrams().move(null, "Dish", 0, 0, 200.0, 100.0);
        var view = DiagramBuilder.build(reread(doc), null);
        assertThat(view.fromDmndi()).isTrue();
        assertThat(view.nodes()).hasSize(8);
        assertThat(view.edges()).hasSize(6);
        assertThat(doc.toXml()).contains(DmnNamespaces.DMNDI_1_3);
    }

    @Test
    void convertsOlderModelsToDmn15() {
        DmnDocument doc = TestModels.dish();
        new DmnEditor(doc).converter().toLatest();
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
        new DmnEditor(old).converter().toLatest();
        DmnReader reader = new DmnReader(DmnDocument.parse(old.toXml()));
        assertThat(reader.info().version()).isEqualTo("1.5");
        assertThat(reader.element("a").orElseThrow().typeRef()).isEqualTo("number");
    }

    @Test
    void reordersAndDeletesRulesParametersAndComponents() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);
        for (String action : List.of("moveRuleUp:1", "moveRuleUp:0", "moveRuleDown:2", "moveRuleDown:5", "deleteRule:4")) {
            DecisionTableForm f = new DecisionTableForm();
            f.setHitPolicy("UNIQUE");
            f.setAction(action);
            ed.tables().save("Risk_Category", f);
        }
        Element table = ed.logic().expression("Risk_Category").orElseThrow();
        assertThat(doc.children(table, "rule")).extracting(r -> r.getAttribute("id"))
                .containsExactly("DT_Risk_r2", "DT_Risk_r1", "DT_Risk_r4", "DT_Risk_r3", "DT_Risk_r6");

        for (String action : List.of("moveParameterUp:2", "moveParameterUp:0", "deleteParameter:0")) {
            ParametersForm f = new ParametersForm();
            f.setAction(action);
            ed.logic().saveParameters("Installment_Calculation", f);
        }
        assertThat(reread(doc).element("Installment_Calculation").orElseThrow().parameters())
                .extracting(p -> p.name()).containsExactly("term", "rate");

        ItemDefinitionForm type = new ItemDefinitionForm();
        type.setName("tLoanOffer");
        type.setAction("moveComponentUp:1");
        ed.types().save("2", type);
        assertThat(reread(doc).itemDefinitions().get(2).components()).extracting(c -> c.name())
                .containsExactly("installment", "amount");
        type.setAction("deleteComponent:0");
        ed.types().save("2", type);
        assertThat(reread(doc).itemDefinitions().get(2).components()).extracting(c -> c.name())
                .containsExactly("amount");
    }

    @Test
    void addressesNestedTypesByPathAndDeletesThem() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);
        ItemDefinitionForm amount = new ItemDefinitionForm();
        amount.setName("amount");
        amount.setAction("addComponent");
        ed.types().save("2.0", amount);

        assertThat(ed.types().byPath("2.0.0").getAttribute("name")).isEqualTo("field1");
        var nested = reread(doc).itemDefinitions().get(2).components().getFirst();
        assertThat(nested.typeRef()).isNull();
        assertThat(nested.components()).singleElement().satisfies(c -> {
            assertThat(c.path()).isEqualTo("2.0.0");
            assertThat(c.typeRef()).isEqualTo("string");
        });
        for (String bad : List.of("3", "2.5", "2.0.0.0", "x", "", "-1")) {
            assertThatThrownBy(() -> ed.types().byPath(bad)).isInstanceOf(DmnEditException.class)
                    .hasMessageContaining("Unknown data type");
        }

        ed.types().delete("2.0.0");
        assertThat(reread(doc).itemDefinitions().get(2).components().getFirst().components()).isEmpty();
        ed.types().delete("0");
        DmnReader reader = reread(doc);
        assertThat(reader.itemDefinitions()).extracting(t -> t.name()).containsExactly("tEligibility", "tLoanOffer");
        assertThat(DmnValidator.validate(reader)).anyMatch(i -> i.message().equals("Unknown type 'tRiskCategory'"));
    }

    @Test
    void offersConditionalsIteratorsAndFiltersFromDmn14() {
        assertThat(LogicType.available(DmnNamespaces.DMN_1_3)).doesNotContain(LogicType.CONDITIONAL, LogicType.FILTER);
        assertThat(LogicType.available(DmnNamespaces.DMN_1_1)).doesNotContain(LogicType.FOR);
        assertThat(LogicType.available(DmnNamespaces.DMN_1_4)).contains(LogicType.CONDITIONAL, LogicType.FOR,
                LogicType.SOME, LogicType.EVERY, LogicType.FILTER);
        assertThat(LogicType.available(DmnNamespaces.DMN_1_5)).containsExactly(LogicType.values());

        DmnDocument doc = TestModels.dish();
        DmnEditor ed = new DmnEditor(doc);
        assertThatThrownBy(() -> ed.logic().setType("Dish", LogicType.CONDITIONAL))
                .isInstanceOf(DmnEditException.class).hasMessageContaining("requires DMN 1.4");
        ed.converter().toLatest();
        ed.logic().setType("Dish", LogicType.CONDITIONAL);
        assertThat(reread(doc).element("Dish").orElseThrow().logic()).isInstanceOf(ExpressionView.Conditional.class);
    }
}
