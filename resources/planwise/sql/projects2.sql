-- :name db-create-project! :<! :1
INSERT INTO projects2
  ("owner-id", name, config, "dataset-id")
  VALUES (:owner-id, :name, NULL, NULL)
  RETURNING id;

-- :name db-update-project :!
UPDATE projects2
  SET name = :name, config = :config, "dataset-id" = :dataset-id
  WHERE id = :id;

-- :name db-get-project :?
SELECT * FROM projects2
    WHERE id = :id;

-- :name db-list-projects :?
SELECT id, name FROM projects2
    WHERE "owner-id" = :owner-id;