-- Passenger location is transient realtime state, not a history table.
-- Allow authenticated clients to send route-scoped Broadcast messages and
-- keep vehicle current-state visibility aligned with the one-minute stale rule.

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
        and s.recorded_at >= now() - interval '1 minute'
    ) as active,
    s.latitude,
    s.longitude,
    s.recorded_at
from public.vehicles v
left join public.vehicle_current_state s
    on s.vehicle_id = v.id;

grant select on public.vehicle_current_locations to authenticated;

drop policy if exists safar_vehicle_location_broadcast_insert
    on realtime.messages;

create policy safar_vehicle_location_broadcast_insert
on realtime.messages
for insert
to authenticated
with check (
    extension = 'broadcast'
    and topic like 'safar:vehicle-location:route:%'
);
