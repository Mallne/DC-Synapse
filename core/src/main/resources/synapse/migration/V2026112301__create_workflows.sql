CREATE TABLE synapse_workflows (
    id VARCHAR(36) PRIMARY KEY,
    scope VARCHAR(255),
    document JSONB NOT NULL,
    created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX synapse_workflows_scope_idx ON synapse_workflows (scope);
