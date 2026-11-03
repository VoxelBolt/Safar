-- Accumulate accepted driver movement into the active work session.
-- Coordinates are already stabilized by AndroidLocationProvider; server-side
-- speed and time checks provide a second guard against GPS teleports.
begin;

alter table public.work_sessions
    add column if not exists distance_anchor_latitude double precision,
    add column if not exists distance_anchor_longitude double precision,
    add column if not exists distance_anchor_at timestamptz;

alter table public.vehicle_current_state
    add column if not exists location_fix_at timestamptz;

update public.vehicle_current_state
-- Earlier schema versions stored a server timestamp here, so allow one normal
-- two-second GPS interval for the first device-timestamped fix after upgrade.
set location_fix_at = recorded_at - interval '2 seconds'
where location_fix_at is null;

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
    v_previous public.vehicle_current_state;
    v_session public.work_sessions;
    v_now timestamptz := clock_timestamp();
    v_gap_seconds double precision;
    v_distance double precision;
    v_anchor_gap_seconds double precision;
    v_anchor_distance double precision;
    v_lat1 double precision;
    v_lat2 double precision;
    v_dlat double precision;
    v_dlon double precision;
    v_h double precision;
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
       or p_recorded_at < v_now - interval '3 minutes'
       or p_recorded_at > v_now + interval '30 seconds' then
        raise exception 'Location fix is stale or device clock is out of sync';
    end if;
    if p_heading_degrees is not null
       and (p_heading_degrees < 0 or p_heading_degrees >= 360) then
        raise exception 'Invalid heading';
    end if;

    select * into v_driver
    from public.drivers
    where user_id = auth.uid();
    if not found then raise exception 'Driver profile not found'; end if;
    if v_driver.route_id is null then raise exception 'Driver route is not assigned yet'; end if;
    if not v_driver.verified then raise exception 'Driver is not verified'; end if;

    update public.vehicles
    set active = true, route_id = v_driver.route_id, updated_at = v_now
    where id = v_driver.vehicle_id;

    -- Serialize GPS callbacks for this vehicle, and reject physically impossible
    -- jumps before they can move the shared marker or inflate session distance.
    select * into v_previous
    from public.vehicle_current_state
    where vehicle_id = v_driver.vehicle_id
    for update;

    if found and v_previous.location_fix_at is not null then
        -- Ignore duplicate and out-of-order callbacks. Freshness for passenger
        -- maps still uses recorded_at, which remains server controlled. A
        -- duplicate cached fix can still carry a new compass heading, so refresh
        -- only its heartbeat and heading while keeping its coordinates fixed.
        if p_recorded_at <= v_previous.location_fix_at then
            update public.vehicle_current_state
            set recorded_at = v_now,
                updated_at = v_now,
                heading_degrees = coalesce(p_heading_degrees, heading_degrees)
            where vehicle_id = v_driver.vehicle_id
            returning * into v_state;
            return v_state;
        end if;
        v_gap_seconds := extract(epoch from (p_recorded_at - v_previous.location_fix_at));
        if v_gap_seconds between 0.25 and 120 then
            v_lat1 := radians(v_previous.latitude);
            v_lat2 := radians(p_latitude);
            v_dlat := radians(p_latitude - v_previous.latitude);
            v_dlon := radians(p_longitude - v_previous.longitude);
            v_h := sin(v_dlat / 2) * sin(v_dlat / 2)
                 + cos(v_lat1) * cos(v_lat2) * sin(v_dlon / 2) * sin(v_dlon / 2);
            v_distance := 6371000 * 2 * asin(sqrt(least(1, greatest(0, v_h))));
            if v_distance / v_gap_seconds > 45 then
                return v_previous;
            end if;
        end if;
    end if;

    insert into public.vehicle_current_state(
        vehicle_id, route_id, latitude, longitude, recorded_at, updated_at, heading_degrees, location_fix_at
    ) values (
        v_driver.vehicle_id, v_driver.route_id, p_latitude, p_longitude,
        v_now, v_now, p_heading_degrees, p_recorded_at
    )
    on conflict (vehicle_id) do update set
        route_id = excluded.route_id,
        latitude = excluded.latitude,
        longitude = excluded.longitude,
        recorded_at = excluded.recorded_at,
        updated_at = excluded.updated_at,
        location_fix_at = excluded.location_fix_at,
        heading_degrees = coalesce(excluded.heading_degrees, public.vehicle_current_state.heading_degrees)
    returning * into v_state;

    select * into v_session
    from public.work_sessions
    where driver_id = auth.uid() and vehicle_id = v_driver.vehicle_id and status = 'ACTIVE'
    order by started_at desc
    limit 1
    for update;

    if found then
        if v_session.distance_anchor_latitude is null
           or v_session.distance_anchor_longitude is null
           or v_session.distance_anchor_at is null then
            update public.work_sessions
            set distance_anchor_latitude = p_latitude,
                distance_anchor_longitude = p_longitude,
                distance_anchor_at = p_recorded_at,
                updated_at = v_now
            where id = v_session.id;
        else
            v_anchor_gap_seconds := extract(epoch from (p_recorded_at - v_session.distance_anchor_at));
            if v_anchor_gap_seconds <= 0 or v_anchor_gap_seconds > 120 then
                -- Do not bridge long GPS outages with a straight line that may
                -- include time when the driver was not actually being tracked.
                update public.work_sessions
                set distance_anchor_latitude = p_latitude,
                    distance_anchor_longitude = p_longitude,
                    distance_anchor_at = p_recorded_at,
                    updated_at = v_now
                where id = v_session.id;
            else
                v_lat1 := radians(v_session.distance_anchor_latitude);
                v_lat2 := radians(p_latitude);
                v_dlat := radians(p_latitude - v_session.distance_anchor_latitude);
                v_dlon := radians(p_longitude - v_session.distance_anchor_longitude);
                v_h := sin(v_dlat / 2) * sin(v_dlat / 2)
                     + cos(v_lat1) * cos(v_lat2) * sin(v_dlon / 2) * sin(v_dlon / 2);
                v_anchor_distance := 6371000 * 2 * asin(sqrt(least(1, greatest(0, v_h))));

                if v_anchor_distance >= 3.5 then
                    update public.work_sessions
                    set distance_meters = distance_meters + v_anchor_distance,
                        distance_anchor_latitude = p_latitude,
                        distance_anchor_longitude = p_longitude,
                        distance_anchor_at = p_recorded_at,
                        updated_at = v_now
                    where id = v_session.id;
                end if;
            end if;
        end if;
    end if;

    return v_state;
end;
$function$;

revoke all on function public.submit_vehicle_location(double precision, double precision, timestamptz, double precision)
    from public, anon;
grant execute on function public.submit_vehicle_location(double precision, double precision, timestamptz, double precision)
    to authenticated;

commit;
