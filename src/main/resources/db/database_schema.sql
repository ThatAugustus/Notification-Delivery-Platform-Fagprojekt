
-- Table for storing tenants information. basically to let the system know which tenant is sending the request.
-- Instead of using normal increment id we are using uuid to make it more secure and less predictable. 
CREATE TABLE tenants (
    id UUID primary key DEFAULT gen_random_uuid(), -- generate random uuid for each tenant if not provided
    name VARCHAR(255) not null, -- the name of the tenant
    created_at TIMESTAMP not null with time zone DEFAULT now(), -- timestamp for when the tenant was created
    updated_at TIMESTAMP not null with time zone DEFAULT now() -- timestamp for when the tenant was last updated
);




-- table for api keys, each tenant can have multiple api keys, and each api key can be used to authenticate requests from that tenant.
-- So basically we use api_keys to verify that the request is coming from a valid tenant. so its like a badge that the tenant can use to access the system.
CREATE TABLE api_keys (
    id UUID primary key DEFAULT gen_random_uuid(), -- generate random uuid for each api key if not provided
    tenant_id UUID not null references tenants(id) on delete cascade, -- foreign key to the tenants table. cascade delete means that if a tenant is deleted, all their api keys will also be deleted.
    key_hash VARCHAR(255) not null, -- the hashed value of the api key. we will store the hash of the api key for security reasons, so even if someone gains access to the database they won't be able to see the actual api keys.
    -- so what will happen is that when a tenatn signs up, we will generate a random api key for them, hash it and store the hash in the database. so we will need to make sure that our backend has a function to generate random api keys and hash them before storing in the database.
    prefix VARCHAR(10) not null, -- the prefix of the api key. we will use the prefix to quickly identify which tenant the api key belongs to when a request comes in. think of credit card. they show only the last 4 digits of the card number and the rest is hidden. so we will do something similar with the api keys, we will show only the prefix and hide the rest of the key for security reasons.
    name VARCHAR(255) not null, -- the name of the api key. this is for the tenant to easily identify their api keys. for example, they can name it "production key" or "development key" etc.
    active BOOLEAN not null DEFAULT true, -- whether the api key is active or not. we can use this field to revoke an api key without deleting it from the database. so if a tenant wants to revoke an api key, we can simply set this field to false and the api key will no longer be valid for authentication.
    created_at TIMESTAMP not null with time zone DEFAULT now(), -- timestamp for when the api key was created
    revoked_at TIMESTAMP with time zone -- timestamp for when the api key was last revoked
);


-- table for notifications, each tenant can have multiple notifications, and each notification belongs to one tenant. so we will use the tenant_id as a foreign key to link the notifications to the tenants. So each request that comes will be a row in the notification table
create table notifications (
    id UUID primary key DEFAULT gen_random_uuid(), -- generate random uuid for each notification if not provided
    tenant_id UUID not null references tenants(id) on delete cascade, -- foreign key to the tenants table. cascade delete means that if a tenant is deleted, all their notifications will also be deleted.
    idempotency_key VARCHAR(255), -- the idempotency key for the notification. this is used to ensure that if a request is sent multiple times with the same idempotency key, only one notification will be created. so we will need to make sure that our backend has a function to generate unique idempotency keys for each request and check for duplicates before creating a new notification.
    chanel VARCHAR(50) not null, -- the channel through which the notification was sent. for example, "email", "sms", "push" etc.
    recipient VARCHAR(255) not null, -- the recipient of the notification. for example, if the channel is email, then this field will store the email address of the recipient. if the channel is sms, then this field will store the phone number of the recipient. etc.
    subject VARCHAR(255), -- emails needs a subject, but other channels might not need it. so we will make it nullable.
    content TEXT not null, -- the content of the notification. this will store the actual message that we want to send to the recipient.
    status VARCHAR(50) not null Default 'ACCEPTED', -- the status of the notification.
     -- Retry tracking
    retry_count INT NOT NULL DEFAULT 0,-- How many times we've retried so far
    max_retries INT NOT NULL DEFAULT 5,-- Give up after this many retries
    next_retry_at TIMESTAMP,-- When to retry next (NULL = not scheduled)
    created_at TIMESTAMP not null with time zone DEFAULT now(), -- timestamp for when the notification was created
    updated_at TIMESTAMP not null with time zone DEFAULT now() -- timestamp for when the notification was last updated

);

-- this is kind of a history table for the delivery attempts of each notification. each time we try to deliver a notification, we will create a new row in this table to track the attempt. so we can use this table to keep track of how many times we have tried to deliver a notification, and what was the result of each attempt (success or failure).
CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    attempt_number INT NOT NULL,
    status VARCHAR(20)  NOT NULL,-- SUCCESS or FAILED
    error_message TEXT,-- NULL on success, error details on failure
    duration_ms BIGINT, -- How long this attempt took in milliseconds
    attempted_at TIMESTAMP NOT NULL DEFAULT now()
);


-- this table is for solving the silent loss problem. instead of insert into notification and then directuly try to publish to rabbitmq, we will first insert into this table and then have a separate process that will read from this table and try to publish to rabbitmq. so if the process crashes after inserting into this table but before publishing to rabbitmq, we won't lose the notification because it is safely stored in this table. and the separate process will keep trying to publish to rabbitmq until it succeeds, so we won't lose any notifications even if there are temporary issues with rabbitmq.
create table outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    payload TEXT NOT NULL, -- the payload that we want to publish to rabbitmq.
    published BOOLEAN NOT NULL DEFAULT false, -- whether the event has been published to rabbitmq or not. we will set this to true once we have successfully published the event to rabbitmq, so that the separate process will know that it can safely delete this event from the table.
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
