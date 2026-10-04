package heidtmare.dmnspwn.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ExpressionView;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.model.Views.ItemDefinitionView;

/** Collects the names FEEL text in a model may refer to, so that those with spaces can be backtick-quoted. */
final class ModelNames {

    private ModelNames() {
    }

    /** Every element, parameter, variable, column and type name of the model. */
    static Set<String> of(DmnReader reader) {
        Set<String> names = new HashSet<>();
        for (ElementView e : reader.elements()) {
            names.add(e.name());
            e.parameters().forEach(p -> names.add(p.name()));
            collect(e.logic(), names);
        }
        collectTypes(reader.itemDefinitions(), names);
        names.remove(null);
        return names;
    }

    private static void collectTypes(List<ItemDefinitionView> items, Set<String> names) {
        for (ItemDefinitionView item : items) {
            names.add(item.name());
            collectTypes(item.components(), names);
        }
    }

    private static void collect(ExpressionView x, Set<String> names) {
        switch (x) {
            case null -> {
            }
            case ExpressionView.DecisionTable t -> t.outputs().forEach(o -> names.add(o.name()));
            case ExpressionView.Context c -> c.entries().forEach(e -> {
                names.add(e.name());
                collect(e.value(), names);
            });
            case ExpressionView.Relation r -> {
                r.columns().forEach(c -> names.add(c.name()));
                r.rows().forEach(row -> row.forEach(cell -> collect(cell, names)));
            }
            case ExpressionView.ListExpr l -> l.items().forEach(i -> collect(i, names));
            case ExpressionView.Invocation i -> i.bindings().forEach(b -> {
                names.add(b.name());
                collect(b.value(), names);
            });
            case ExpressionView.Function f -> {
                f.parameters().forEach(p -> names.add(p.name()));
                collect(f.body(), names);
            }
            case ExpressionView.Conditional c -> {
                collect(c.condition(), names);
                collect(c.then(), names);
                collect(c.otherwise(), names);
            }
            case ExpressionView.Iterator i -> {
                names.add(i.variable());
                collect(i.collection(), names);
                collect(i.body(), names);
            }
            case ExpressionView.Filter f -> {
                collect(f.collection(), names);
                collect(f.match(), names);
            }
            default -> {
            }
        }
    }
}
