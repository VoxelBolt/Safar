-- Use server time for vehicle freshness while rejecting stale or clock-skewed GPS fixes.
-- This prevents an active driver from disappearing when a device clock is behind
-- the previously stored timestamp. The client must still provide a recent GPS fix.
begin;

create or replace function public.submit_vehicle_location(
    p_latitude double precision,
    p_longitude double precision,
    p_recorded_at timestamptz,
    p_heading_degrees double precision default null
) returns public.vehicle_current_state
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    v_driver public.drivers;
    v_state public.vehicle_current_state;
    v_server_recorded_at timestamptz := clock_timestamp();
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;

    if p_latitude is null or p_longitude is null
       or p_latitude < -90 or p_latitude > 90
       or p_longitude < -180 or p_longitude > 180 then
        raise exception 'Invalid coordinates';
    end if;

    if p_recorded_at is null
       or p_recorded_at < v_server_recorded_at - interval '3 minutes'
       or p_recorded_at > v_server_recorded_at + interval '30 seconds' then
        raise exception 'Location fix is stale or device clock is out of sync';
    end if;

    if p_heading_degrees is not null
       and (p_heading_degrees < 0 or p_heading_degrees >= 360) then
        raise exception 'Invalid heading';
    end if;

    select *
    into v_driver
    from public.drivers
    where user_id = auth.uid();

    if not found then
        raise exception 'Driver profile not found';
    end if;

    if v_driver.route_id is null then
        raise exception 'Driver route is not assigned yet';
    end if;

    if not v_driver.verified then
        raise exception 'Driver is not verified';
    end if;

    update public.vehicles
    set active = true,
        route_id = v_driver.route_id,
        updated_at = v_server_recorded_at
    where id = v_driver.vehicle_id;

    insert into public.vehicle_current_state(
        vehicle_id, route_id, latitude, longitude, recorded_at, updated_at, heading_degrees
    )
    values (
        v_driver.vehicle_id, v_driver.route_id, p_latitude, p_longitude,
        v_server_recorded_at, v_server_recorded_at, p_heading_degrees
    )
    on conflict (vehicle_id) do update
    set route_id = excluded.route_id,
        latitude = excluded.latitude,
        longitude = excluded.longitude,
        recorded_at = excluded.recorded_at,
        updated_at = excluded.updated_at,
        heading_degrees = excluded.heading_degrees
    where excluded.recorded_at >= public.vehicle_current_state.recorded_at
    returning * into v_state;

    if v_state.vehicle_id is null then
        select *
        into v_state
        from public.vehicle_current_state
        where vehicle_id = v_driver.vehicle_id;
    end if;

    return v_state;
end;
$function$;

revoke all on function public.submit_vehicle_location(double precision, double precision, timestamptz, double precision)
    from public, anon;
grant execute on function public.submit_vehicle_location(double precision, double precision, timestamptz, double precision)
    to authenticated;

commit;
