begin;

-- Server-side totals let the client page the visible session rows without
-- downloading the driver's entire history just to calculate summary cards.
create or replace function public.get_work_session_summary(
    p_start_at timestamptz default null,
    p_end_at timestamptz default null
)
returns table (
    session_count bigint,
    work_seconds bigint,
    distance_meters double precision
)
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_now timestamptz := pg_catalog.transaction_timestamp();
begin
    if v_uid is null or not exists (
        select 1 from public.drivers as d where d.user_id = v_uid
    ) then
        raise exception 'Driver authentication is required';
    end if;

    return query
    select
        count(*)::bigint,
        coalesce(sum(
            case
                when s.status::text = 'FINISHED' then
                    case
                        when s.duration_seconds > 0 then s.duration_seconds
                        else greatest(
                            extract(epoch from (coalesce(s.finished_at, v_now) - s.started_at))::bigint
                                - s.total_paused_seconds,
                            0
                        )
                    end
                when s.status::text = 'PAUSED' then greatest(
                    extract(epoch from (coalesce(s.paused_at, v_now) - s.started_at))::bigint
                        - s.total_paused_seconds,
                    0
                )
                else greatest(
                    extract(epoch from (v_now - s.started_at))::bigint
                        - s.total_paused_seconds,
                    0
                )
            end
        ), 0)::bigint,
        coalesce(sum(greatest(s.distance_meters, 0)), 0)::double precision
    from public.work_sessions as s
    where s.driver_id = v_uid
      and (p_start_at is null or s.started_at >= p_start_at)
      and (p_end_at is null or s.started_at <= p_end_at);
end;
$function$;

revoke all on function public.get_work_session_summary(timestamptz, timestamptz) from public, anon;
grant execute on function public.get_work_session_summary(timestamptz, timestamptz) to authenticated;

commit;
