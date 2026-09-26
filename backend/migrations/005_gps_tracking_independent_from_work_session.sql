-- Stage 06: GPS tracking is independent from the Work Session timer.
-- Applied to Supabase as migration:
-- 20260919112033_gps_tracking_independent_from_work_session

create or replace function public.submit_vehicle_location(
    p_latitude double precision,
    p_longitude double precision,
    p_recorded_at timestamp with time zone
)
returns public.vehicle_locations
language plpgsql
security definer
set search_path to 'public', 'pg_temp'
as $function$
declare
    v_driver public.drivers;
    v_location public.vehicle_locations;
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;

    if p_latitude < -90 or p_latitude > 90
       or p_longitude < -180 or p_longitude > 180 then
        raise exception 'Invalid coordinates';
    end if;

    select * into v_driver
    from public.drivers
    where user_id = auth.uid();

    if not found then
        raise exception 'Driver profile not found';
    end if;

    if v_driver.route_id is null then
        raise exception 'Driver route is not assigned yet';
    end if;

    update public.vehicles
    set active = true,
        updated_at = now()
    where id = v_driver.vehicle_id;

    insert into public.vehicle_locations(
        vehicle_id,
        latitude,
        longitude,
        recorded_at
    )
    values (
        v_driver.vehicle_id,
        p_latitude,
        p_longitude,
        p_recorded_at
    )
    returning * into v_location;

    return v_location;
end;
$function$;

create or replace function public.start_work_session()
returns public.work_sessions
language plpgsql
security definer
set search_path to 'public', 'pg_temp'
as $function$
declare
    v_uid uuid := auth.uid();
    v_driver public.drivers;
    v_session public.work_sessions;
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;

    select * into v_driver
    from public.drivers
    where user_id = v_uid
    for update;

    if not found or not v_driver.verified then
        raise exception 'Verified driver profile is required';
    end if;

    if v_driver.route_id is null then
        raise exception 'Driver route is not assigned yet';
    end if;

    select * into v_session
    from public.work_sessions
    where driver_id = v_uid
      and status in ('ACTIVE', 'PAUSED')
    order by started_at desc
    limit 1
    for update;

    if found then
        if v_session.status = 'PAUSED'
           and v_session.paused_at is not null
           and now() - v_session.paused_at > interval '6 hours' then
            update public.work_sessions
            set status = 'FINISHED',
                finished_at = now(),
                duration_seconds = greatest(
                    0,
                    extract(epoch from (
                        coalesce(paused_at, now()) - started_at
                    ))::bigint - total_paused_seconds
                )
            where id = v_session.id;
        else
            return v_session;
        end if;
    end if;

    insert into public.work_sessions(driver_id, vehicle_id, started_at, status)
    values (v_uid, v_driver.vehicle_id, now(), 'ACTIVE')
    returning * into v_session;

    return v_session;
end;
$function$;

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
        updated_at = now()
    where id = p_session_id
    returning * into v_session;

    return v_session;
end;
$function$;

create or replace function public.finish_work_session(p_session_id uuid)
returns public.work_sessions
language plpgsql
security definer
set search_path to 'public', 'pg_temp'
as $function$
declare
    v_session public.work_sessions;
    v_paused_total bigint;
    v_duration bigint;
begin
    select * into v_session
    from public.work_sessions
    where id = p_session_id
      and driver_id = auth.uid()
    for update;

    if not found then
        raise exception 'Work session not found';
    end if;

    if v_session.status = 'FINISHED' then
        return v_session;
    end if;

    v_paused_total := v_session.total_paused_seconds;

    if v_session.status = 'PAUSED' and v_session.paused_at is not null then
        v_paused_total := v_paused_total + greatest(
            0,
            extract(epoch from (now() - v_session.paused_at))::bigint
        );
    end if;

    v_duration := greatest(
        0,
        extract(epoch from (now() - v_session.started_at))::bigint - v_paused_total
    );

    update public.work_sessions
    set status = 'FINISHED',
        finished_at = now(),
        paused_at = null,
        total_paused_seconds = v_paused_total,
        duration_seconds = v_duration,
        updated_at = now()
    where id = p_session_id
    returning * into v_session;

    return v_session;
end;
$function$;

do $publication$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'vehicle_locations'
    ) then
        alter publication supabase_realtime
            add table public.vehicle_locations;
    end if;
end
$publication$;
