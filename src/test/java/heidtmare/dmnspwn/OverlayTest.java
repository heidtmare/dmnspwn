package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import heidtmare.dmnspwn.diagram.Overlay;
import heidtmare.dmnspwn.diagram.Overlay.Status;
import heidtmare.dmnspwn.eval.Evaluation;
import heidtmare.dmnspwn.eval.Evaluation.DecisionResult;
import heidtmare.dmnspwn.eval.Evaluation.InputResult;
import heidtmare.dmnspwn.eval.Trace;
import heidtmare.dmnspwn.eval.Values;

class OverlayTest {

    private static DecisionResult decision(String id, Trace.Message... messages) {
        return new DecisionResult(id, id, null, Values.number(1), List.of(messages), List.of(), false, true);
    }

    @Test
    void marksInputsAndDecisionsWithTheirStatus() {
        Evaluation result = new Evaluation(
                List.of(new InputResult("in1", "In 1", "1", Values.number(1), null),
                        new InputResult("in2", "In 2", "oops", null, "bad input")),
                List.of(decision("ok"), decision("warn", new Trace.Message(false, "careful")),
                        decision("err", new Trace.Message(true, "failed")),
                        decision("diff", new Trace.Message(false, "note"))),
                null);

        Overlay overlay = Overlay.of(result, Map.of("diff", "2"));

        assertThat(overlay.mark("in1").status()).isEqualTo(Status.OK);
        assertThat(overlay.mark("in2")).isEqualTo(new Overlay.Mark(Status.ERROR, "oops", "bad input"));
        assertThat(overlay.mark("ok")).isEqualTo(new Overlay.Mark(Status.OK, "1", null));
        assertThat(overlay.mark("warn")).isEqualTo(new Overlay.Mark(Status.WARNING, "1", "careful"));
        assertThat(overlay.mark("err").status()).isEqualTo(Status.ERROR);
        assertThat(overlay.mark("diff")).isEqualTo(new Overlay.Mark(Status.MISMATCH, "1", "expected 2\nnote"));
        assertThat(overlay.mark("missing")).isNull();
    }
}
