-- Persist the current device compass heading with the current vehicle coordinate.
-- Existing GPS sampling and location publication cadence are untouched.
begin;
alter table public.vehicle_current_state add column if not exists heading_degrees double precision;
alter table public.vehicle_current_state drop constraint if exists vehicle_current_state_heading_degrees_range;
alter table public.vehicle_current_state add constraint vehicle_current_state_heading_degrees_range
    check (heading_degrees is null or (heading_degrees >= 0 and heading_degrees < 360));
drop view if exists public.vehicle_current_locations;
create view public.vehicle_current_locations with (security_invoker = true) as
select v.id, v.identifier, v.route_id,
       (v.active and s.route_id = v.route_id and s.recorded_at >= now() - interval '25 seconds') as active,
       s.latitude, s.longitude, s.recorded_at, s.heading_degrees
from public.vehicles v left join public.vehicle_current_state s on s.vehicle_id = v.id;
grant select on public.vehicle_current_locations to authenticated;
drop function if exists public.submit_vehicle_location(double precision, double precision, timestamptz);
create function public.submit_vehicle_location(
 p_latitude double precision, p_longitude double precision, p_recorded_at timestamptz,
 p_heading_degrees double precision default null
) returns public.vehicle_current_state
language plpgsql security definer set search_path = public, pg_temp
as $function$
declare v_driver public.drivers; v_state public.vehicle_current_state;
begin
 if auth.uid() is null then raise exception 'Authentication is required'; end if;
 if p_latitude < -90 or p_latitude > 90 or p_longitude < -180 or p_longitude > 180 then raise exception 'Invalid coordinates'; end if;
 if p_heading_degrees is not null and (p_heading_degrees < 0 or p_heading_degrees >= 360) then raise exception 'Invalid heading'; end if;
 select * into v_driver from public.drivers where user_id = auth.uid();
 if not found then raise exception 'Driver profile not found'; end if;
 if v_driver.route_id is null then raise exception 'Driver route is not assigned yet'; end if;
 if not v_driver.verified then raise exception 'Driver is not verified'; end if;
 update public.vehicles set active = true, route_id = v_driver.route_id, updated_at = now() where id = v_driver.vehicle_id;
 insert into public.vehicle_current_state(vehicle_id, route_id, latitude, longitude, recorded_at, updated_at, heading_degrees)
 values (v_driver.vehicle_id, v_driver.route_id, p_latitude, p_longitude, p_recorded_at, now(), p_heading_degrees)
 on conflict (vehicle_id) do update set route_id = excluded.route_id, latitude = excluded.latitude,
 longitude = excluded.longitude, recorded_at = excluded.recorded_at, updated_at = now(), heading_degrees = excluded.heading_degrees
 where excluded.recorded_at >= public.vehicle_current_state.recorded_at
 returning * into v_state;
 if v_state.vehicle_id is null then select * into v_state from public.vehicle_current_state where vehicle_id = v_driver.vehicle_id; end if;
 return v_state;
end;
$function$;
revoke all on function public.submit_vehicle_location(double precision, double precision, timestamptz, double precision) from public, anon;
grant execute on function public.submit_vehicle_location(double precision, double precision, timestamptz, double precision) to authenticated;
create or replace function public.broadcast_vehicle_location()
returns trigger language plpgsql security definer set search_path = ''
as $function$
begin
 if tg_op = 'DELETE' then
  perform realtime.send(jsonb_build_object('vehicle_id', old.vehicle_id, 'route_id', old.route_id),
   'vehicle_location_removed', 'safar:vehicle-location:route:' || old.route_id::text, true);
  return null;
 end if;
 if tg_op = 'UPDATE' and old.route_id is distinct from new.route_id then
  perform realtime.send(jsonb_build_object('vehicle_id', old.vehicle_id, 'route_id', old.route_id),
   'vehicle_location_removed', 'safar:vehicle-location:route:' || old.route_id::text, true);
 end if;
 perform realtime.send(jsonb_build_object('vehicle_id', new.vehicle_id, 'route_id', new.route_id,
  'latitude', new.latitude, 'longitude', new.longitude, 'recorded_at', new.recorded_at,
  'heading_degrees', new.heading_degrees), 'vehicle_location',
  'safar:vehicle-location:route:' || new.route_id::text, true);
 return null;
end;
$function$;
commit;
