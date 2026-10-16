-- A foreground GPS publisher may not have received a compass sample yet.
-- In that case, preserve the last measured heading instead of resetting it to NULL.
begin;

create or replace function public.submit_vehicle_location(
 p_latitude double precision,
 p_longitude double precision,
 p_recorded_at timestamptz,
 p_heading_degrees double precision default null
) returns public.vehicle_current_state
language plpgsql security definer set search_path = public, pg_temp
as $function$
declare
 v_driver public.drivers;
 v_state public.vehicle_current_state;
begin
 if auth.uid() is null then raise exception 'Authentication is required'; end if;
 if p_latitude < -90 or p_latitude > 90 or p_longitude < -180 or p_longitude > 180 then
   raise exception 'Invalid coordinates';
 end if;
 if p_heading_degrees is not null and (p_heading_degrees < 0 or p_heading_degrees >= 360) then
   raise exception 'Invalid heading';
 end if;

 select * into v_driver from public.drivers where user_id = auth.uid();
 if not found then raise exception 'Driver profile not found'; end if;
 if v_driver.route_id is null then raise exception 'Driver route is not assigned yet'; end if;
 if not v_driver.verified then raise exception 'Driver is not verified'; end if;

 update public.vehicles
 set active = true, route_id = v_driver.route_id, updated_at = now()
 where id = v_driver.vehicle_id;

 insert into public.vehicle_current_state(
   vehicle_id, route_id, latitude, longitude, recorded_at, updated_at, heading_degrees
 )
 values (
   v_driver.vehicle_id, v_driver.route_id, p_latitude, p_longitude,
   p_recorded_at, now(), p_heading_degrees
 )
 on conflict (vehicle_id) do update set
   route_id = excluded.route_id,
   latitude = excluded.latitude,
   longitude = excluded.longitude,
   recorded_at = excluded.recorded_at,
   updated_at = now(),
   heading_degrees = coalesce(excluded.heading_degrees, public.vehicle_current_state.heading_degrees)
 where excluded.recorded_at >= public.vehicle_current_state.recorded_at
 returning * into v_state;

 if v_state.vehicle_id is null then
   select * into v_state from public.vehicle_current_state where vehicle_id = v_driver.vehicle_id;
 end if;
 return v_state;
end;
$function$;

revoke all on function public.submit_vehicle_location(double precision, double precision, timestamptz, double precision) from public, anon;
grant execute on function public.submit_vehicle_location(double precision, double precision, timestamptz, double precision) to authenticated;

commit;
