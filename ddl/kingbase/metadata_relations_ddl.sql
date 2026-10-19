CREATE TABLE {{metadata_parent}} (id INTEGER PRIMARY KEY);
CREATE TABLE {{metadata_child}} (id INTEGER PRIMARY KEY, parent_id INTEGER, FOREIGN KEY (parent_id) REFERENCES {{metadata_parent}} (id));
CREATE INDEX {{local:metadata_child_index}} ON {{metadata_child}} (parent_id);
CREATE VIEW {{metadata_view}} AS SELECT id FROM {{metadata_parent}};
