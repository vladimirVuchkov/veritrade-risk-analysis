# 0006 - Risk rules in YAML

## Context
The assignment is about the design of the distributed system, not about the quality of the analysis.
The rules should still be easy to read, review and extend without a code change.

## Decision
- The rules live in
  [`analysis-service/src/main/resources/risk-rules.yml`](../../analysis-service/src/main/resources/risk-rules.yml):
  a `rulesVersion` and a list of rules. Each rule has an id (for example `FIN-001`), a category, a
  severity and one or more Java regular expressions, matched case-insensitively.
- The file ships 33 rules covering all 6 categories and all 4 severities.
- `RuleLoader` validates the whole file at startup: shape, id format, duplicate ids, unknown
  categories and severities, invalid regexes, and patterns that match empty text. On any error the
  service refuses to start.
- The engine (`RuleMatcher`, `ExcerptExtractor`, `RiskScorer`, `RiskAnalyzer`) is pure Java, with
  no Spring and no broker.

## Consequences
- A rule change is a text change, reviewed like code. `rulesVersion` is reported with every result.
- A broken rules file fails at startup instead of producing wrong reports.
- A rule change still needs a redeploy. The file is on the classpath; its location can be changed
  with `veritrade.analysis.rules.location`.
- Keyword and regex matching is simple and explainable. It is not semantic analysis.

## Update (Wave 3)
- A space in a pattern now means "any run of whitespace": `RuleLoader` rewrites every run of literal
  spaces outside a character class into `[\h\v]+`, which also matches line breaks, tabs and the
  no-break space. A space inside `[...]` or `\Q...\E` is rejected at startup. The text is not
  normalised, so positions and excerpts still point into the original content.
- Because matching finds more, `rulesVersion` went from `"1.0"` to `"1.1"`, together with the contract
  example `analysis-completed.json`.
