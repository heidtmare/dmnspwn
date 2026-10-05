package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import heidtmare.dmnspwn.eval.Evaluation;
import heidtmare.dmnspwn.eval.Evaluation.DecisionResult;
import heidtmare.dmnspwn.eval.Feel;
import heidtmare.dmnspwn.eval.ModelEvaluator;
import heidtmare.dmnspwn.eval.Scope;
import heidtmare.dmnspwn.eval.Values;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.xml.DmnDocument;

class EvaluatorTest {

    private static final Feel FEEL = new Feel();

    private static ModelEvaluator evaluator(DmnDocument doc) {
        return new ModelEvaluator(new DmnReader(doc), FEEL);
    }

    private static DecisionResult decision(Evaluation e, String id) {
        return e.decisions().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void evaluatesLoanSample() {
        Evaluation e = evaluator(TestModels.loan()).evaluate(Map.of(
                "Applicant_Age", "30", "Credit_Score", "750", "Monthly_Income", "4000",
                "Requested_Amount", "10000"), List.of(), null);

        assertThat(e.hasInputErrors()).isFalse();
        DecisionResult risk = decision(e, "Risk_Category");
        assertThat(risk.formatted()).isEqualTo("\"Low\"");
        assertThat(risk.matchedRules()).containsExactly(6);
        assertThat(decision(e, "Eligibility").formatted()).isEqualTo("\"Eligible\"");
        assertThat(decision(e, "Eligibility").matchedRules()).containsExactly(3, 4);
        DecisionResult offer = decision(e, "Loan_Offer");
        assertThat(offer.messages()).isEmpty();
        assertThat(offer.formatted()).startsWith("{amount: 10000, installment: 299.7089");
    }

    @Test
    void evaluatesDishSampleWithNamesContainingSpaces() {
        Evaluation e = evaluator(TestModels.dish()).evaluate(Map.of(
                "Season", "\"Fall\"", "Guest_Count", "3", "Guests_With_Children", "true"), List.of("Beverages"), null);

        assertThat(decision(e, "Party_Size").formatted()).isEqualTo("\"small\"");
        assertThat(decision(e, "Dish").formatted()).isEqualTo("\"Spareribs\"");
        DecisionResult beverages = decision(e, "Beverages");
        assertThat(beverages.requested()).isTrue();
        assertThat(decision(e, "Dish").requested()).isFalse();
        assertThat(beverages.formatted()).isEqualTo("[\"Aecht Schlenkerla Rauchbier\", \"Apple Juice\", \"Water\"]");
        assertThat(e.decisions()).extracting(DecisionResult::id).doesNotContain("Seasonal_Menu");
    }

    @Test
    void reportsUniqueViolationsAndMissingInputs() {
        Evaluation e = evaluator(TestModels.dish()).evaluate(Map.of("Season", "\"Summer\"", "Guest_Count", "20"),
                List.of("Dish"), null);
        assertThat(decision(e, "Dish").formatted()).isEqualTo("\"Light Salad and a nice Steak\"");

        e = evaluator(TestModels.loan()).evaluate(Map.of(), List.of("Risk_Category"), null);
        DecisionResult risk = decision(e, "Risk_Category");
        assertThat(risk.formatted()).isEqualTo("null");
        assertThat(risk.matchedRules()).isEmpty();
        assertThat(risk.messages()).isNotEmpty();
    }

    @Test
    void evaluatesAdHocExpressionsAgainstTheModel() {
        Evaluation e = evaluator(TestModels.loan()).evaluate(Map.of("Requested_Amount", "1200"), List.of(),
                "Installment Calculation(Requested Amount, 0, 12) + 0.1 + 0.2");
        // rate 0 divides by zero -> null, reported
        assertThat(e.expression().formatted()).isEqualTo("null");
        assertThat(e.decisions()).isEmpty();

        e = evaluator(TestModels.loan()).evaluate(Map.of("Requested_Amount", "1200", "Applicant_Age", "17"),
                List.of(), "{offer: Installment Calculation(amount: Requested Amount, rate: 0.12, term: 12) > 100, "
                        + "risk: Risk Category, sum: 0.1 + 0.2}");
        assertThat(e.expression().formatted()).isEqualTo("{offer: true, risk: \"High\", sum: 0.3}");
        assertThat(e.decisions()).extracting(DecisionResult::id).containsExactly("Risk_Category");

        e = evaluator(TestModels.loan()).evaluate(Map.of(), List.of(), "Eligibility Service(30, 800, 100)");
        assertThat(e.expression().formatted()).isEqualTo("\"Ineligible\"");
        assertThat(e.expression().messages()).isEmpty();
    }

    @Test
    void reportsBadInputs() {
        Evaluation e = evaluator(TestModels.loan()).evaluate(Map.of("Applicant_Age", "30 +"), List.of("Risk_Category"), null);
        assertThat(e.hasInputErrors()).isTrue();
        assertThat(evaluator(TestModels.dish()).inputFields())
                .filteredOn(f -> f.id().equals("Season")).first()
                .satisfies(f -> assertThat(f.suggestions()).containsExactly("\"Spring\"", "\"Summer\"", "\"Fall\"", "\"Winter\""));
    }

    @Test
    void evaluatesBoxedExpressions() {
        String xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="Boxes" namespace="urn:boxes">
                  <decision id="Numbers" name="Numbers">
                    <variable name="Numbers"/>
                    <list>
                      <literalExpression><text>1</text></literalExpression>
                      <literalExpression><text>5</text></literalExpression>
                      <literalExpression><text>10</text></literalExpression>
                    </list>
                  </decision>
                  <decision id="Big" name="Big Ones">
                    <variable name="Big Ones"/>
                    <informationRequirement><requiredDecision href="#Numbers"/></informationRequirement>
                    <filter>
                      <in><literalExpression><text>Numbers</text></literalExpression></in>
                      <match><literalExpression><text>item &gt; 2</text></literalExpression></match>
                    </filter>
                  </decision>
                  <decision id="Doubled" name="Doubled">
                    <variable name="Doubled"/>
                    <informationRequirement><requiredDecision href="#Big"/></informationRequirement>
                    <for iteratorVariable="n">
                      <in><literalExpression><text>Big Ones</text></literalExpression></in>
                      <return><literalExpression><text>n * 2</text></literalExpression></return>
                    </for>
                  </decision>
                  <decision id="Check" name="Check">
                    <variable name="Check"/>
                    <informationRequirement><requiredDecision href="#Doubled"/></informationRequirement>
                    <conditional>
                      <if><literalExpression><text>sum(Doubled) &gt; 20</text></literalExpression></if>
                      <then><literalExpression><text>"big"</text></literalExpression></then>
                      <else><literalExpression><text>"small"</text></literalExpression></else>
                    </conditional>
                  </decision>
                  <decision id="Total" name="Total">
                    <variable name="Total"/>
                    <decisionTable hitPolicy="COLLECT" aggregation="SUM">
                      <input><inputExpression><text>3</text></inputExpression></input>
                      <output name="v"/>
                      <rule><inputEntry><text>&gt; 1</text></inputEntry><outputEntry><text>10</text></outputEntry></rule>
                      <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>5</text></outputEntry></rule>
                      <rule><inputEntry><text>&lt; 1</text></inputEntry><outputEntry><text>100</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                  <decision id="Ranked" name="Ranked">
                    <variable name="Ranked"/>
                    <decisionTable hitPolicy="PRIORITY">
                      <input><inputExpression><text>3</text></inputExpression></input>
                      <output name="v"><outputValues><text>"high","medium","low"</text></outputValues></output>
                      <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"low"</text></outputEntry></rule>
                      <rule><inputEntry><text>3</text></inputEntry><outputEntry><text>"medium"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                  <decision id="Clash" name="Clash">
                    <variable name="Clash"/>
                    <decisionTable>
                      <input><inputExpression><text>3</text></inputExpression></input>
                      <output name="v"/>
                      <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>1</text></outputEntry></rule>
                      <rule><inputEntry><text>3</text></inputEntry><outputEntry><text>2</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                </definitions>
                """;
        Evaluation e = evaluator(DmnDocument.parse(xml)).evaluate(Map.of(), List.of(), null);
        assertThat(decision(e, "Big").formatted()).isEqualTo("[5, 10]");
        assertThat(decision(e, "Doubled").formatted()).isEqualTo("[10, 20]");
        assertThat(decision(e, "Check").formatted()).isEqualTo("\"big\"");
        assertThat(decision(e, "Total").formatted()).isEqualTo("15");
        assertThat(decision(e, "Ranked").formatted()).isEqualTo("\"medium\"");
        assertThat(decision(e, "Clash").formatted()).isEqualTo("null");
        assertThat(decision(e, "Clash").hasErrors()).isTrue();
    }

    @Test
    void rejectsInputsThatDoNotConformToTheirType() {
        Evaluation e = evaluator(TestModels.loan()).evaluate(Map.of(
                "Applicant_Age", "\"30\"", "Credit_Score", "750"), List.of("Risk_Category"), null);
        assertThat(e.inputs()).filteredOn(i -> i.id().equals("Applicant_Age")).first().satisfies(i -> {
            assertThat(i.error()).contains("string \"30\" is not a number");
            assertThat(i.formatted()).isEqualTo("null");
        });

        e = evaluator(TestModels.dish()).evaluate(Map.of("Season", "\"Monsoon\"", "Guest_Count", "[3]"),
                List.of("Dish"), null);
        assertThat(e.inputs()).filteredOn(i -> i.id().equals("Season")).first()
                .satisfies(i -> assertThat(i.error()).contains("not in the allowed values of tSeason"));
        // a singleton list converts to its item
        assertThat(e.inputs()).filteredOn(i -> i.id().equals("Guest_Count")).first().satisfies(i -> {
            assertThat(i.error()).isNull();
            assertThat(i.formatted()).isEqualTo("3");
        });
    }

    @Test
    void checksResultsAgainstTypesAndTableValues() {
        String xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="Types" namespace="urn:types">
                  <itemDefinition name="tGrade">
                    <typeRef>string</typeRef>
                    <allowedValues><text>"A","B"</text></allowedValues>
                  </itemDefinition>
                  <itemDefinition name="tScores" isCollection="true">
                    <typeRef>number</typeRef>
                    <allowedValues><text>[0..100]</text></allowedValues>
                  </itemDefinition>
                  <itemDefinition name="tPerson">
                    <itemComponent name="name"><typeRef>string</typeRef></itemComponent>
                    <itemComponent name="age"><typeRef>number</typeRef></itemComponent>
                  </itemDefinition>
                  <decision id="Grade" name="Grade">
                    <variable name="Grade" typeRef="tGrade"/>
                    <literalExpression><text>"C"</text></literalExpression>
                  </decision>
                  <decision id="Scores" name="Scores">
                    <variable name="Scores" typeRef="tScores"/>
                    <literalExpression><text>[50, 101]</text></literalExpression>
                  </decision>
                  <decision id="One" name="One">
                    <variable name="One" typeRef="tScores"/>
                    <literalExpression><text>7</text></literalExpression>
                  </decision>
                  <decision id="Person" name="Person">
                    <variable name="Person" typeRef="tPerson"/>
                    <literalExpression><text>{name: "Ann", age: "old", extra: 1}</text></literalExpression>
                  </decision>
                  <decision id="Fine" name="Fine">
                    <variable name="Fine" typeRef="tPerson"/>
                    <literalExpression><text>{name: "Ann", age: 30, extra: 1}</text></literalExpression>
                  </decision>
                  <decision id="Day" name="Day">
                    <variable name="Day" typeRef="date and time"/>
                    <literalExpression><text>date("2024-01-31")</text></literalExpression>
                  </decision>
                  <decision id="BadInput" name="BadInput">
                    <variable name="BadInput"/>
                    <decisionTable>
                      <input><inputExpression typeRef="number"><text>5</text></inputExpression>
                        <inputValues><text>[1..3]</text></inputValues></input>
                      <output name="v"/>
                      <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>1</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                  <decision id="BadOutput" name="BadOutput">
                    <variable name="BadOutput"/>
                    <decisionTable>
                      <input><inputExpression><text>5</text></inputExpression></input>
                      <output name="v" typeRef="string"><outputValues><text>"x","y"</text></outputValues></output>
                      <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"z"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                  <businessKnowledgeModel id="Twice" name="Twice">
                    <variable name="Twice"/>
                    <encapsulatedLogic>
                      <formalParameter name="n" typeRef="number"/>
                      <literalExpression><text>if n = null then "null" else n * 2</text></literalExpression>
                    </encapsulatedLogic>
                  </businessKnowledgeModel>
                  <decision id="Call" name="Call">
                    <variable name="Call"/>
                    <knowledgeRequirement><requiredKnowledge href="#Twice"/></knowledgeRequirement>
                    <literalExpression><text>Twice("2")</text></literalExpression>
                  </decision>
                </definitions>
                """;
        Evaluation e = evaluator(DmnDocument.parse(xml)).evaluate(Map.of(), List.of(), null);
        assertThat(decision(e, "Grade").formatted()).isEqualTo("null");
        assertThat(decision(e, "Grade").messages()).extracting(Evaluation.Message::text)
                .containsExactly("Result: string \"C\" is not in the allowed values of tGrade (\"A\",\"B\")");
        assertThat(decision(e, "Scores").formatted()).isEqualTo("null");
        assertThat(decision(e, "Scores").messages().getFirst().text()).contains("item 2 of tScores");
        assertThat(decision(e, "One").formatted()).isEqualTo("[7]");
        assertThat(decision(e, "Person").formatted()).isEqualTo("null");
        assertThat(decision(e, "Person").messages().getFirst().text()).contains("tPerson.age: string \"old\" is not a number");
        assertThat(decision(e, "Fine").hasErrors()).isFalse();
        assertThat(decision(e, "Day").formatted()).isEqualTo("date and time(\"2024-01-31T00:00:00Z\")");
        assertThat(decision(e, "BadInput").formatted()).isEqualTo("null");
        assertThat(decision(e, "BadInput").matchedRules()).isEmpty();
        assertThat(decision(e, "BadInput").messages().getFirst().text()).contains("Input 1: number 5 is not in the input values");
        assertThat(decision(e, "BadOutput").formatted()).isEqualTo("null");
        assertThat(decision(e, "BadOutput").messages().getFirst().text()).contains("Rule 1, output 1: string \"z\" is not in the output values");
        assertThat(decision(e, "Call").formatted()).isEqualTo("\"null\"");
        assertThat(decision(e, "Call").messages().getFirst().text()).contains("Parameter 'n': string \"2\" is not a number");
    }

    @Test
    void quotesNamesOutsideStrings() {
        Feel.Result r = FEEL.evaluate("Guest Count + 1", Scope.root(Feel.QuotedNames.of(List.of("Guest Count")))
                .put("Guest Count", Values.number(2)));
        assertThat(Values.format(r.value())).isEqualTo("3");
        r = FEEL.evaluate("\"Guest Count\" + \"!\"", Scope.root(Feel.QuotedNames.of(List.of("Guest Count"))));
        assertThat(Values.format(r.value())).isEqualTo("\"Guest Count!\"");
        assertThat(FEEL.syntaxError("1 +", List.of())).isNotNull();
    }
}
