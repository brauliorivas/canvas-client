# Interactive Canvas grade transfer CLI

## Summary

Build a Clojure CLI in the existing Leiningen project with three interactive commands:

```text
lein run register
lein run homework
lein run grade
```

Use a local SQLite database file for students, course relationships, homework mappings, and grading history. Aula Virtual supplies the authoritative roster and student names; AWS Academy supplies grades. Every remote change requires an explicit confirmation.

## Architecture and configuration

Organize namespaces under `canvaslms` into four folders:

| Layer | Responsibility |
|---|---|
| `api/` | Reusable Canvas HTTP functions, pagination, response normalization, authentication, and error handling |
| `model/` | SQLite migrations, persistence, and validated domain data represented as idiomatic Clojure maps |
| `core/` | Student matching, homework configuration, grade calculations, previews, and applying approved changes |
| `cli/` | Commands, prompts, colored tables, validation messages, and confirmations |

Dependencies flow from CLI → core → API/model. API and model never prompt or print. Grade calculations remain pure functions.

Use the existing `clj-http`, plus Cheshire for JSON, `next.jdbc` with SQLite JDBC, `clojure.tools.cli`, and `org.clj-commons/pretty` for tables and colors. Use `dotenv-java` behind a configuration namespace; environment variables override `.env`. ([Pretty](https://github.com/clj-commons/pretty), [next.jdbc](https://github.com/seancorfield/next-jdbc), [dotenv-java](https://github.com/cdimascio/dotenv-java))

Configuration:

```dotenv
AULA_VIRTUAL_URL=
AULA_VIRTUAL_TOKEN=
AWS_ACADEMY_URL=
AWS_ACADEMY_TOKEN=
CANVASLMS_DB_PATH=./data/canvaslms.sqlite
CANVASLMS_TIMEZONE=America/Guayaquil
```

Provide `.env.example`, ignore credentials and database files, and keep tokens out of SQLite and logs. Store endpoint identities with saved relationships to prevent reusing course IDs against a different instance.

## Interactive workflows

### `register`

1. List accessible Aula Virtual and AWS courses with IDs and names.
2. Select the Aula Virtual course and one AWS course.
3. Fetch complete student rosters; deduplicate students enrolled in multiple sections.
4. Match by trimmed, case-insensitive `login_id`. Store the original login IDs, both Canvas user IDs, and the Aula Virtual full name.
5. Display matched students, missing AWS students, duplicate logins, and unavailable login IDs.
6. Confirm before saving locally; allow registering another AWS course against the same Aula Virtual course.

Names are displayed for verification but never used for automatic matching. Ambiguous identities remain unresolved and cannot receive grades. AWS students outside the authoritative roster are ignored.

Repeated registration refreshes existing relationships without creating duplicates. No command enrolls students or changes either roster.

### `homework`

1. Select the registered Aula Virtual course.
2. List its gradeable assignments, including quizzes and other gradebook activities.
3. Select the destination, then a registered AWS course and one source activity.
4. Show both maximum scores and confirm the conversion scale. Prefill Canvas values; permit explicit positive numeric overrides stored with the mapping.
5. Ask for the two late percentages: up to 14 days inclusive, and more than 14 days.
6. Show the Aula Virtual **Available until** cutoff. If absent, require a local cutoff without changing Canvas.
7. Preview and confirm the mapping before saving.

Each destination has one active mapping to one AWS activity. Selecting an existing mapping offers review and editing.

Use Canvas assignment IDs for gradebook operations, including quiz-backed assignments. Resolve applicable student and section availability overrides; show each student's effective cutoff in the grading preview. Canvas exposes these dates as `lock_at`. ([Assignments API](https://developerdocs.instructure.com/services/canvas/resources/assignments))

### `grade`

1. Select a saved homework mapping.
2. Refresh both rosters, assignment metadata, AWS attempts, and existing Aula Virtual grades.
3. Build a proposal for every authoritative student.
4. Display names, emails, selected attempt, source score/time, cutoff, current destination grade, scaled score, deduction, proposed grade, comment, and action or skip reason.
5. Ask individually before including each replacement of a different existing grade.
6. Show the resulting batch and require a final confirmation, defaulting to **No**.
7. Apply only approved rows and report successes, skips, failures, and unresolved outcomes.

Support `grade --dry-run` for preview only. Invalid selections are reprompted; cancellation, EOF, or interruption before confirmation causes no remote writes. CLI text is English; student comments are Spanish.

## Grading, persistence, and recovery

**Calculation**

```text
scaled = AWS attempt score / configured AWS maximum × configured AV maximum
final  = scaled × (1 − applicable penalty percentage / 100)
```

- Select the highest graded AWS attempt **before penalties**, paired with its own submission timestamp. Break equal-score ties using the earliest submission.
- At or before the cutoff: no penalty.
- After the cutoff through exactly 14 elapsed days: first percentage.
- Beyond 14 elapsed days: second percentage. The tiers are alternatives, not cumulative.
- Use decimal arithmetic; round once at the end to two decimals using half-up rounding. Bound results between zero and the configured destination maximum.
- Compare timestamps as instants; display them in the configured timezone. Never substitute a grading timestamp for a submission timestamp.
- Skip students absent from AWS, unresolved matches, pending grading, excused work, and submissions with insufficient attempt/timestamp information.
- For registered students with genuinely unsubmitted work, propose zero only after their cutoff.

Fetch submission history and quiz attempt data where applicable. If an AWS lab exposes only a grade without reliable attempt timing, flag it for review and skip it. Canvas distinguishes individual quiz attempt scores from the retained gradebook score. ([Submissions API](https://developerdocs.instructure.com/services/canvas/resources/submissions), [Quiz Submissions API](https://developerdocs.instructure.com/services/canvas/resources/quiz_submissions))

**Comments and writes**

Every applied late deduction includes a Spanish comment explaining the source score, conversion, cutoff, submission time, percentage deducted, and final grade.

Send each student's grade and required comment together. Set that submission's `late_policy_status` to `none` to prevent an additional Canvas automatic deduction; disclose this status change in the preview. Preserve the course's grade-posting policy. ([Submission updates](https://developerdocs.instructure.com/services/canvas/resources/submissions))

**Local history and recovery**

- Persist course relationships, authoritative students, AWS memberships/matches, versioned homework settings, grading runs, and per-student proposals/results.
- Record the approved proposal before sending requests, then save each response and returned comment ID.
- Recheck relevant remote values before writing; changed rows require a fresh preview and approval.
- Skip already-applied identical changes. Reconcile timeouts against the remote grade and a stable comment reference before retrying; never blindly resend comments.
- Use sequential writes, bounded retries for safe requests and explicit throttling responses, and stop on authentication failures.
- Preserve partial progress. Rerunning `grade` reconciles unfinished rows and requires confirmation for remaining changes.

## Verification and acceptance

Replace the starter's failing placeholder test with focused tests covering:

- Email normalization, duplicate identities, multiple AWS courses, roster refreshes, and missing students.
- Highest-attempt selection with matching timestamps and equal-score ties.
- Scaling, rounding, zero scores, percentage boundaries, and exactly 14 days versus one instant beyond.
- Missing cutoffs, local cutoffs, availability overrides, pending grading, and zeros before/after closure.
- Individual overwrite approvals, declined batch confirmation, cancellation, and dry-run producing no remote writes.
- Pagination, permission errors, throttling, partial failures, timeout reconciliation, and duplicate-comment prevention.
- SQLite migrations, repeat registration, mapping edits, and history surviving restart.

Use mocked Canvas responses and temporary SQLite databases for automated tests. Document token setup, all three workflows, recovery behavior, and a read-only check against the real instances to verify login visibility and AWS lab timestamps before the first live transfer.
