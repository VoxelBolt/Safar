begin;

-- Backfilled GPS fixes update the authoritative baseline and work-session
-- distance, but they must not be broadcast as if the vehicle were live at an
-- old coordinate. The next fresh single-fix submission activates/broadcasts it.
create or replace function public.broadcast_vehicle_location()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    if pg_catalog.current_setting('safar.suppress_vehicle_location_broadcast', true) = 'on' then
        return null;
    end if;

    if tg_op = 'DELETE' then
        perform realtime.send(
            pg_catalog.jsonb_build_object('vehicle_id', old.vehicle_id, 'route_id', old.route_id),
            'vehicle_location_removed',
            'safar:vehicle-location:route:' || old.route_id::text,
            true
        );
        return null;
    end if;

    if tg_op = 'UPDATE' and old.route_id is distinct from new.route_id then
        perform realtime.send(
            pg_catalog.jsonb_build_object('vehicle_id', old.vehicle_id, 'route_id', old.route_id),
            'vehicle_location_removed',
            'safar:vehicle-location:route:' || old.route_id::text,
            true
        );
    end if;

    perform realtime.send(
        pg_catalog.jsonb_build_object(
            'vehicle_id', new.vehicle_id,
            'route_id', new.route_id,
            'latitude', new.latitude,
            'longitude', new.longitude,
            'recorded_at', new.recorded_at,
            'heading_degrees', new.heading_degrees
        ),
        'vehicle_location',
        'safar:vehicle-location:route:' || new.route_id::text,
        true
    );
    return null;
end;
$function$;
revoke all on function public.broadcast_vehicle_location() from public, anon, authenticated;

create or replace function public.submit_vehicle_location_batch(p_locations jsonb)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_driver public.drivers;
    v_fix record;
    v_previous_recorded_at timestamptz;
    v_fix_count integer;
begin
    if v_uid is null then raise exception 'Authentication is required'; end if;
    if pg_catalog.jsonb_typeof(p_locations) is distinct from 'array' then
        raise exception 'Location batch must be a JSON array';
    end if;
    v_fix_count := pg_catalog.jsonb_array_length(p_locations);
    if v_fix_count < 1 or v_fix_count > 100 then
        raise exception 'Location batch must contain between 1 and 100 fixes';
    end if;

    select d.* into v_driver
    from public.drivers as d
    where d.user_id = v_uid
    for update;
    if not found or not v_driver.verified or v_driver.route_id is null then
        raise exception 'Verified driver with an assigned route is required';
    end if;

    perform pg_catalog.set_config('safar.suppress_vehicle_location_broadcast', 'on', true);
    for v_fix in
        select f.latitude, f.longitude, f.recorded_at, f.heading_degrees
        from pg_catalog.jsonb_to_recordset(p_locations) as f(
            latitude double precision,
            longitude double precision,
            recorded_at timestamptz,
            heading_degrees double precision
        )
        order by f.recorded_at
    loop
        if v_fix.recorded_at is null
           or (v_previous_recorded_at is not null and v_fix.recorded_at <= v_previous_recorded_at) then
            raise exception 'Location batch timestamps must be non-null, unique, and increasing';
        end if;
        perform public.submit_vehicle_location(
            v_fix.latitude,
            v_fix.longitude,
            v_fix.recorded_at,
            v_fix.heading_degrees
        );
        v_previous_recorded_at := v_fix.recorded_at;
    end loop;

    -- Keep a movement baseline for the next live fix, but leave the vehicle
    -- inactive and its public location stale until a fresh fix arrives.
    update public.vehicle_current_state as s
    set recorded_at = s.location_fix_at,
        updated_at = pg_catalog.clock_timestamp()
    where s.vehicle_id = v_driver.vehicle_id;
    update public.vehicles as v
    set active = false,
        updated_at = pg_catalog.clock_timestamp()
    where v.id = v_driver.vehicle_id;

    perform pg_catalog.set_config('safar.suppress_vehicle_location_broadcast', 'off', true);
end;
$function$;
revoke all on function public.submit_vehicle_location_batch(jsonb) from public, anon;
grant execute on function public.submit_vehicle_location_batch(jsonb) to authenticated;

commit;
