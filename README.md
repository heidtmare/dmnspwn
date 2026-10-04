<p align="center"><img src="src/main/resources/static/images/dmnspwn-icon.png" alt="dmnspwn logo" width="160"></p>

# dmnspwn

A server-side rendered viewer and editor for OMG **Decision Model and Notation** (DMN 1.1 – 1.5) models.
Java 21, Spring Boot 4.1, Spring MVC + Thymeleaf. No front-end build; the only JavaScript is an optional
drag-and-drop enhancement for the diagram in edit mode.

![dmnspwn demo: browsing a DRD, viewing a decision table, evaluating decisions, editing the diagram and validating](docs/demo.gif)

## Run

```bash
mvn spring-boot:run          # http://localhost:8080
mvn test                     # unit + MockMvc tests
mvn package && java -jar target/dmnspwn-0.1.0-SNAPSHOT.jar
```

Models are stored as plain `.dmn` files in `./dmn-models` (two samples are copied there on first start).
Configure with `dmnspwn.storage-directory`, `dmnspwn.seed-samples`, `dmnspwn.history-size`.

## Loading from and publishing to AWS S3

Disabled by default. Enable it with configuration (or the matching `DMNSPWN_S3_*` environment variables):

```yaml
dmnspwn:
  s3:
    enabled: true
    bucket: my-decision-models
    prefix: dmn/           # the app only reads and writes keys under this prefix
    region: eu-west-1      # optional; defaults to the SDK region chain
    # endpoint: http://localhost:9000   # S3-compatible stores (MinIO, LocalStack)
    # path-style: true
```

Credentials come from the AWS SDK default provider chain (`AWS_*` environment variables, `~/.aws` profiles / SSO,
ECS/EKS/EC2 roles). Required IAM actions on the bucket/prefix: `s3:ListBucket`, `s3:GetObject`, `s3:PutObject`.

- **Load from S3** (home page) browses the prefix and imports `.dmn` / `.xml` objects as local models.
- Each model gets an **S3** tab showing its linked object, whether the local copy or the S3 object changed
  since the last sync, and actions to **Publish**, **Pull** (replace local, undoable) and **Unlink**.
- Publishing uses S3 conditional writes: on the linked key it requires the ETag from the last sync
  (`If-Match`), on any other key it requires that no object exists (`If-None-Match: *`). A conflict is
  reported with an explicit *Overwrite* option instead of silently replacing someone else's change.
- Reloading an object never discards unpublished local edits.
- Enable bucket versioning if you want server-side history of published versions.

## Features

- **DRD rendering** – decision requirements diagrams rendered on the server as SVG with the DMN notation
  (decision, input data, BKM, knowledge source, decision service with divider, text annotation; information /
  knowledge / authority requirements and associations). Uses DMNDI when present (multiple DRDs supported),
  otherwise an automatic layered layout. Standalone SVG export.
- **Boxed expressions** – decision tables (hit policy notation, input/output values, annotations), literal
  expressions, contexts, relations, lists, invocations, function definitions, and the DMN 1.4+ conditional,
  `for` / `some` / `every` iterators and filter.
- **Editing** (toggle *View / Edit* in the header)
  - add / rename / delete elements; requirements created according to the DMN connection rules
    (with duplicate and cycle checks)
  - drag shapes to move them, Shift+drag between shapes to connect; or use the plain HTML forms
  - full decision-table editor (hit policy, aggregation, inputs, outputs, annotations, rules: insert,
    duplicate, reorder, delete)
  - literal expression editor; XML editor for any boxed expression type
  - BKM parameters, decision service membership, item definitions (incl. nested components)
  - whole-document XML source editing, undo history, version conversion to DMN 1.5
- **Evaluation** (*Evaluate* tab, or *Evaluate* on a decision) – runs decisions for input values written as
  FEEL expressions and shows each result, warnings/errors, and the decision-table rules that matched. Decision
  tables (all hit policies and aggregations), literal expressions, contexts, relations, lists, invocations,
  function definitions, conditionals, iterators and filters are evaluated; BKMs and decision services are
  callable as functions. An optional ad-hoc FEEL expression can refer to any input, decision, BKM or decision
  service by name. Nothing is written to the model.
- **Validation** – duplicate ids, missing names, dangling references, illegal requirements, cycles,
  decision table shape, unknown types, orphan DMNDI shapes.

## Design notes

- The DOM is the source of truth (`xml.DmnDocument`). Edits mutate the DOM in place and insert children in
  XSD sequence order, so extension elements, vendor attributes and comments survive a round trip.
- Read-only view records (`model.Views`, `model.ExpressionView`) are built per request by `model.DmnReader`
  and rendered by Thymeleaf; recursive boxed expressions use a recursive fragment.
- XML parsing disables DTDs and external entities.
- Intended as a local / trusted-network tool: there is no authentication or CSRF protection. Add Spring
  Security before exposing it more widely.
- FEEL is evaluated by the [FEEL Scala engine](https://github.com/camunda/feel-scala) (Apache 2.0; it brings
  the Scala runtime, Apache 2.0, and fastparse, MIT). It is used only for FEEL text; the boxed expressions and
  DRG wiring are interpreted in `eval.Interpreter` / `eval.ModelEvaluator`, with the engine's own values kept
  between steps so numbers stay decimal. DMN names may contain spaces (`Guest Count`), which the engine
  only accepts in backticks, so names known in the model are backtick-quoted before parsing.
- Evaluation limits: types are not checked or coerced against `typeRef` / allowed values, imported elements
  are not resolved, and Java / PMML function kinds are not executed.

Specifications: <https://www.omg.org/spec/DMN>
