# Evaluation suite

Three suites — extraction, categorization, query — each scored on two case sets, against a
non-LLM baseline, with every failure classified and every suite run five times.

```powershell
docker compose -f docker-compose.prod.yml -f docker-compose.eval.yml --env-file .env up -d --build
./eval.ps1                                 # everything: ~100 min, ~1300 model calls
./eval.ps1 -BaselinesOnly                  # no model calls, a few seconds
./eval.ps1 -Only query -Set test -Runs 1   # one suite, one set, one run
```

One command, one dated file in `results/` plus `results/latest.json`, written after **every**
run so a stopped shell costs one run rather than all of them.

## Budgeting a run against the free tier

The Gemini free tier allows **500 `generateContent` requests per day per model**. The full
suite at five runs needs about 1,300:

| Suite | Cases (dev + test) | Calls per case | x5 runs |
|-------|--------------------|----------------|---------|
| extraction     | 44 + 42 | 1 | 430 |
| categorization | 31 + 32 | 1 | 315 |
| query          | 13 + 15 | 3-4 (router, embedding, SQL, summary) | ~560 |

So a complete five-run suite is **two to three days of free quota**, and it cannot be done in
one sitting. Plan around it: `-Runs 1` for a single sample after a prompt change (~260 calls,
inside one day), `-Only`/`-Set` to spend the budget where a change could have had an effect, and
`-BaselinesOnly` whenever the question is about the baseline rather than the model. The suite
writes after every run, so quota spent is never quota wasted.

When the cap is hit, the services retry three times and then return HTTP 500, which is
indistinguishable at the eval's layer from a genuine server fault. Either way the case was never
answered, so it is **not** scored: a run with more than 5% infrastructure failures is discarded
whole and recorded in `discardedRuns`, rather than averaged in as a run of wrong answers. A
quota outage that silently became a 0% headline is exactly the failure this guard exists for.

## dev and test

`cases/dev/` holds the cases written while the prompts were being built. Failures in those cases
were used to change prompts — the Priya Sharma miss produced the "Needs Manual Categorization"
wording, the flight question produced the "categories are broad" paragraph in the SQL prompt.
**The dev score is contaminated and is an upper bound.**

`cases/test/` is held out. It was written after the prompts were frozen, from bank SMS templates
and question phrasings that do not appear in dev: thirteen banks dev never mentions plus the
five dev banks in other templates they really send, merchants dev never names, and colloquial
re-phrasings of questions dev asks plainly. **No failure in `test` has ever been used to change
a prompt, and none may be. Tune on dev, score on test, never the reverse.** A gap between the
two is a finding about how far the prompts generalise, not a defect in the test set.

The rule works in the intended direction too. The categorization prompt was changed to make the
direction of a person-to-person payment decide the category, and the two cases measuring it —
`direction-debit-meera` and `direction-credit-meera`, the same name and amount with opposite
`transactionType` — were added to **dev**, not to the held-out set.

## What is measured

**Extraction** sends each SMS to `POST /api/extract` — the same `GeminiClient.analyze` the Kafka
listener calls. Scored per field (amount, merchant, transactionType, transactionDate,
accountLast4), not per record, because "the date is wrong" and "nothing was extracted" are
different failures. Non-transaction rejection is counted separately.

**Categorization** sends merchant/amount/type triples to `POST /api/categorize`, which runs the
same `CategorizationDecider` the listener runs. Extraction stays out of the loop: through the
pipeline a wrong merchant and a wrong category are indistinguishable. Reports accuracy, a
confusion matrix, and calibration split two ways — how many flagged cases were genuinely wrong,
and how many confident ones were. The second is the number that matters.

**Query** asks each question and scores routing, aggregation and resolution independently, so a
right answer found the wrong way and a wrong answer found the right way are distinguishable.
Routing is scored on the *router's* decision: a question routed to SQL that found no rows and
fell back to retrieval counts as correctly routed and is recorded separately as a SQL failure.

Both evals call components directly rather than pushing SMS through the pipeline. That is one
model call per case instead of four, which is what makes five runs affordable, and it isolates
the component under test.

## How ground truth was established

Extraction and categorization labels are hand-written, and each case records the bank template
or merchant it came from in a `source` field. Categorization cases may declare
`acceptableAlternatives` — categories a careful human could also defend — which are fixed before
any run so the error taxonomy cannot be fitted to the results afterwards.

Query aggregation ground truth is **hand-written SQL run against the same database at eval
time**, not a constant. A constant would go stale the first time a transaction was added, and a
stale expected value fails a correct system.

## Baselines

Every score has something to beat, all three deterministic and free to run:

- **Extraction** — a regex reader. Amount, account digits and direction are genuinely
  regex-able from these templates; merchant and date are where it is expected to struggle.
- **Categorization** — a keyword lookup table from merchant substring to category.
- **Query routing** — does the question contain "how much", "total", "how many", "average"? The
  baseline cannot produce a figure at all, so it scores zero on aggregation and resolution by
  construction, which is itself the argument for the SQL path.

Where a baseline is competitive, the README says so rather than burying it.

## Known limitations

The SMS are synthetic, written to documented bank templates rather than taken from anyone's
phone. Real messages carry typos, truncation and regional-language fragments that none of these
have.

The sets are small — 86 extraction cases, 63 categorization, 28 questions — so a single case is
worth one to three percentage points and confidence intervals would be wide. Five runs measure
the model's variance, not the sampling error from a small set.

The baseline regexes and the keyword table were written with both case sets visible. That
flatters the baseline rather than the model, so it makes the comparison harder, not easier — but
it means the baseline's dev-versus-test gap is not evidence of anything.

The query suite runs against whatever the database holds, so its absolute figures move as data
is added; each results file records the dataset size it ran against.

Nothing here measures the pipeline end to end — whether Kafka delivers, whether storage commits,
whether the fan-out reaches both consumers. That is verified by hand, by posting an SMS and
following one `correlationId` across all seven service logs, not by this suite.
