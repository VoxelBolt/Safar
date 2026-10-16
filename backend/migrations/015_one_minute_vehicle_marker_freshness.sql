-- Enforce the product rule that a driver marker disappears after one minute
-- without a server-accepted location update. Migration 012 widened this to five
-- minutes; restore the intended one-minute window while preserving compass heading.
begin;

create or replace view public.vehicle_current_locations
with (security_invoker = true)
as
select
    v.id,
    v.identifier,
    v.route_id,
    (
        coalesce(v.active, false)
        and coalesce(s.route_id = v.route_id, false)
        and coalesce(s.recorded_at >= now() - interval '1 minute', false)
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
