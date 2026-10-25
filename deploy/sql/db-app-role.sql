-- api 실행 역할(loakraken_app): 표 읽기·추가·수정·삭제와 시퀀스 사용만 한다. 스키마 변경(Flyway)은 소유자 역할이 한다.
-- 소유자(슈퍼유저)로 실행한다. 여러 번 실행해도 같다(암호·설정·권한을 다시 맞춘다).
-- psql 변수: pw(실행 역할 암호), owner(마이그레이션을 실행하는 소유자 역할), db(데이터베이스 이름).
-- 암호는 명령줄에 두지 않는다. 표준 입력 첫 줄의 \set pw '…'로 넘긴다(배포 안내 참고).
SELECT 'CREATE ROLE loakraken_app LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'loakraken_app') \gexec
ALTER ROLE loakraken_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'pw';
-- 문장 하나가 오래 걸리거나 트랜잭션이 멈춘 채 남아 연결을 붙잡지 않게 한다. 더 오래 걸려야 하는 작업은 그 트랜잭션 안에서 SET LOCAL로 늘린다.
ALTER ROLE loakraken_app SET statement_timeout = '15s';
ALTER ROLE loakraken_app SET idle_in_transaction_session_timeout = '60s';
GRANT CONNECT ON DATABASE :"db" TO loakraken_app;
GRANT USAGE ON SCHEMA public TO loakraken_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO loakraken_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO loakraken_app;
-- 마이그레이션 기록은 Flyway(소유자)만 쓴다.
SELECT 'REVOKE ALL ON TABLE public.flyway_schema_history FROM loakraken_app' WHERE to_regclass('public.flyway_schema_history') IS NOT NULL \gexec
-- 앞으로 소유자가 마이그레이션으로 만드는 표·시퀀스도 같은 권한을 받는다.
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO loakraken_app;
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO loakraken_app;
-- 확인: 모두 맞으면 DB_APP_ROLE_OK 한 단어를 출력한다.
SELECT CASE WHEN r.rolcanlogin AND NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole AND NOT r.rolbypassrls AND (SELECT coalesce(bool_and(has_table_privilege('loakraken_app', c.oid, 'SELECT') AND has_table_privilege('loakraken_app', c.oid, 'INSERT') AND has_table_privilege('loakraken_app', c.oid, 'UPDATE') AND has_table_privilege('loakraken_app', c.oid, 'DELETE')), false) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND c.relname <> 'flyway_schema_history') AND NOT has_schema_privilege('loakraken_app', 'public', 'CREATE') AND (to_regclass('public.flyway_schema_history') IS NULL OR NOT has_table_privilege('loakraken_app', 'public.flyway_schema_history', 'INSERT, UPDATE, DELETE, TRUNCATE')) AND r.rolconfig @> ARRAY['statement_timeout=15s', 'idle_in_transaction_session_timeout=60s'] THEN 'DB_APP_ROLE_OK' ELSE 'DB_APP_ROLE_CHECK' END FROM pg_roles r WHERE r.rolname = 'loakraken_app';
