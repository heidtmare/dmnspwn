package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import heidtmare.dmnspwn.diagram.DiagramBuilder;
import heidtmare.dmnspwn.diagram.DiagramView;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.model.ExpressionView;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.validate.DmnValidator;

class DmnReaderTest {

    private final DmnReader loan = new DmnReader(TestModels.loan());
    private final DmnReader dish = new DmnReader(TestModels.dish());

    @Test
    void readsElementsAndConnections() {
        assertThat(loan.elements()).extracting(ElementView::kind).contains(ElementKind.DECISION,
                ElementKind.INPUT_DATA, ElementKind.BUSINESS_KNOWLEDGE_MODEL, ElementKind.KNOWLEDGE_SOURCE,
                ElementKind.DECISION_SERVICE, ElementKind.TEXT_ANNOTATION);
        ElementView offer = loan.element("Loan_Offer").orElseThrow();
        assertThat(offer.requires()).extracting(c -> c.kind()).containsExactlyInAnyOrder(
                ConnectionKind.INFORMATION, ConnectionKind.INFORMATION, ConnectionKind.KNOWLEDGE);
        assertThat(loan.element("Risk_Category").orElseThrow().requires())
                .anyMatch(c -> c.kind() == ConnectionKind.AUTHORITY && c.sourceId().equals("Lending_Policy"));
        assertThat(loan.connections()).anyMatch(c -> c.kind() == ConnectionKind.ASSOCIATION);
    }

    @Test
    void readsBoxedExpressions() {
        var table = (ExpressionView.DecisionTable) loan.element("Risk_Category").orElseThrow().logic();
        assertThat(table.hitPolicyCode()).isEqualTo("U");
        assertThat(table.inputs()).hasSize(2);
        assertThat(table.annotations()).containsExactly("Rationale");
        assertThat(table.rules()).hasSize(6);
        assertThat(table.rules().getFirst().inputs()).containsExactly("< 18", "-");

        var context = (ExpressionView.Context) loan.element("Loan_Offer").orElseThrow().logic();
        assertThat(context.entries()).hasSize(3);
        assertThat(context.entries().getFirst().value()).isInstanceOf(ExpressionView.Invocation.class);
        assertThat(context.entries().getLast().name()).isNull();

        ElementView bkm = loan.element("Installment_Calculation").orElseThrow();
        assertThat(bkm.signature()).isEqualTo("Installment Calculation(amount: number, rate: number, term: number)");

        assertThat(dish.element("Seasonal_Menu").orElseThrow().logic()).isInstanceOf(ExpressionView.Relation.class);
        var beverages = (ExpressionView.DecisionTable) dish.element("Beverages").orElseThrow().logic();
        assertThat(beverages.hitPolicyCode()).isEqualTo("C");
    }

    @Test
    void readsNestedItemDefinitions() {
        assertThat(loan.itemDefinitions()).hasSize(3);
        var offer = loan.itemDefinitions().get(2);
        assertThat(offer.summary()).isEqualTo("structure");
        assertThat(offer.components()).extracting(c -> c.path()).containsExactly("2.0", "2.1");
    }

    @Test
    void buildsDiagramFromDmndi() {
        DiagramView view = DiagramBuilder.build(loan, null);
        assertThat(view.fromDmndi()).isTrue();
        assertThat(view.nodes()).hasSize(loan.elements().size());
        assertThat(view.edges()).hasSize(loan.connections().size());
        assertThat(view.containers()).hasSize(1);
    }

    @Test
    void laysOutModelsWithoutDmndi() {
        DiagramView view = DiagramBuilder.build(dish, null);
        assertThat(view.fromDmndi()).isFalse();
        assertThat(view.nodes()).hasSize(dish.elements().size());
        var beverages = view.nodes().stream().filter(n -> n.elementId().equals("Beverages")).findFirst().orElseThrow();
        var dishNode = view.nodes().stream().filter(n -> n.elementId().equals("Dish")).findFirst().orElseThrow();
        assertThat(beverages.bounds().y()).isLessThan(dishNode.bounds().y());
    }

    @Test
    void samplesValidateWithoutErrors() {
        assertThat(DmnValidator.validate(loan)).noneMatch(i -> i.isError());
        assertThat(DmnValidator.validate(dish)).noneMatch(i -> i.isError());
    }
}
