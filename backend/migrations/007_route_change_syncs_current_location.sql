-- Keep the single current-location row aligned with the driver's selected route.
-- If a driver changes route while tracking, the current-state trigger broadcasts
-- removal from the old route and the current position on the new route.
create or replace function public.assign_driver_route(p_route_id uuid)
returns public.drivers
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    v_uid uuid := auth.uid();
    v_driver public.drivers;
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;

    if not exists (select 1 from public.routes where id = p_route_id) then
        raise exception 'Route not found';
    end if;

    select *
    into v_driver
    from public.drivers
    where user_id = v_uid
    for update;

    if not found then
        raise exception 'Driver profile not found';
    end if;

    update public.drivers
    set route_id = p_route_id,
        updated_at = now()
    where user_id = v_uid
    returning * into v_driver;

    update public.vehicles
    set route_id = p_route_id,
        updated_at = now()
    where id = v_driver.vehicle_id;

    update public.vehicle_current_state
    set route_id = p_route_id,
        updated_at = now()
    where vehicle_id = v_driver.vehicle_id;

    return v_driver;
end;
$function$;
