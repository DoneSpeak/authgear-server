--
-- PostgreSQL database dump
--

\restrict Kk8JFQ5F0vcDzklBgaPeDz6PZmBXVAGavdHnJpIMASbDbqqhCTWrWXxTG6ziQYt

-- Dumped from database version 16.11
-- Dumped by pg_dump version 16.11

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pg_partman; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_partman WITH SCHEMA public;


--
-- Name: EXTENSION pg_partman; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_partman IS 'Extension to manage partitioned tables by time or ID';


--
-- Name: notify_config_source_change(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.notify_config_source_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  record RECORD;
BEGIN
  IF (TG_OP = 'DELETE') THEN
    record := OLD;
  ELSE
    record := NEW;
  END IF;
  PERFORM pg_notify('config_source_change', record.app_id);
  RETURN NULL;
END;
$$;


--
-- Name: notify_domain_change(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.notify_domain_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
  DECLARE
    record RECORD;
  BEGIN
    IF (TG_OP = 'DELETE') THEN
      record := OLD;
    ELSE
      record := NEW;
    END IF;
    PERFORM pg_notify('domain_change', record.domain);
    RETURN NULL;
  END;
$$;


--
-- Name: notify_plan_change(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.notify_plan_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  record RECORD;
BEGIN
  IF (TG_OP = 'DELETE') THEN
    record := OLD;
  ELSE
    record := NEW;
  END IF;
  PERFORM pg_notify('plan_change', record.name);
  RETURN NULL;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: _audit_analytic_count; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_analytic_count (
    id text NOT NULL,
    app_id text NOT NULL,
    type text NOT NULL,
    count integer NOT NULL,
    date date NOT NULL
);


--
-- Name: _audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_log (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    activity_type text NOT NULL,
    ip_address inet,
    user_agent text,
    client_id text,
    data jsonb NOT NULL
)
PARTITION BY RANGE (created_at);


--
-- Name: _audit_log_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_log_default (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    activity_type text NOT NULL,
    ip_address inet,
    user_agent text,
    client_id text,
    data jsonb NOT NULL
);


--
-- Name: _audit_log_p20250901; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_log_p20250901 (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    activity_type text NOT NULL,
    ip_address inet,
    user_agent text,
    client_id text,
    data jsonb NOT NULL
);


--
-- Name: _audit_log_p20251001; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_log_p20251001 (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    activity_type text NOT NULL,
    ip_address inet,
    user_agent text,
    client_id text,
    data jsonb NOT NULL
);


--
-- Name: _audit_log_p20251101; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_log_p20251101 (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    activity_type text NOT NULL,
    ip_address inet,
    user_agent text,
    client_id text,
    data jsonb NOT NULL
);


--
-- Name: _audit_log_p20251201; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_log_p20251201 (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    activity_type text NOT NULL,
    ip_address inet,
    user_agent text,
    client_id text,
    data jsonb NOT NULL
);


--
-- Name: _audit_log_p20260101; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_log_p20260101 (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    activity_type text NOT NULL,
    ip_address inet,
    user_agent text,
    client_id text,
    data jsonb NOT NULL
);


--
-- Name: _audit_log_p20260201; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_log_p20260201 (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    activity_type text NOT NULL,
    ip_address inet,
    user_agent text,
    client_id text,
    data jsonb NOT NULL
);


--
-- Name: _audit_log_p20260301; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_log_p20260301 (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    activity_type text NOT NULL,
    ip_address inet,
    user_agent text,
    client_id text,
    data jsonb NOT NULL
);


--
-- Name: _audit_log_p20260401; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_log_p20260401 (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    activity_type text NOT NULL,
    ip_address inet,
    user_agent text,
    client_id text,
    data jsonb NOT NULL
);


--
-- Name: _audit_log_p20260501; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_log_p20260501 (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    activity_type text NOT NULL,
    ip_address inet,
    user_agent text,
    client_id text,
    data jsonb NOT NULL
);


--
-- Name: _audit_log_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_log_template (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    activity_type text NOT NULL,
    ip_address inet,
    user_agent text,
    client_id text,
    data jsonb NOT NULL
);


--
-- Name: _audit_migration; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._audit_migration (
    id text NOT NULL,
    applied_at timestamp with time zone
);


--
-- Name: _auth_authenticator; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_authenticator (
    id text NOT NULL,
    app_id text NOT NULL,
    type text NOT NULL,
    user_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    is_default boolean NOT NULL,
    kind text NOT NULL
);


--
-- Name: _auth_authenticator_oob; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_authenticator_oob (
    id text NOT NULL,
    app_id text NOT NULL,
    phone text NOT NULL,
    email text NOT NULL,
    metadata jsonb
);


--
-- Name: _auth_authenticator_passkey; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_authenticator_passkey (
    id text NOT NULL,
    app_id text NOT NULL,
    credential_id text NOT NULL,
    creation_options jsonb NOT NULL,
    attestation_response jsonb NOT NULL,
    sign_count bigint NOT NULL
);


--
-- Name: _auth_authenticator_password; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_authenticator_password (
    id text NOT NULL,
    app_id text NOT NULL,
    password_hash text NOT NULL,
    expire_after timestamp without time zone
);


--
-- Name: _auth_authenticator_totp; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_authenticator_totp (
    id text NOT NULL,
    app_id text NOT NULL,
    secret text NOT NULL,
    display_name text NOT NULL
);


--
-- Name: _auth_client_resource; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_client_resource (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    client_id text NOT NULL,
    resource_id text NOT NULL
);


--
-- Name: _auth_client_resource_scope; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_client_resource_scope (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    client_id text NOT NULL,
    resource_id text NOT NULL,
    scope_id text NOT NULL
);


--
-- Name: _auth_event_sequence; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public._auth_event_sequence
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: _auth_group; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_group (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    key text NOT NULL,
    name text,
    description text
);


--
-- Name: _auth_group_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_group_role (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    group_id text NOT NULL,
    role_id text NOT NULL
);


--
-- Name: _auth_identity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_identity (
    id text NOT NULL,
    app_id text NOT NULL,
    type text NOT NULL,
    user_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL
);


--
-- Name: _auth_identity_anonymous; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_identity_anonymous (
    id text NOT NULL,
    app_id text NOT NULL,
    key_id text,
    key jsonb
);


--
-- Name: _auth_identity_biometric; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_identity_biometric (
    id text NOT NULL,
    app_id text NOT NULL,
    key_id text NOT NULL,
    key jsonb NOT NULL,
    device_info jsonb NOT NULL
);


--
-- Name: _auth_identity_ldap; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_identity_ldap (
    id text NOT NULL,
    app_id text NOT NULL,
    server_name text NOT NULL,
    user_id_attribute_name text NOT NULL,
    user_id_attribute_value bytea NOT NULL,
    claims jsonb NOT NULL,
    raw_entry_json jsonb NOT NULL,
    last_login_username text
);


--
-- Name: _auth_identity_login_id; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_identity_login_id (
    id text NOT NULL,
    app_id text NOT NULL,
    login_id_key text NOT NULL,
    login_id text NOT NULL,
    claims jsonb NOT NULL,
    original_login_id text NOT NULL,
    unique_key text NOT NULL,
    login_id_type text NOT NULL
);


--
-- Name: _auth_identity_oauth; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_identity_oauth (
    id text NOT NULL,
    app_id text NOT NULL,
    provider_type text NOT NULL,
    provider_keys jsonb DEFAULT '{}'::jsonb NOT NULL,
    provider_user_id text NOT NULL,
    claims jsonb NOT NULL,
    profile jsonb
);


--
-- Name: _auth_identity_passkey; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_identity_passkey (
    id text NOT NULL,
    app_id text NOT NULL,
    credential_id text NOT NULL,
    creation_options jsonb NOT NULL,
    attestation_response jsonb NOT NULL
);


--
-- Name: _auth_identity_siwe; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_identity_siwe (
    id text NOT NULL,
    app_id text NOT NULL,
    chain_id integer NOT NULL,
    address text NOT NULL,
    data jsonb NOT NULL
);


--
-- Name: _auth_migration; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_migration (
    id text NOT NULL,
    applied_at timestamp with time zone
);


--
-- Name: _auth_oauth_authorization; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_oauth_authorization (
    id text NOT NULL,
    app_id text NOT NULL,
    client_id text NOT NULL,
    user_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    scopes jsonb NOT NULL
);


--
-- Name: _auth_password_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_password_history (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    password text NOT NULL
);


--
-- Name: _auth_recovery_code; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_recovery_code (
    id text NOT NULL,
    app_id text NOT NULL,
    user_id text NOT NULL,
    code text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    consumed boolean NOT NULL,
    updated_at timestamp without time zone NOT NULL
);


--
-- Name: _auth_resource; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_resource (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    uri text NOT NULL,
    name text,
    metadata jsonb
);


--
-- Name: _auth_resource_scope; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_resource_scope (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    resource_id text NOT NULL,
    scope text NOT NULL,
    description text,
    metadata jsonb
);


--
-- Name: _auth_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_role (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    key text NOT NULL,
    name text,
    description text
);


--
-- Name: _auth_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_user (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    last_login_at timestamp without time zone,
    login_at timestamp without time zone,
    is_disabled boolean NOT NULL,
    disable_reason text,
    standard_attributes jsonb,
    custom_attributes jsonb,
    is_deactivated boolean,
    delete_at timestamp without time zone,
    is_anonymized boolean DEFAULT false NOT NULL,
    anonymize_at timestamp without time zone,
    anonymized_at timestamp without time zone,
    last_indexed_at timestamp without time zone,
    require_reindex_after timestamp without time zone,
    mfa_grace_period_end_at timestamp without time zone,
    metadata jsonb,
    is_indefinitely_disabled boolean,
    account_valid_from timestamp without time zone,
    account_valid_until timestamp without time zone,
    temporarily_disabled_from timestamp without time zone,
    temporarily_disabled_until timestamp without time zone,
    account_status_stale_from timestamp without time zone
);


--
-- Name: _auth_user_group; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_user_group (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    group_id text NOT NULL
);


--
-- Name: _auth_user_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_user_role (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    user_id text NOT NULL,
    role_id text NOT NULL
);


--
-- Name: _auth_verified_claim; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._auth_verified_claim (
    id text NOT NULL,
    app_id text NOT NULL,
    user_id text NOT NULL,
    name text NOT NULL,
    value text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    metadata jsonb
);


--
-- Name: _images_file; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._images_file (
    id text NOT NULL,
    app_id text NOT NULL,
    size integer NOT NULL,
    metadata jsonb NOT NULL,
    created_at timestamp without time zone NOT NULL
);


--
-- Name: _images_migrations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._images_migrations (
    id text NOT NULL,
    applied_at timestamp with time zone
);


--
-- Name: _portal_app_collaborator; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._portal_app_collaborator (
    id text NOT NULL,
    app_id text NOT NULL,
    user_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    role text NOT NULL
);


--
-- Name: _portal_app_collaborator_invitation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._portal_app_collaborator_invitation (
    id text NOT NULL,
    app_id text NOT NULL,
    invited_by text NOT NULL,
    invitee_email text NOT NULL,
    code text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    expire_at timestamp without time zone NOT NULL
);


--
-- Name: _portal_config_source; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._portal_config_source (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    data jsonb NOT NULL,
    plan_name text NOT NULL
);


--
-- Name: _portal_domain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._portal_domain (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    domain text NOT NULL,
    apex_domain text NOT NULL,
    verification_nonce text NOT NULL,
    is_custom boolean NOT NULL
);


--
-- Name: _portal_historical_subscription; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._portal_historical_subscription (
    id text NOT NULL,
    app_id text NOT NULL,
    stripe_customer_id text NOT NULL,
    stripe_subscription_id text NOT NULL,
    subscription_created_at timestamp without time zone NOT NULL,
    subscription_updated_at timestamp without time zone NOT NULL,
    subscription_cancelled_at timestamp without time zone,
    subscription_ended_at timestamp without time zone,
    created_at timestamp without time zone NOT NULL
);


--
-- Name: _portal_migration; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._portal_migration (
    id text NOT NULL,
    applied_at timestamp with time zone
);


--
-- Name: _portal_pending_domain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._portal_pending_domain (
    id text NOT NULL,
    app_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    domain text NOT NULL,
    apex_domain text NOT NULL,
    verification_nonce text NOT NULL,
    is_custom boolean NOT NULL
);


--
-- Name: _portal_plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._portal_plan (
    id text NOT NULL,
    name text NOT NULL,
    feature_config jsonb NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL
);


--
-- Name: _portal_subscription; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._portal_subscription (
    id text NOT NULL,
    app_id text NOT NULL,
    stripe_customer_id text NOT NULL,
    stripe_subscription_id text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    cancelled_at timestamp without time zone,
    ended_at timestamp without time zone
);


--
-- Name: _portal_subscription_checkout; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._portal_subscription_checkout (
    id text NOT NULL,
    app_id text NOT NULL,
    stripe_checkout_session_id text NOT NULL,
    stripe_customer_id text,
    status text NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    expire_at timestamp without time zone NOT NULL
);


--
-- Name: _portal_tutorial_progress; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._portal_tutorial_progress (
    app_id text NOT NULL,
    data jsonb NOT NULL
);


--
-- Name: _portal_usage_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._portal_usage_record (
    id text NOT NULL,
    app_id text NOT NULL,
    name text NOT NULL,
    period text NOT NULL,
    start_time timestamp without time zone NOT NULL,
    end_time timestamp without time zone NOT NULL,
    count integer NOT NULL,
    alert_data jsonb,
    stripe_timestamp timestamp without time zone
);


--
-- Name: _portal_user_app_quota; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._portal_user_app_quota (
    user_id text NOT NULL,
    max_own_apps integer NOT NULL
);


--
-- Name: _audit_log_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log ATTACH PARTITION public._audit_log_default DEFAULT;


--
-- Name: _audit_log_p20250901; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log ATTACH PARTITION public._audit_log_p20250901 FOR VALUES FROM ('2025-09-01 00:00:00') TO ('2025-10-01 00:00:00');


--
-- Name: _audit_log_p20251001; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log ATTACH PARTITION public._audit_log_p20251001 FOR VALUES FROM ('2025-10-01 00:00:00') TO ('2025-11-01 00:00:00');


--
-- Name: _audit_log_p20251101; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log ATTACH PARTITION public._audit_log_p20251101 FOR VALUES FROM ('2025-11-01 00:00:00') TO ('2025-12-01 00:00:00');


--
-- Name: _audit_log_p20251201; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log ATTACH PARTITION public._audit_log_p20251201 FOR VALUES FROM ('2025-12-01 00:00:00') TO ('2026-01-01 00:00:00');


--
-- Name: _audit_log_p20260101; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log ATTACH PARTITION public._audit_log_p20260101 FOR VALUES FROM ('2026-01-01 00:00:00') TO ('2026-02-01 00:00:00');


--
-- Name: _audit_log_p20260201; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log ATTACH PARTITION public._audit_log_p20260201 FOR VALUES FROM ('2026-02-01 00:00:00') TO ('2026-03-01 00:00:00');


--
-- Name: _audit_log_p20260301; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log ATTACH PARTITION public._audit_log_p20260301 FOR VALUES FROM ('2026-03-01 00:00:00') TO ('2026-04-01 00:00:00');


--
-- Name: _audit_log_p20260401; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log ATTACH PARTITION public._audit_log_p20260401 FOR VALUES FROM ('2026-04-01 00:00:00') TO ('2026-05-01 00:00:00');


--
-- Name: _audit_log_p20260501; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log ATTACH PARTITION public._audit_log_p20260501 FOR VALUES FROM ('2026-05-01 00:00:00') TO ('2026-06-01 00:00:00');


--
-- Name: _audit_analytic_count _audit_analytic_count_app_id_type_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_analytic_count
    ADD CONSTRAINT _audit_analytic_count_app_id_type_date_key UNIQUE (app_id, type, date);


--
-- Name: _audit_analytic_count _audit_analytic_count_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_analytic_count
    ADD CONSTRAINT _audit_analytic_count_pkey PRIMARY KEY (id);


--
-- Name: _audit_log_default _audit_log_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log_default
    ADD CONSTRAINT _audit_log_default_pkey PRIMARY KEY (id);


--
-- Name: _audit_log_p20250901 _audit_log_p20250901_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log_p20250901
    ADD CONSTRAINT _audit_log_p20250901_pkey PRIMARY KEY (id);


--
-- Name: _audit_log_p20251001 _audit_log_p20251001_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log_p20251001
    ADD CONSTRAINT _audit_log_p20251001_pkey PRIMARY KEY (id);


--
-- Name: _audit_log_p20251101 _audit_log_p20251101_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log_p20251101
    ADD CONSTRAINT _audit_log_p20251101_pkey PRIMARY KEY (id);


--
-- Name: _audit_log_p20251201 _audit_log_p20251201_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log_p20251201
    ADD CONSTRAINT _audit_log_p20251201_pkey PRIMARY KEY (id);


--
-- Name: _audit_log_p20260101 _audit_log_p20260101_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log_p20260101
    ADD CONSTRAINT _audit_log_p20260101_pkey PRIMARY KEY (id);


--
-- Name: _audit_log_p20260201 _audit_log_p20260201_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log_p20260201
    ADD CONSTRAINT _audit_log_p20260201_pkey PRIMARY KEY (id);


--
-- Name: _audit_log_p20260301 _audit_log_p20260301_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log_p20260301
    ADD CONSTRAINT _audit_log_p20260301_pkey PRIMARY KEY (id);


--
-- Name: _audit_log_p20260401 _audit_log_p20260401_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log_p20260401
    ADD CONSTRAINT _audit_log_p20260401_pkey PRIMARY KEY (id);


--
-- Name: _audit_log_p20260501 _audit_log_p20260501_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log_p20260501
    ADD CONSTRAINT _audit_log_p20260501_pkey PRIMARY KEY (id);


--
-- Name: _audit_log_template _audit_log_template_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_log_template
    ADD CONSTRAINT _audit_log_template_pkey PRIMARY KEY (id);


--
-- Name: _audit_migration _audit_migration_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._audit_migration
    ADD CONSTRAINT _audit_migration_pkey PRIMARY KEY (id);


--
-- Name: _auth_authenticator_oob _auth_authenticator_oob_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_authenticator_oob
    ADD CONSTRAINT _auth_authenticator_oob_pkey PRIMARY KEY (id);


--
-- Name: _auth_authenticator_passkey _auth_authenticator_passkey_credential_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_authenticator_passkey
    ADD CONSTRAINT _auth_authenticator_passkey_credential_id UNIQUE (credential_id);


--
-- Name: _auth_authenticator_passkey _auth_authenticator_passkey_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_authenticator_passkey
    ADD CONSTRAINT _auth_authenticator_passkey_pkey PRIMARY KEY (id);


--
-- Name: _auth_authenticator_password _auth_authenticator_password_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_authenticator_password
    ADD CONSTRAINT _auth_authenticator_password_pkey PRIMARY KEY (id);


--
-- Name: _auth_authenticator _auth_authenticator_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_authenticator
    ADD CONSTRAINT _auth_authenticator_pkey PRIMARY KEY (id);


--
-- Name: _auth_authenticator_totp _auth_authenticator_totp_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_authenticator_totp
    ADD CONSTRAINT _auth_authenticator_totp_pkey PRIMARY KEY (id);


--
-- Name: _auth_client_resource _auth_client_resource_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_client_resource
    ADD CONSTRAINT _auth_client_resource_pkey PRIMARY KEY (id);


--
-- Name: _auth_client_resource_scope _auth_client_resource_scope_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_client_resource_scope
    ADD CONSTRAINT _auth_client_resource_scope_pkey PRIMARY KEY (id);


--
-- Name: _auth_group _auth_group_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_group
    ADD CONSTRAINT _auth_group_pkey PRIMARY KEY (id);


--
-- Name: _auth_group_role _auth_group_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_group_role
    ADD CONSTRAINT _auth_group_role_pkey PRIMARY KEY (id);


--
-- Name: _auth_identity_siwe _auth_identity_address; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_siwe
    ADD CONSTRAINT _auth_identity_address UNIQUE (app_id, chain_id, address);


--
-- Name: _auth_identity_anonymous _auth_identity_anonymous_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_anonymous
    ADD CONSTRAINT _auth_identity_anonymous_key UNIQUE (app_id, key_id);


--
-- Name: _auth_identity_anonymous _auth_identity_anonymous_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_anonymous
    ADD CONSTRAINT _auth_identity_anonymous_pkey PRIMARY KEY (id);


--
-- Name: _auth_identity_biometric _auth_identity_biometric_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_biometric
    ADD CONSTRAINT _auth_identity_biometric_key UNIQUE (app_id, key_id);


--
-- Name: _auth_identity_biometric _auth_identity_biometric_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_biometric
    ADD CONSTRAINT _auth_identity_biometric_pkey PRIMARY KEY (id);


--
-- Name: _auth_identity_ldap _auth_identity_ldap_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_ldap
    ADD CONSTRAINT _auth_identity_ldap_pkey PRIMARY KEY (id);


--
-- Name: _auth_identity_ldap _auth_identity_ldap_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_ldap
    ADD CONSTRAINT _auth_identity_ldap_unique UNIQUE (app_id, server_name, user_id_attribute_name, user_id_attribute_value);


--
-- Name: _auth_identity_login_id _auth_identity_login_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_login_id
    ADD CONSTRAINT _auth_identity_login_id_key UNIQUE (app_id, unique_key);


--
-- Name: _auth_identity_login_id _auth_identity_login_id_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_login_id
    ADD CONSTRAINT _auth_identity_login_id_pkey PRIMARY KEY (id);


--
-- Name: _auth_identity_oauth _auth_identity_oauth_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_oauth
    ADD CONSTRAINT _auth_identity_oauth_key UNIQUE (app_id, provider_type, provider_keys, provider_user_id);


--
-- Name: _auth_identity_oauth _auth_identity_oauth_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_oauth
    ADD CONSTRAINT _auth_identity_oauth_pkey PRIMARY KEY (id);


--
-- Name: _auth_identity_passkey _auth_identity_passkey_credential_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_passkey
    ADD CONSTRAINT _auth_identity_passkey_credential_id UNIQUE (credential_id);


--
-- Name: _auth_identity_passkey _auth_identity_passkey_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_passkey
    ADD CONSTRAINT _auth_identity_passkey_pkey PRIMARY KEY (id);


--
-- Name: _auth_identity _auth_identity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity
    ADD CONSTRAINT _auth_identity_pkey PRIMARY KEY (id);


--
-- Name: _auth_identity_siwe _auth_identity_siwe_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_siwe
    ADD CONSTRAINT _auth_identity_siwe_pkey PRIMARY KEY (id);


--
-- Name: _auth_migration _auth_migration_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_migration
    ADD CONSTRAINT _auth_migration_pkey PRIMARY KEY (id);


--
-- Name: _auth_oauth_authorization _auth_oauth_authorization_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_oauth_authorization
    ADD CONSTRAINT _auth_oauth_authorization_key UNIQUE (app_id, user_id, client_id);


--
-- Name: _auth_oauth_authorization _auth_oauth_authorization_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_oauth_authorization
    ADD CONSTRAINT _auth_oauth_authorization_pkey PRIMARY KEY (id);


--
-- Name: _auth_password_history _auth_password_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_password_history
    ADD CONSTRAINT _auth_password_history_pkey PRIMARY KEY (id);


--
-- Name: _auth_recovery_code _auth_recovery_code_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_recovery_code
    ADD CONSTRAINT _auth_recovery_code_pkey PRIMARY KEY (id);


--
-- Name: _auth_resource _auth_resource_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_resource
    ADD CONSTRAINT _auth_resource_pkey PRIMARY KEY (id);


--
-- Name: _auth_resource_scope _auth_resource_scope_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_resource_scope
    ADD CONSTRAINT _auth_resource_scope_pkey PRIMARY KEY (id);


--
-- Name: _auth_role _auth_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_role
    ADD CONSTRAINT _auth_role_pkey PRIMARY KEY (id);


--
-- Name: _auth_user_group _auth_user_group_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_user_group
    ADD CONSTRAINT _auth_user_group_pkey PRIMARY KEY (id);


--
-- Name: _auth_user _auth_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_user
    ADD CONSTRAINT _auth_user_pkey PRIMARY KEY (id);


--
-- Name: _auth_user_role _auth_user_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_user_role
    ADD CONSTRAINT _auth_user_role_pkey PRIMARY KEY (id);


--
-- Name: _auth_verified_claim _auth_verified_claim_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_verified_claim
    ADD CONSTRAINT _auth_verified_claim_pkey PRIMARY KEY (id);


--
-- Name: _images_file _images_file_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._images_file
    ADD CONSTRAINT _images_file_pkey PRIMARY KEY (id);


--
-- Name: _images_migrations _images_migrations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._images_migrations
    ADD CONSTRAINT _images_migrations_pkey PRIMARY KEY (id);


--
-- Name: _portal_app_collaborator _portal_app_collaborator_app_id_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_app_collaborator
    ADD CONSTRAINT _portal_app_collaborator_app_id_user_id_key UNIQUE (app_id, user_id);


--
-- Name: _portal_app_collaborator_invitation _portal_app_collaborator_invitation_app_id_invitee_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_app_collaborator_invitation
    ADD CONSTRAINT _portal_app_collaborator_invitation_app_id_invitee_email_key UNIQUE (app_id, invitee_email);


--
-- Name: _portal_app_collaborator_invitation _portal_app_collaborator_invitation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_app_collaborator_invitation
    ADD CONSTRAINT _portal_app_collaborator_invitation_pkey PRIMARY KEY (id);


--
-- Name: _portal_app_collaborator _portal_app_collaborator_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_app_collaborator
    ADD CONSTRAINT _portal_app_collaborator_pkey PRIMARY KEY (id);


--
-- Name: _portal_config_source _portal_config_source_app_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_config_source
    ADD CONSTRAINT _portal_config_source_app_id_key UNIQUE (app_id);


--
-- Name: _portal_config_source _portal_config_source_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_config_source
    ADD CONSTRAINT _portal_config_source_pkey PRIMARY KEY (id);


--
-- Name: _portal_domain _portal_domain_apex_domain_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_domain
    ADD CONSTRAINT _portal_domain_apex_domain_key UNIQUE (apex_domain);


--
-- Name: _portal_domain _portal_domain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_domain
    ADD CONSTRAINT _portal_domain_pkey PRIMARY KEY (id);


--
-- Name: _portal_historical_subscription _portal_historical_subscription_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_historical_subscription
    ADD CONSTRAINT _portal_historical_subscription_pkey PRIMARY KEY (id);


--
-- Name: _portal_migration _portal_migration_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_migration
    ADD CONSTRAINT _portal_migration_pkey PRIMARY KEY (id);


--
-- Name: _portal_pending_domain _portal_pending_domain_app_id_apex_domain_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_pending_domain
    ADD CONSTRAINT _portal_pending_domain_app_id_apex_domain_key UNIQUE (app_id, apex_domain);


--
-- Name: _portal_pending_domain _portal_pending_domain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_pending_domain
    ADD CONSTRAINT _portal_pending_domain_pkey PRIMARY KEY (id);


--
-- Name: _portal_plan _portal_plan_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_plan
    ADD CONSTRAINT _portal_plan_name_key UNIQUE (name);


--
-- Name: _portal_plan _portal_plan_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_plan
    ADD CONSTRAINT _portal_plan_pkey PRIMARY KEY (id);


--
-- Name: _portal_subscription _portal_subscription_app_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_subscription
    ADD CONSTRAINT _portal_subscription_app_id_key UNIQUE (app_id);


--
-- Name: _portal_subscription_checkout _portal_subscription_checkout_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_subscription_checkout
    ADD CONSTRAINT _portal_subscription_checkout_pkey PRIMARY KEY (id);


--
-- Name: _portal_subscription_checkout _portal_subscription_checkout_stripe_checkout_session_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_subscription_checkout
    ADD CONSTRAINT _portal_subscription_checkout_stripe_checkout_session_id_key UNIQUE (stripe_checkout_session_id);


--
-- Name: _portal_subscription_checkout _portal_subscription_checkout_stripe_customer_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_subscription_checkout
    ADD CONSTRAINT _portal_subscription_checkout_stripe_customer_id_key UNIQUE (stripe_customer_id);


--
-- Name: _portal_subscription _portal_subscription_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_subscription
    ADD CONSTRAINT _portal_subscription_pkey PRIMARY KEY (id);


--
-- Name: _portal_tutorial_progress _portal_tutorial_progress_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_tutorial_progress
    ADD CONSTRAINT _portal_tutorial_progress_pkey PRIMARY KEY (app_id);


--
-- Name: _portal_usage_record _portal_usage_record_app_id_name_period_start_time_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_usage_record
    ADD CONSTRAINT _portal_usage_record_app_id_name_period_start_time_key UNIQUE (app_id, name, period, start_time);


--
-- Name: _portal_usage_record _portal_usage_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_usage_record
    ADD CONSTRAINT _portal_usage_record_pkey PRIMARY KEY (id);


--
-- Name: _portal_user_app_quota _portal_user_app_quota_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._portal_user_app_quota
    ADD CONSTRAINT _portal_user_app_quota_pkey PRIMARY KEY (user_id);


--
-- Name: _audit_log_idx_app_id_activity_type_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_idx_app_id_activity_type_created_at ON ONLY public._audit_log USING btree (app_id, activity_type, created_at DESC);


--
-- Name: _audit_log_default_app_id_activity_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_default_app_id_activity_type_created_at_idx ON public._audit_log_default USING btree (app_id, activity_type, created_at DESC);


--
-- Name: _audit_log_idx_app_id_activity_type_data_payload_recipient_crea; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_idx_app_id_activity_type_data_payload_recipient_crea ON ONLY public._audit_log USING btree (app_id, activity_type, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_default_app_id_activity_type_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_default_app_id_activity_type_expr_created_at_idx ON public._audit_log_default USING btree (app_id, activity_type, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_idx_app_id_data_payload_recipient_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_idx_app_id_data_payload_recipient_created_at ON ONLY public._audit_log USING btree (app_id, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_default_app_id_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_default_app_id_expr_created_at_idx ON public._audit_log_default USING btree (app_id, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_idx_app_id_user_id_activity_type_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_idx_app_id_user_id_activity_type_created_at ON ONLY public._audit_log USING btree (app_id, user_id, activity_type, created_at DESC);


--
-- Name: _audit_log_default_app_id_user_id_activity_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_default_app_id_user_id_activity_type_created_at_idx ON public._audit_log_default USING btree (app_id, user_id, activity_type, created_at DESC);


--
-- Name: _audit_log_idx_app_id_user_id_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_idx_app_id_user_id_created_at ON ONLY public._audit_log USING btree (app_id, user_id, created_at DESC);


--
-- Name: _audit_log_default_app_id_user_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_default_app_id_user_id_created_at_idx ON public._audit_log_default USING btree (app_id, user_id, created_at DESC);


--
-- Name: _audit_log_idx_created_at_brin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_idx_created_at_brin ON ONLY public._audit_log USING brin (created_at);


--
-- Name: _audit_log_default_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_default_created_at_idx ON public._audit_log_default USING brin (created_at);


--
-- Name: _audit_log_p20250901_app_id_activity_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20250901_app_id_activity_type_created_at_idx ON public._audit_log_p20250901 USING btree (app_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20250901_app_id_activity_type_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20250901_app_id_activity_type_expr_created_at_idx ON public._audit_log_p20250901 USING btree (app_id, activity_type, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20250901_app_id_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20250901_app_id_expr_created_at_idx ON public._audit_log_p20250901 USING btree (app_id, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20250901_app_id_user_id_activity_type_created_a_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20250901_app_id_user_id_activity_type_created_a_idx ON public._audit_log_p20250901 USING btree (app_id, user_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20250901_app_id_user_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20250901_app_id_user_id_created_at_idx ON public._audit_log_p20250901 USING btree (app_id, user_id, created_at DESC);


--
-- Name: _audit_log_p20250901_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20250901_created_at_idx ON public._audit_log_p20250901 USING brin (created_at);


--
-- Name: _audit_log_p20251001_app_id_activity_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251001_app_id_activity_type_created_at_idx ON public._audit_log_p20251001 USING btree (app_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20251001_app_id_activity_type_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251001_app_id_activity_type_expr_created_at_idx ON public._audit_log_p20251001 USING btree (app_id, activity_type, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20251001_app_id_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251001_app_id_expr_created_at_idx ON public._audit_log_p20251001 USING btree (app_id, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20251001_app_id_user_id_activity_type_created_a_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251001_app_id_user_id_activity_type_created_a_idx ON public._audit_log_p20251001 USING btree (app_id, user_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20251001_app_id_user_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251001_app_id_user_id_created_at_idx ON public._audit_log_p20251001 USING btree (app_id, user_id, created_at DESC);


--
-- Name: _audit_log_p20251001_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251001_created_at_idx ON public._audit_log_p20251001 USING brin (created_at);


--
-- Name: _audit_log_p20251101_app_id_activity_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251101_app_id_activity_type_created_at_idx ON public._audit_log_p20251101 USING btree (app_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20251101_app_id_activity_type_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251101_app_id_activity_type_expr_created_at_idx ON public._audit_log_p20251101 USING btree (app_id, activity_type, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20251101_app_id_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251101_app_id_expr_created_at_idx ON public._audit_log_p20251101 USING btree (app_id, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20251101_app_id_user_id_activity_type_created_a_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251101_app_id_user_id_activity_type_created_a_idx ON public._audit_log_p20251101 USING btree (app_id, user_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20251101_app_id_user_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251101_app_id_user_id_created_at_idx ON public._audit_log_p20251101 USING btree (app_id, user_id, created_at DESC);


--
-- Name: _audit_log_p20251101_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251101_created_at_idx ON public._audit_log_p20251101 USING brin (created_at);


--
-- Name: _audit_log_p20251201_app_id_activity_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251201_app_id_activity_type_created_at_idx ON public._audit_log_p20251201 USING btree (app_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20251201_app_id_activity_type_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251201_app_id_activity_type_expr_created_at_idx ON public._audit_log_p20251201 USING btree (app_id, activity_type, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20251201_app_id_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251201_app_id_expr_created_at_idx ON public._audit_log_p20251201 USING btree (app_id, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20251201_app_id_user_id_activity_type_created_a_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251201_app_id_user_id_activity_type_created_a_idx ON public._audit_log_p20251201 USING btree (app_id, user_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20251201_app_id_user_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251201_app_id_user_id_created_at_idx ON public._audit_log_p20251201 USING btree (app_id, user_id, created_at DESC);


--
-- Name: _audit_log_p20251201_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20251201_created_at_idx ON public._audit_log_p20251201 USING brin (created_at);


--
-- Name: _audit_log_p20260101_app_id_activity_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260101_app_id_activity_type_created_at_idx ON public._audit_log_p20260101 USING btree (app_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20260101_app_id_activity_type_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260101_app_id_activity_type_expr_created_at_idx ON public._audit_log_p20260101 USING btree (app_id, activity_type, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20260101_app_id_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260101_app_id_expr_created_at_idx ON public._audit_log_p20260101 USING btree (app_id, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20260101_app_id_user_id_activity_type_created_a_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260101_app_id_user_id_activity_type_created_a_idx ON public._audit_log_p20260101 USING btree (app_id, user_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20260101_app_id_user_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260101_app_id_user_id_created_at_idx ON public._audit_log_p20260101 USING btree (app_id, user_id, created_at DESC);


--
-- Name: _audit_log_p20260101_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260101_created_at_idx ON public._audit_log_p20260101 USING brin (created_at);


--
-- Name: _audit_log_p20260201_app_id_activity_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260201_app_id_activity_type_created_at_idx ON public._audit_log_p20260201 USING btree (app_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20260201_app_id_activity_type_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260201_app_id_activity_type_expr_created_at_idx ON public._audit_log_p20260201 USING btree (app_id, activity_type, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20260201_app_id_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260201_app_id_expr_created_at_idx ON public._audit_log_p20260201 USING btree (app_id, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20260201_app_id_user_id_activity_type_created_a_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260201_app_id_user_id_activity_type_created_a_idx ON public._audit_log_p20260201 USING btree (app_id, user_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20260201_app_id_user_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260201_app_id_user_id_created_at_idx ON public._audit_log_p20260201 USING btree (app_id, user_id, created_at DESC);


--
-- Name: _audit_log_p20260201_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260201_created_at_idx ON public._audit_log_p20260201 USING brin (created_at);


--
-- Name: _audit_log_p20260301_app_id_activity_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260301_app_id_activity_type_created_at_idx ON public._audit_log_p20260301 USING btree (app_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20260301_app_id_activity_type_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260301_app_id_activity_type_expr_created_at_idx ON public._audit_log_p20260301 USING btree (app_id, activity_type, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20260301_app_id_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260301_app_id_expr_created_at_idx ON public._audit_log_p20260301 USING btree (app_id, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20260301_app_id_user_id_activity_type_created_a_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260301_app_id_user_id_activity_type_created_a_idx ON public._audit_log_p20260301 USING btree (app_id, user_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20260301_app_id_user_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260301_app_id_user_id_created_at_idx ON public._audit_log_p20260301 USING btree (app_id, user_id, created_at DESC);


--
-- Name: _audit_log_p20260301_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260301_created_at_idx ON public._audit_log_p20260301 USING brin (created_at);


--
-- Name: _audit_log_p20260401_app_id_activity_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260401_app_id_activity_type_created_at_idx ON public._audit_log_p20260401 USING btree (app_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20260401_app_id_activity_type_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260401_app_id_activity_type_expr_created_at_idx ON public._audit_log_p20260401 USING btree (app_id, activity_type, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20260401_app_id_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260401_app_id_expr_created_at_idx ON public._audit_log_p20260401 USING btree (app_id, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20260401_app_id_user_id_activity_type_created_a_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260401_app_id_user_id_activity_type_created_a_idx ON public._audit_log_p20260401 USING btree (app_id, user_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20260401_app_id_user_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260401_app_id_user_id_created_at_idx ON public._audit_log_p20260401 USING btree (app_id, user_id, created_at DESC);


--
-- Name: _audit_log_p20260401_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260401_created_at_idx ON public._audit_log_p20260401 USING brin (created_at);


--
-- Name: _audit_log_p20260501_app_id_activity_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260501_app_id_activity_type_created_at_idx ON public._audit_log_p20260501 USING btree (app_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20260501_app_id_activity_type_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260501_app_id_activity_type_expr_created_at_idx ON public._audit_log_p20260501 USING btree (app_id, activity_type, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20260501_app_id_expr_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260501_app_id_expr_created_at_idx ON public._audit_log_p20260501 USING btree (app_id, ((data #>> '{payload,recipient}'::text[])), created_at DESC);


--
-- Name: _audit_log_p20260501_app_id_user_id_activity_type_created_a_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260501_app_id_user_id_activity_type_created_a_idx ON public._audit_log_p20260501 USING btree (app_id, user_id, activity_type, created_at DESC);


--
-- Name: _audit_log_p20260501_app_id_user_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260501_app_id_user_id_created_at_idx ON public._audit_log_p20260501 USING btree (app_id, user_id, created_at DESC);


--
-- Name: _audit_log_p20260501_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _audit_log_p20260501_created_at_idx ON public._audit_log_p20260501 USING brin (created_at);


--
-- Name: _auth_authenticator_app_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_authenticator_app_id ON public._auth_authenticator USING btree (app_id);


--
-- Name: _auth_authenticator_app_id_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_authenticator_app_id_user_id ON public._auth_authenticator USING btree (app_id, user_id);


--
-- Name: _auth_authenticator_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_authenticator_user_id ON public._auth_authenticator USING btree (user_id);


--
-- Name: _auth_client_resource_scope_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX _auth_client_resource_scope_unique ON public._auth_client_resource_scope USING btree (app_id, client_id, resource_id, scope_id);


--
-- Name: _auth_client_resource_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX _auth_client_resource_unique ON public._auth_client_resource USING btree (app_id, client_id, resource_id);


--
-- Name: _auth_group_app_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_group_app_id ON public._auth_group USING btree (app_id);


--
-- Name: _auth_group_key_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX _auth_group_key_unique ON public._auth_group USING btree (app_id, key);


--
-- Name: _auth_group_role_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_group_role_group ON public._auth_group_role USING btree (app_id, group_id);


--
-- Name: _auth_group_role_group_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_group_role_group_id ON public._auth_group_role USING btree (group_id);


--
-- Name: _auth_group_role_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_group_role_role ON public._auth_group_role USING btree (app_id, role_id);


--
-- Name: _auth_group_role_role_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_group_role_role_id ON public._auth_group_role USING btree (role_id);


--
-- Name: _auth_group_role_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX _auth_group_role_unique ON public._auth_group_role USING btree (app_id, group_id, role_id);


--
-- Name: _auth_identity_app_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_identity_app_id ON public._auth_identity USING btree (app_id);


--
-- Name: _auth_identity_app_id_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_identity_app_id_user_id ON public._auth_identity USING btree (app_id, user_id);


--
-- Name: _auth_identity_ldap_claim_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_identity_ldap_claim_email ON public._auth_identity_ldap USING btree (app_id, ((claims ->> 'email'::text)));


--
-- Name: _auth_identity_ldap_claim_phone_number; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_identity_ldap_claim_phone_number ON public._auth_identity_ldap USING btree (app_id, ((claims ->> 'phone_number'::text)));


--
-- Name: _auth_identity_ldap_claim_preferred_username; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_identity_ldap_claim_preferred_username ON public._auth_identity_ldap USING btree (app_id, ((claims ->> 'preferred_username'::text)));


--
-- Name: _auth_identity_login_id_claim_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_identity_login_id_claim_email ON public._auth_identity_login_id USING btree (app_id, ((claims ->> 'email'::text)));


--
-- Name: _auth_identity_login_id_claim_phone_number; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_identity_login_id_claim_phone_number ON public._auth_identity_login_id USING btree (app_id, ((claims ->> 'phone_number'::text)));


--
-- Name: _auth_identity_login_id_claim_preferred_username; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_identity_login_id_claim_preferred_username ON public._auth_identity_login_id USING btree (app_id, ((claims ->> 'preferred_username'::text)));


--
-- Name: _auth_identity_login_id_login_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_identity_login_id_login_id ON public._auth_identity_login_id USING btree (app_id, login_id_key, login_id);


--
-- Name: _auth_identity_oauth_claim_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_identity_oauth_claim_email ON public._auth_identity_oauth USING btree (app_id, ((claims ->> 'email'::text)));


--
-- Name: _auth_identity_oauth_claim_phone_number; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_identity_oauth_claim_phone_number ON public._auth_identity_oauth USING btree (app_id, ((claims ->> 'phone_number'::text)));


--
-- Name: _auth_identity_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_identity_user_id ON public._auth_identity USING btree (user_id);


--
-- Name: _auth_oauth_authorization_app_id_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_oauth_authorization_app_id_user_id ON public._auth_oauth_authorization USING btree (app_id, user_id);


--
-- Name: _auth_oauth_authorization_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_oauth_authorization_user_id ON public._auth_oauth_authorization USING btree (user_id);


--
-- Name: _auth_password_history_app_id_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_password_history_app_id_user_id ON public._auth_password_history USING btree (app_id, user_id);


--
-- Name: _auth_password_history_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_password_history_user_id ON public._auth_password_history USING btree (user_id);


--
-- Name: _auth_recovery_code_app_id_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_recovery_code_app_id_user_id ON public._auth_recovery_code USING btree (app_id, user_id);


--
-- Name: _auth_recovery_code_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_recovery_code_user_id ON public._auth_recovery_code USING btree (user_id);


--
-- Name: _auth_resource_app_id_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_resource_app_id_created_at ON public._auth_resource USING btree (app_id, created_at);


--
-- Name: _auth_resource_name_typeahead; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_resource_name_typeahead ON public._auth_resource USING btree (app_id, name text_pattern_ops);


--
-- Name: _auth_resource_scope_app_id_resource_id_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_resource_scope_app_id_resource_id_created_at ON public._auth_resource_scope USING btree (app_id, resource_id, created_at);


--
-- Name: _auth_resource_scope_scope_typeahead; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_resource_scope_scope_typeahead ON public._auth_resource_scope USING btree (app_id, resource_id, scope text_pattern_ops);


--
-- Name: _auth_resource_scope_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX _auth_resource_scope_unique ON public._auth_resource_scope USING btree (app_id, resource_id, scope);


--
-- Name: _auth_resource_uri_typeahead; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_resource_uri_typeahead ON public._auth_resource USING btree (app_id, uri text_pattern_ops);


--
-- Name: _auth_resource_uri_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX _auth_resource_uri_unique ON public._auth_resource USING btree (app_id, uri);


--
-- Name: _auth_role_app_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_role_app_id ON public._auth_role USING btree (app_id);


--
-- Name: _auth_role_key_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX _auth_role_key_unique ON public._auth_role USING btree (app_id, key);


--
-- Name: _auth_user_account_status_stale_from; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_account_status_stale_from ON public._auth_user USING btree (account_status_stale_from);


--
-- Name: _auth_user_anonymize_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_anonymize_at ON public._auth_user USING brin (anonymize_at);


--
-- Name: _auth_user_anonymized_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_anonymized_at ON public._auth_user USING brin (anonymized_at);


--
-- Name: _auth_user_app_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_app_id ON public._auth_user USING btree (app_id);


--
-- Name: _auth_user_app_id_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_app_id_created_at ON public._auth_user USING btree (app_id, created_at DESC NULLS LAST);


--
-- Name: _auth_user_app_id_last_login_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_app_id_last_login_at ON public._auth_user USING btree (app_id, last_login_at DESC NULLS LAST);


--
-- Name: _auth_user_delete_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_delete_at ON public._auth_user USING brin (delete_at);


--
-- Name: _auth_user_group_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_group_group ON public._auth_user_group USING btree (app_id, group_id);


--
-- Name: _auth_user_group_group_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_group_group_id ON public._auth_user_group USING btree (group_id);


--
-- Name: _auth_user_group_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX _auth_user_group_unique ON public._auth_user_group USING btree (app_id, user_id, group_id);


--
-- Name: _auth_user_group_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_group_user ON public._auth_user_group USING btree (app_id, user_id);


--
-- Name: _auth_user_group_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_group_user_id ON public._auth_user_group USING btree (user_id);


--
-- Name: _auth_user_role_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_role_role ON public._auth_user_role USING btree (app_id, role_id);


--
-- Name: _auth_user_role_role_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_role_role_id ON public._auth_user_role USING btree (role_id);


--
-- Name: _auth_user_role_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX _auth_user_role_unique ON public._auth_user_role USING btree (app_id, user_id, role_id);


--
-- Name: _auth_user_role_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_role_user ON public._auth_user_role USING btree (app_id, user_id);


--
-- Name: _auth_user_role_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_user_role_user_id ON public._auth_user_role USING btree (user_id);


--
-- Name: _auth_verified_claim_app_id_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_verified_claim_app_id_user_id ON public._auth_verified_claim USING btree (app_id, user_id);


--
-- Name: _auth_verified_claim_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX _auth_verified_claim_user_id ON public._auth_verified_claim USING btree (user_id);


--
-- Name: _portal_app_collaborator_invitation_code_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX _portal_app_collaborator_invitation_code_key ON public._portal_app_collaborator_invitation USING btree (code);


--
-- Name: _audit_log_default_app_id_activity_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_created_at ATTACH PARTITION public._audit_log_default_app_id_activity_type_created_at_idx;


--
-- Name: _audit_log_default_app_id_activity_type_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_data_payload_recipient_crea ATTACH PARTITION public._audit_log_default_app_id_activity_type_expr_created_at_idx;


--
-- Name: _audit_log_default_app_id_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_data_payload_recipient_created_at ATTACH PARTITION public._audit_log_default_app_id_expr_created_at_idx;


--
-- Name: _audit_log_default_app_id_user_id_activity_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_activity_type_created_at ATTACH PARTITION public._audit_log_default_app_id_user_id_activity_type_created_at_idx;


--
-- Name: _audit_log_default_app_id_user_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_created_at ATTACH PARTITION public._audit_log_default_app_id_user_id_created_at_idx;


--
-- Name: _audit_log_default_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_created_at_brin ATTACH PARTITION public._audit_log_default_created_at_idx;


--
-- Name: _audit_log_p20250901_app_id_activity_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20250901_app_id_activity_type_created_at_idx;


--
-- Name: _audit_log_p20250901_app_id_activity_type_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_data_payload_recipient_crea ATTACH PARTITION public._audit_log_p20250901_app_id_activity_type_expr_created_at_idx;


--
-- Name: _audit_log_p20250901_app_id_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_data_payload_recipient_created_at ATTACH PARTITION public._audit_log_p20250901_app_id_expr_created_at_idx;


--
-- Name: _audit_log_p20250901_app_id_user_id_activity_type_created_a_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20250901_app_id_user_id_activity_type_created_a_idx;


--
-- Name: _audit_log_p20250901_app_id_user_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_created_at ATTACH PARTITION public._audit_log_p20250901_app_id_user_id_created_at_idx;


--
-- Name: _audit_log_p20250901_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_created_at_brin ATTACH PARTITION public._audit_log_p20250901_created_at_idx;


--
-- Name: _audit_log_p20251001_app_id_activity_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20251001_app_id_activity_type_created_at_idx;


--
-- Name: _audit_log_p20251001_app_id_activity_type_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_data_payload_recipient_crea ATTACH PARTITION public._audit_log_p20251001_app_id_activity_type_expr_created_at_idx;


--
-- Name: _audit_log_p20251001_app_id_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_data_payload_recipient_created_at ATTACH PARTITION public._audit_log_p20251001_app_id_expr_created_at_idx;


--
-- Name: _audit_log_p20251001_app_id_user_id_activity_type_created_a_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20251001_app_id_user_id_activity_type_created_a_idx;


--
-- Name: _audit_log_p20251001_app_id_user_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_created_at ATTACH PARTITION public._audit_log_p20251001_app_id_user_id_created_at_idx;


--
-- Name: _audit_log_p20251001_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_created_at_brin ATTACH PARTITION public._audit_log_p20251001_created_at_idx;


--
-- Name: _audit_log_p20251101_app_id_activity_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20251101_app_id_activity_type_created_at_idx;


--
-- Name: _audit_log_p20251101_app_id_activity_type_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_data_payload_recipient_crea ATTACH PARTITION public._audit_log_p20251101_app_id_activity_type_expr_created_at_idx;


--
-- Name: _audit_log_p20251101_app_id_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_data_payload_recipient_created_at ATTACH PARTITION public._audit_log_p20251101_app_id_expr_created_at_idx;


--
-- Name: _audit_log_p20251101_app_id_user_id_activity_type_created_a_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20251101_app_id_user_id_activity_type_created_a_idx;


--
-- Name: _audit_log_p20251101_app_id_user_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_created_at ATTACH PARTITION public._audit_log_p20251101_app_id_user_id_created_at_idx;


--
-- Name: _audit_log_p20251101_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_created_at_brin ATTACH PARTITION public._audit_log_p20251101_created_at_idx;


--
-- Name: _audit_log_p20251201_app_id_activity_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20251201_app_id_activity_type_created_at_idx;


--
-- Name: _audit_log_p20251201_app_id_activity_type_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_data_payload_recipient_crea ATTACH PARTITION public._audit_log_p20251201_app_id_activity_type_expr_created_at_idx;


--
-- Name: _audit_log_p20251201_app_id_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_data_payload_recipient_created_at ATTACH PARTITION public._audit_log_p20251201_app_id_expr_created_at_idx;


--
-- Name: _audit_log_p20251201_app_id_user_id_activity_type_created_a_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20251201_app_id_user_id_activity_type_created_a_idx;


--
-- Name: _audit_log_p20251201_app_id_user_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_created_at ATTACH PARTITION public._audit_log_p20251201_app_id_user_id_created_at_idx;


--
-- Name: _audit_log_p20251201_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_created_at_brin ATTACH PARTITION public._audit_log_p20251201_created_at_idx;


--
-- Name: _audit_log_p20260101_app_id_activity_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20260101_app_id_activity_type_created_at_idx;


--
-- Name: _audit_log_p20260101_app_id_activity_type_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_data_payload_recipient_crea ATTACH PARTITION public._audit_log_p20260101_app_id_activity_type_expr_created_at_idx;


--
-- Name: _audit_log_p20260101_app_id_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_data_payload_recipient_created_at ATTACH PARTITION public._audit_log_p20260101_app_id_expr_created_at_idx;


--
-- Name: _audit_log_p20260101_app_id_user_id_activity_type_created_a_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20260101_app_id_user_id_activity_type_created_a_idx;


--
-- Name: _audit_log_p20260101_app_id_user_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_created_at ATTACH PARTITION public._audit_log_p20260101_app_id_user_id_created_at_idx;


--
-- Name: _audit_log_p20260101_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_created_at_brin ATTACH PARTITION public._audit_log_p20260101_created_at_idx;


--
-- Name: _audit_log_p20260201_app_id_activity_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20260201_app_id_activity_type_created_at_idx;


--
-- Name: _audit_log_p20260201_app_id_activity_type_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_data_payload_recipient_crea ATTACH PARTITION public._audit_log_p20260201_app_id_activity_type_expr_created_at_idx;


--
-- Name: _audit_log_p20260201_app_id_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_data_payload_recipient_created_at ATTACH PARTITION public._audit_log_p20260201_app_id_expr_created_at_idx;


--
-- Name: _audit_log_p20260201_app_id_user_id_activity_type_created_a_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20260201_app_id_user_id_activity_type_created_a_idx;


--
-- Name: _audit_log_p20260201_app_id_user_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_created_at ATTACH PARTITION public._audit_log_p20260201_app_id_user_id_created_at_idx;


--
-- Name: _audit_log_p20260201_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_created_at_brin ATTACH PARTITION public._audit_log_p20260201_created_at_idx;


--
-- Name: _audit_log_p20260301_app_id_activity_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20260301_app_id_activity_type_created_at_idx;


--
-- Name: _audit_log_p20260301_app_id_activity_type_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_data_payload_recipient_crea ATTACH PARTITION public._audit_log_p20260301_app_id_activity_type_expr_created_at_idx;


--
-- Name: _audit_log_p20260301_app_id_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_data_payload_recipient_created_at ATTACH PARTITION public._audit_log_p20260301_app_id_expr_created_at_idx;


--
-- Name: _audit_log_p20260301_app_id_user_id_activity_type_created_a_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20260301_app_id_user_id_activity_type_created_a_idx;


--
-- Name: _audit_log_p20260301_app_id_user_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_created_at ATTACH PARTITION public._audit_log_p20260301_app_id_user_id_created_at_idx;


--
-- Name: _audit_log_p20260301_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_created_at_brin ATTACH PARTITION public._audit_log_p20260301_created_at_idx;


--
-- Name: _audit_log_p20260401_app_id_activity_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20260401_app_id_activity_type_created_at_idx;


--
-- Name: _audit_log_p20260401_app_id_activity_type_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_data_payload_recipient_crea ATTACH PARTITION public._audit_log_p20260401_app_id_activity_type_expr_created_at_idx;


--
-- Name: _audit_log_p20260401_app_id_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_data_payload_recipient_created_at ATTACH PARTITION public._audit_log_p20260401_app_id_expr_created_at_idx;


--
-- Name: _audit_log_p20260401_app_id_user_id_activity_type_created_a_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20260401_app_id_user_id_activity_type_created_a_idx;


--
-- Name: _audit_log_p20260401_app_id_user_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_created_at ATTACH PARTITION public._audit_log_p20260401_app_id_user_id_created_at_idx;


--
-- Name: _audit_log_p20260401_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_created_at_brin ATTACH PARTITION public._audit_log_p20260401_created_at_idx;


--
-- Name: _audit_log_p20260501_app_id_activity_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20260501_app_id_activity_type_created_at_idx;


--
-- Name: _audit_log_p20260501_app_id_activity_type_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_activity_type_data_payload_recipient_crea ATTACH PARTITION public._audit_log_p20260501_app_id_activity_type_expr_created_at_idx;


--
-- Name: _audit_log_p20260501_app_id_expr_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_data_payload_recipient_created_at ATTACH PARTITION public._audit_log_p20260501_app_id_expr_created_at_idx;


--
-- Name: _audit_log_p20260501_app_id_user_id_activity_type_created_a_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_activity_type_created_at ATTACH PARTITION public._audit_log_p20260501_app_id_user_id_activity_type_created_a_idx;


--
-- Name: _audit_log_p20260501_app_id_user_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_app_id_user_id_created_at ATTACH PARTITION public._audit_log_p20260501_app_id_user_id_created_at_idx;


--
-- Name: _audit_log_p20260501_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public._audit_log_idx_created_at_brin ATTACH PARTITION public._audit_log_p20260501_created_at_idx;


--
-- Name: _portal_config_source notify_config_source_change; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER notify_config_source_change AFTER INSERT OR DELETE OR UPDATE ON public._portal_config_source FOR EACH ROW EXECUTE FUNCTION public.notify_config_source_change();


--
-- Name: _portal_domain notify_domain_change; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER notify_domain_change AFTER INSERT OR DELETE OR UPDATE ON public._portal_domain FOR EACH ROW EXECUTE FUNCTION public.notify_domain_change();


--
-- Name: _portal_plan notify_plan_change; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER notify_plan_change AFTER INSERT OR DELETE OR UPDATE ON public._portal_plan FOR EACH ROW EXECUTE FUNCTION public.notify_plan_change();


--
-- Name: _auth_authenticator_oob _auth_authenticator_oob_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_authenticator_oob
    ADD CONSTRAINT _auth_authenticator_oob_id_fkey FOREIGN KEY (id) REFERENCES public._auth_authenticator(id);


--
-- Name: _auth_authenticator_passkey _auth_authenticator_passkey_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_authenticator_passkey
    ADD CONSTRAINT _auth_authenticator_passkey_id_fkey FOREIGN KEY (id) REFERENCES public._auth_authenticator(id);


--
-- Name: _auth_authenticator_password _auth_authenticator_password_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_authenticator_password
    ADD CONSTRAINT _auth_authenticator_password_id_fkey FOREIGN KEY (id) REFERENCES public._auth_authenticator(id);


--
-- Name: _auth_authenticator_totp _auth_authenticator_totp_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_authenticator_totp
    ADD CONSTRAINT _auth_authenticator_totp_id_fkey FOREIGN KEY (id) REFERENCES public._auth_authenticator(id);


--
-- Name: _auth_authenticator _auth_authenticator_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_authenticator
    ADD CONSTRAINT _auth_authenticator_user_id_fkey FOREIGN KEY (user_id) REFERENCES public._auth_user(id);


--
-- Name: _auth_client_resource _auth_client_resource_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_client_resource
    ADD CONSTRAINT _auth_client_resource_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public._auth_resource(id);


--
-- Name: _auth_client_resource_scope _auth_client_resource_scope_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_client_resource_scope
    ADD CONSTRAINT _auth_client_resource_scope_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public._auth_resource(id);


--
-- Name: _auth_client_resource_scope _auth_client_resource_scope_scope_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_client_resource_scope
    ADD CONSTRAINT _auth_client_resource_scope_scope_id_fkey FOREIGN KEY (scope_id) REFERENCES public._auth_resource_scope(id);


--
-- Name: _auth_group_role _auth_group_role_group_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_group_role
    ADD CONSTRAINT _auth_group_role_group_id_fkey FOREIGN KEY (group_id) REFERENCES public._auth_group(id);


--
-- Name: _auth_group_role _auth_group_role_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_group_role
    ADD CONSTRAINT _auth_group_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES public._auth_role(id);


--
-- Name: _auth_identity_anonymous _auth_identity_anonymous_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_anonymous
    ADD CONSTRAINT _auth_identity_anonymous_id_fkey FOREIGN KEY (id) REFERENCES public._auth_identity(id);


--
-- Name: _auth_identity_biometric _auth_identity_biometric_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_biometric
    ADD CONSTRAINT _auth_identity_biometric_id_fkey FOREIGN KEY (id) REFERENCES public._auth_identity(id);


--
-- Name: _auth_identity_ldap _auth_identity_ldap_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_ldap
    ADD CONSTRAINT _auth_identity_ldap_id_fkey FOREIGN KEY (id) REFERENCES public._auth_identity(id);


--
-- Name: _auth_identity_login_id _auth_identity_login_id_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_login_id
    ADD CONSTRAINT _auth_identity_login_id_id_fkey FOREIGN KEY (id) REFERENCES public._auth_identity(id);


--
-- Name: _auth_identity_oauth _auth_identity_oauth_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_oauth
    ADD CONSTRAINT _auth_identity_oauth_id_fkey FOREIGN KEY (id) REFERENCES public._auth_identity(id);


--
-- Name: _auth_identity_passkey _auth_identity_passkey_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_passkey
    ADD CONSTRAINT _auth_identity_passkey_id_fkey FOREIGN KEY (id) REFERENCES public._auth_identity(id);


--
-- Name: _auth_identity_siwe _auth_identity_siwe_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity_siwe
    ADD CONSTRAINT _auth_identity_siwe_id_fkey FOREIGN KEY (id) REFERENCES public._auth_identity(id);


--
-- Name: _auth_identity _auth_identity_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_identity
    ADD CONSTRAINT _auth_identity_user_id_fkey FOREIGN KEY (user_id) REFERENCES public._auth_user(id);


--
-- Name: _auth_oauth_authorization _auth_oauth_authorization_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_oauth_authorization
    ADD CONSTRAINT _auth_oauth_authorization_user_id_fkey FOREIGN KEY (user_id) REFERENCES public._auth_user(id);


--
-- Name: _auth_password_history _auth_password_history_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_password_history
    ADD CONSTRAINT _auth_password_history_user_id_fkey FOREIGN KEY (user_id) REFERENCES public._auth_user(id);


--
-- Name: _auth_recovery_code _auth_recovery_code_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_recovery_code
    ADD CONSTRAINT _auth_recovery_code_user_id_fkey FOREIGN KEY (user_id) REFERENCES public._auth_user(id);


--
-- Name: _auth_resource_scope _auth_resource_scope_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_resource_scope
    ADD CONSTRAINT _auth_resource_scope_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public._auth_resource(id);


--
-- Name: _auth_user_group _auth_user_group_group_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_user_group
    ADD CONSTRAINT _auth_user_group_group_id_fkey FOREIGN KEY (group_id) REFERENCES public._auth_group(id);


--
-- Name: _auth_user_group _auth_user_group_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_user_group
    ADD CONSTRAINT _auth_user_group_user_id_fkey FOREIGN KEY (user_id) REFERENCES public._auth_user(id);


--
-- Name: _auth_user_role _auth_user_role_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_user_role
    ADD CONSTRAINT _auth_user_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES public._auth_role(id);


--
-- Name: _auth_user_role _auth_user_role_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_user_role
    ADD CONSTRAINT _auth_user_role_user_id_fkey FOREIGN KEY (user_id) REFERENCES public._auth_user(id);


--
-- Name: _auth_verified_claim _auth_verified_claim_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public._auth_verified_claim
    ADD CONSTRAINT _auth_verified_claim_user_id_fkey FOREIGN KEY (user_id) REFERENCES public._auth_user(id);


--
-- PostgreSQL database dump complete
--

\unrestrict Kk8JFQ5F0vcDzklBgaPeDz6PZmBXVAGavdHnJpIMASbDbqqhCTWrWXxTG6ziQYt

