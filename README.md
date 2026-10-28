# canvaslms

An interactive Clojure CLI for transferring grades from AWS Academy to your school's Canvas course. Aula Virtual is the authoritative student roster. Students are matched by login ID, and every remote grade change is previewed and explicitly confirmed.

## Setup

Requires Java 17 or newer and [Leiningen](https://leiningen.org/).

1. Create a personal access token on each Canvas instance, using an account that can read the course roster and grades. Your Aula Virtual account must also be allowed to manage grades and add submission comments. Token availability and permissions depend on the institution.
2. Copy `.env.example` to `.env` and fill in your URLs and tokens:

   ```dotenv
   AULA_VIRTUAL_URL=https://aulavirtual.espol.edu.ec
   AULA_VIRTUAL_TOKEN=your-school-token
   AWS_ACADEMY_URL=https://awsacademy.instructure.com
   AWS_ACADEMY_TOKEN=your-academy-token
   CANVASLMS_DB_PATH=./data/canvaslms.sqlite
   CANVASLMS_TIMEZONE=America/Guayaquil
   ```

3. Download dependencies and inspect help:

   ```sh
   lein deps
   lein cli --help
   ```

Environment variables override `.env`. URLs must be HTTPS origins, without `/api/v1` or other paths. URLs and access tokens are not hard coded. Tokens are never saved in SQLite or included in error messages. `.env` and database files are ignored by Git.

The SQLite file contains student identities, course relationships, mappings, and grading history. Back it up and handle it as student data. Use the same database path to retain history across invocations. Only one CLI process can use that database at a time.

## Find and select options

Use `lein cli` for interactive commands. It is an alias for `lein trampoline run`, which gives the app direct terminal input. The standalone JAR supports the same picker.

Every course, assignment, registered-course, and saved-homework selector has a live **Search / ID** field. Type a name, ID, or words from the displayed columns to filter results immediately. Matching ignores case, accents, and punctuation. Multiple search words must all match. Exact selection-ID matches appear first, so you can type a known ID and press Enter directly.

| Key | Action |
|---|---|
| Tab or Down | Highlight the next matching option; wrap at the end |
| Shift+Tab or Up | Highlight the previous matching option |
| Enter | Select the highlighted option |
| Backspace | Delete the last search character |
| Ctrl+U | Clear the search |
| Escape or Ctrl+C | Cancel the picker |

The picker shows up to ten options in a window that follows the highlight and adjusts to terminal size. Editing the search resets the highlight to the first match. With no matches, Enter does nothing until you change the search. `q` is searchable text in a live picker, so you can search for “quiz”; ordinary prompts still accept `q` to cancel.

Existing `lein run` commands remain supported. When direct terminal input is unavailable—for example, through Leiningen's subprocess forwarding, redirected input, or a basic terminal—the app automatically uses the original table and manual-ID prompt. No external search program is required.

## Register courses and match students

```sh
lein cli register
```

Search for a school course and an AWS course, then select with Tab and Enter or type their displayed IDs. The CLI fetches both rosters, shows student names and matching results, and asks before saving locally. You can repeat this for all three AWS courses, or any number of source courses.

- The school roster and full names are authoritative.
- Login IDs are matched after trimming whitespace and ignoring case. Display names are for human verification only.
- Duplicate or unavailable logins remain unresolved. No fuzzy name matching or email substitution occurs.
- Students absent from AWS are skipped. Students found only in AWS are ignored.
- Running `register` again refreshes existing relationships rather than duplicating them.
- Registration never changes Canvas enrollments. Grading also refreshes the rosters to discover newly registered students.

## Map homework

```sh
lein cli homework
```

Select the school assignment, a registered AWS course, and one gradeable AWS activity. Knowledge checks, quizzes, and labs are selected through their Canvas gradebook assignments.

Confirm the source and destination maximum points, then enter two late-penalty percentages. Canvas maximums are prefilled; explicit local overrides are allowed. The app uses **Available until** (`lock_at`), never the due date (`due_at`). If the base cutoff is missing, enter a local fallback in `YYYY-MM-DD HH:mm` format using the configured timezone, or ISO 8601 with a UTC offset. This does not change Canvas dates.

Individual student cutoff overrides take priority. Applicable section cutoffs use the most lenient date, including an explicit removal of a cutoff; a missing effective cutoff needs the configured local fallback. Unsupported group or differentiation-tag availability overrides are skipped rather than guessed.

One school assignment has one active mapping to one AWS activity. To edit a saved mapping, select its destination assignment again. Earlier mapping versions remain in SQLite.

## Preview and apply grades

Start with a read-only Canvas preview:

```sh
lein cli grade --dry-run
```

Choose a saved mapping. The preview includes every school student, current and proposed grades, selected attempts, timestamps, effective cutoffs, deductions, comments, and skip reasons. A dry run makes no remote changes; it can update local recovery records when remote evidence proves a previous write succeeded.

The homework picker includes a **Graded** column beside the ID. **Yes** means a confirmed grading run exists in local history, including partial runs and runs from earlier mapping versions. **No** means no confirmed run has been recorded. Dry runs and canceled previews leave this status unchanged.

To apply changes:

```sh
lein cli grade
```

Each replacement of an existing different grade requires its own approval. The CLI then shows the approved batch and asks for final confirmation. Confirmations default to **No**. Escape or Ctrl+C cancels a live picker; `q` cancels ordinary prompts and plain ID selectors. EOF also cancels. There is no automatic `--yes` option.

### Calculation

```text
converted = AWS score / AWS maximum × school maximum
final     = converted × (1 − late penalty / 100)
```

For example, `44/55` becomes `16/20`. A 20% late penalty produces `12.80/20`.

- Select the highest **graded attempt**, with that attempt's own submission time. Equal scores prefer the earliest attempt.
- At or before the effective cutoff: no deduction.
- Later than the cutoff through exactly 14 elapsed days: the first percentage.
- More than 14 elapsed days: the second percentage. The percentages are not cumulative.
- Use exact rational arithmetic for conversion and deductions, then round the final result half up to at most two decimals. The result stays between zero and the configured destination maximum.
- Registered students with genuinely unsubmitted work receive a proposed zero only after their cutoff. Pending grading is skipped.
- Absent students, excused work, ambiguous identities, unavailable submissions, incomplete attempt histories, or missing submission times are skipped.

Every applied late deduction includes a Spanish comment explaining the calculation and dates. Missing-work zeros also include a Spanish explanation. Grades and comments are sent together in one request per student. Each written submission's late-policy status becomes `none` so Canvas does not apply an additional automatic deduction; this is disclosed before confirmation. The course's grade-posting policy is preserved, so manual posting may still be necessary to make grades/comments visible to students.

The app does not create assignments, change due dates or enrollments, or write to AWS Academy. Moderated grades, discussion checkpoints, and group assignments graded as a group are excluded from mapping selection.

### AWS lab and quiz compatibility

AWS Academy activities must expose a reliable graded attempt and submission timestamp through the Canvas API. Classic quiz history and quiz-submission data are read separately to avoid pairing an aggregate score with the wrong attempt time. A lab that reports a score without a submission time is displayed as skipped; the app never invents a date or substitutes `graded_at`.

Before the first live transfer, verify with `grade --dry-run` that your tokens expose login IDs, all expected students, source scores, attempt histories, and timestamps. Institution-specific compatibility cannot be confirmed using mocked tests alone.

### Recovery and audit history

Before writing, the CLI refreshes the proposal and rechecks each destination submission. Changed sources, cutoffs, mappings, or destination grades require a new preview. Canvas does not provide an atomic transaction across the two instances; external edits immediately after a check cannot be completely prevented.

SQLite records every approved proposal, per-student outcome, returned submission, and comment ID. Writes are sequential. Safe reads and explicit rate-limit responses have bounded retries; ambiguous writes are never blindly repeated. An authentication error or unknown write outcome stops subsequent writes in that run.

Run `grade` again after a partial failure. The CLI compares uncertain outcomes with current grades and stable comment references. Confirmed successes are not repeated, and existing matching comments are not reposted. If the outcome remains uncertain, the CLI skips that operation unless you inspect Canvas and explicitly authorize a retry. A fresh preview and normal final confirmation are still required.

To inspect local audit records with SQLite:

```sh
sqlite3 data/canvaslms.sqlite 'SELECT id, mapping_id, status, created_at FROM runs ORDER BY created_at DESC;'
sqlite3 data/canvaslms.sqlite 'SELECT run_id, user_id, status, result FROM run_rows ORDER BY rowid DESC;'
```

Domain snapshots use Clojure EDN in SQLite text columns, preserving decimal precision and immutable proposal data. Indexed relational columns identify courses, students, versions, operations, and runs. Tokens are not part of these snapshots.

## Code layout

```text
src/canvaslms/
  api/       Canvas HTTP client and ready-to-use endpoint functions
  model/     Domain validation and SQLite persistence
  core/      Matching, pure grading rules, and workflow orchestration
  cli/       Live search pickers, terminal input, prompts, tables, and commands
  config.clj Environment / .env configuration
  core.clj   Main entrypoint
resources/migrations/  Versioned SQLite schema
test/canvaslms/        Mocked API, calculation, persistence, and workflow tests
```

The CLI calls core workflows; core coordinates API and model functions. API/model code never prompts or prints. Pure grade calculations can be exercised without a database or network.

## Development and packaging

```sh
lein test
lein uberjar
java -jar target/uberjar/canvaslms-0.1.0-SNAPSHOT-standalone.jar --help
```

Tests use mocked Canvas responses and temporary SQLite files. They never use your personal access tokens or modify live grades.

The agreed design is in [doc/implementation-plan.md](doc/implementation-plan.md).

## License

EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0. See [LICENSE](LICENSE).
# canvas-client
