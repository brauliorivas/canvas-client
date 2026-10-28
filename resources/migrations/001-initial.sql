CREATE TABLE registrations (
  id INTEGER PRIMARY KEY,
  av_url TEXT NOT NULL,
  aws_url TEXT NOT NULL,
  av_course_id INTEGER NOT NULL,
  aws_course_id INTEGER NOT NULL,
  data TEXT NOT NULL,
  UNIQUE(av_url, aws_url, av_course_id, aws_course_id)
);
--;;
CREATE TABLE students (
  av_url TEXT NOT NULL,
  course_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  data TEXT NOT NULL,
  PRIMARY KEY(av_url, course_id, user_id)
);
--;;
CREATE TABLE memberships (
  registration_id INTEGER NOT NULL REFERENCES registrations(id),
  user_id INTEGER NOT NULL,
  data TEXT NOT NULL,
  PRIMARY KEY(registration_id, user_id)
);
--;;
CREATE TABLE matches (
  registration_id INTEGER NOT NULL REFERENCES registrations(id),
  av_user_id INTEGER NOT NULL,
  status TEXT NOT NULL,
  data TEXT NOT NULL,
  PRIMARY KEY(registration_id, av_user_id)
);
--;;
CREATE TABLE mappings (
  id INTEGER PRIMARY KEY,
  registration_id INTEGER NOT NULL REFERENCES registrations(id),
  av_url TEXT NOT NULL,
  aws_url TEXT NOT NULL,
  av_course_id INTEGER NOT NULL,
  destination_id INTEGER NOT NULL,
  version INTEGER NOT NULL,
  data TEXT NOT NULL,
  UNIQUE(av_url, aws_url, av_course_id, destination_id)
);
--;;
CREATE TABLE mapping_versions (
  mapping_id INTEGER NOT NULL REFERENCES mappings(id),
  version INTEGER NOT NULL,
  data TEXT NOT NULL,
  PRIMARY KEY(mapping_id, version)
);
--;;
CREATE TABLE runs (
  id TEXT PRIMARY KEY,
  mapping_id INTEGER NOT NULL REFERENCES mappings(id),
  status TEXT NOT NULL,
  created_at TEXT NOT NULL,
  data TEXT NOT NULL
);
--;;
CREATE TABLE run_rows (
  run_id TEXT NOT NULL REFERENCES runs(id),
  user_id INTEGER NOT NULL,
  operation_id TEXT NOT NULL,
  status TEXT NOT NULL,
  proposal TEXT NOT NULL,
  result TEXT,
  PRIMARY KEY(run_id, user_id)
);
--;;
CREATE INDEX run_rows_operation ON run_rows(operation_id);
