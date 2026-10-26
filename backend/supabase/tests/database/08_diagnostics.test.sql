begin;
select plan(5);

select ok(to_regclass('public.app_diagnostic_reports') is not null, 'diagnostic reports table exists');
select ok((select relrowsecurity from pg_class where oid = 'public.app_diagnostic_reports'::regclass), 'diagnostic reports enforce RLS');
select ok(not has_table_privilege('anon', 'public.app_diagnostic_reports', 'select'), 'anonymous clients cannot read diagnostic reports');
select ok(not has_table_privilege('authenticated', 'public.app_diagnostic_reports', 'select'), 'signed-in clients cannot read diagnostic reports directly');
select ok(has_table_privilege('service_role', 'public.app_diagnostic_reports', 'insert'), 'service role can persist verified reports');

select * from finish();
rollback;
