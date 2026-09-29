-- Stage 08/09: allow a passenger to read a vehicle they explicitly favorited.
-- This keeps Favorites useful even when the vehicle is currently inactive/offline.
drop policy if exists vehicles_read_favorited on public.vehicles;
create policy vehicles_read_favorited
on public.vehicles for select
to authenticated
using (
    exists (
        select 1
        from public.favorite_vehicles f
        where f.user_id = (select auth.uid())
          and f.vehicle_id = vehicles.id
    )
);

-- Existing RLS policies remain in force; this only adds the minimum read path
-- needed to resolve a user's own favorite vehicle after it goes offline.
