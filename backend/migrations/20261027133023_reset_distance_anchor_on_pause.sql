-- Do not count movement made while a driver has paused their work session.
-- The first accepted GPS fix after resume becomes a fresh distance anchor.
begin;

create or replace function public.pause_work_session(p_session_id uuid)
returns public.work_sessions
language plpgsql
security definer
set search_path to 'public', 'pg_temp'
as $function$
declare
    v_session public.work_sessions;
begin
    select * into v_session
    from public.work_sessions
    where id = p_session_id
      and driver_id = auth.uid()
    for update;

    if not found then
        raise exception 'Work session not found';
    end if;

    if v_session.status <> 'ACTIVE' then
        raise exception 'Work session is not active';
    end if;

    update public.work_sessions
    set status = 'PAUSED',
        paused_at = now(),
        distance_anchor_latitude = null,
        distance_anchor_longitude = null,
        distance_anchor_at = null,
        updated_at = now()
    where id = p_session_id
    returning * into v_session;

    return v_session;
end;
$function$;

create or replace function public.resume_work_session(p_session_id uuid)
returns public.work_sessions
language plpgsql
security definer
set search_path to 'public', 'pg_temp'
as $function$
declare
    v_session public.work_sessions;
    v_added_pause bigint;
begin
    select * into v_session
    from public.work_sessions
    where id = p_session_id
      and driver_id = auth.uid()
    for update;

    if not found then
        raise exception 'Work session not found';
    end if;

    if v_session.status <> 'PAUSED' or v_session.paused_at is null then
        raise exception 'Work session is not paused';
    end if;

    v_added_pause := greatest(
        0,
        extract(epoch from (now() - v_session.paused_at))::bigint
    );

    update public.work_sessions
    set status = 'ACTIVE',
        total_paused_seconds = total_paused_seconds + v_added_pause,
        paused_at = null,
        distance_anchor_latitude = null,
        distance_anchor_longitude = null,
        distance_anchor_at = null,
        updated_at = now()
    where id = p_session_id
    returning * into v_session;

    return v_session;
end;
$function$;

commit;
