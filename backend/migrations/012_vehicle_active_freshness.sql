-- Keep server-side active status consistent with the app's 5-minute location freshness.
-- A 25-second cutoff made live drivers flicker to "no active transport" during
-- brief GPS/network delays even though their current location was still usable.
begin;
create or replace view public.vehicle_current_locations
with (security_invoker = true)
as
select
    v.id,
    v.identifier,
    v.route_id,
    (
        v.active
        and s.route_id = v.route_id
        and s.recorded_at >= now() - interval '5 minutes'
    ) as active,
    s.latitude,
    s.longitude,
    s.recorded_at,
    s.heading_degrees
from public.vehicles v
left join public.vehicle_current_state s
    on s.vehicle_id = v.id;
grant select on public.vehicle_current_locations to authenticated;
commit;
