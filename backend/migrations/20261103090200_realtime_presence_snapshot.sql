begin;

-- Drivers need a current passenger snapshot after joining or recovering a route
-- channel because Realtime broadcasts are transient and are not replayed.
create or replace function public.get_active_passenger_locations(p_route_id uuid)
returns table (
    passenger_id uuid,
    route_id uuid,
    latitude double precision,
    longitude double precision,
    recorded_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_driver public.drivers;
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;

    select d.* into v_driver
    from public.drivers as d
    where d.user_id = v_uid;
    if not found or not v_driver.verified or v_driver.route_id is distinct from p_route_id then
        raise exception 'Verified driver assigned to this route is required';
    end if;

    return query
    select p.passenger_id, p.route_id, p.latitude, p.longitude, p.recorded_at
    from public.passenger_current_state as p
    where p.route_id = p_route_id
      and p.updated_at >= pg_catalog.now() - interval '1 minute'
    order by p.updated_at desc;
end;
$function$;

revoke all on function public.get_active_passenger_locations(uuid) from public, anon;
grant execute on function public.get_active_passenger_locations(uuid) to authenticated;

commit;
