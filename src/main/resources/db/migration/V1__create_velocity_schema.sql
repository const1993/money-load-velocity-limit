CREATE TABLE load_attempt (
    customer_id VARCHAR(255) NOT NULL,
    load_id VARCHAR(255) NOT NULL,
    amount_cents BIGINT NOT NULL,
    event_time TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    accepted BOOLEAN NOT NULL,
    decision_reason VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_load_attempt PRIMARY KEY (customer_id, load_id),
    CONSTRAINT ck_load_attempt_amount CHECK (amount_cents >= 0),
    -- CASE avoids H2 2.4.240 retaining a closed DDL session in an IN check.
    CONSTRAINT ck_load_attempt_reason CHECK (
        CASE decision_reason
            WHEN 'ACCEPTED' THEN TRUE
            WHEN 'DAILY_AMOUNT_LIMIT_EXCEEDED' THEN TRUE
            WHEN 'DAILY_COUNT_LIMIT_EXCEEDED' THEN TRUE
            WHEN 'WEEKLY_AMOUNT_LIMIT_EXCEEDED' THEN TRUE
            ELSE FALSE
        END
    ),
    CONSTRAINT ck_load_attempt_decision CHECK (
        (accepted = TRUE AND decision_reason = 'ACCEPTED') OR
        (accepted = FALSE AND decision_reason <> 'ACCEPTED')
    )
);

CREATE TABLE daily_velocity (
    customer_id VARCHAR(255) NOT NULL,
    date_utc DATE NOT NULL,
    accepted_amount_cents BIGINT NOT NULL,
    accepted_count INTEGER NOT NULL,
    updated_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_daily_velocity PRIMARY KEY (customer_id, date_utc),
    CONSTRAINT ck_daily_velocity_amount CHECK (accepted_amount_cents >= 0),
    CONSTRAINT ck_daily_velocity_count CHECK (accepted_count >= 0)
);

CREATE TABLE weekly_velocity (
    customer_id VARCHAR(255) NOT NULL,
    week_start_utc DATE NOT NULL,
    accepted_amount_cents BIGINT NOT NULL,
    updated_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_weekly_velocity PRIMARY KEY (customer_id, week_start_utc),
    CONSTRAINT ck_weekly_velocity_amount CHECK (accepted_amount_cents >= 0)
);
