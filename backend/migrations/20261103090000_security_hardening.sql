begin;

-- The base table contains exact coordinates even after a vehicle goes offline.
-- Clients read scoped snapshots through RPCs below instead of selecting this table.
revoke all on table public.vehicle_current_state from public, anon, authenticated;
drop policy if exists vehicle_current_state_read_authenticated on public.vehicle_current_state;

-- Keep the current read surface compatible with older app builds, but make it
-- user-scoped and never expose stale/offline coordinates through this view.
create or replace view public.vehicle_current_locations
with (security_invoker = false, security_barrier = true)
as
select
    v.id,
    v.identifier,
    v.route_id,
    coalesce(
        v.active and s.route_id = v.route_id
            and s.recorded_at >= pg_catalog.now() - interval '1 minute',
        false
    ) as active,
    case when v.active and s.route_id = v.route_id
                  and s.recorded_at >= pg_catalog.now() - interval '1 minute'
         then s.latitude else null end as latitude,
    case when v.active and s.route_id = v.route_id
                  and s.recorded_at >= pg_catalog.now() - interval '1 minute'
         then s.longitude else null end as longitude,
    case when v.active and s.route_id = v.route_id
                  and s.recorded_at >= pg_catalog.now() - interval '1 minute'
         then s.recorded_at else null end as recorded_at,
    case when v.active and s.route_id = v.route_id
                  and s.recorded_at >= pg_catalog.now() - interval '1 minute'
         then s.heading_degrees else null end as heading_degrees
from public.vehicles as v
left join public.vehicle_current_state as s on s.vehicle_id = v.id
where exists (
    select 1
    from public.users as u
    where u.id = (select auth.uid())
      and (
          u.role = 'PASSENGER'
          or exists (
              select 1 from public.drivers as d
              where d.user_id = u.id and d.verified and d.route_id = v.route_id
          )
          or exists (
              select 1 from public.drivers as d
              where d.user_id = u.id and not d.verified and d.vehicle_id = v.id
          )
      )
);
grant select on public.vehicle_current_locations to authenticated;

-- Pending applications do not reserve a fleet vehicle identifier. Only a
-- trusted backend approval operation creates the vehicles/drivers assignment.
create table if not exists public.driver_applications (
    user_id uuid primary key references public.users(id) on delete cascade,
    vehicle_identifier text not null check (length(pg_catalog.btrim(vehicle_identifier)) > 0),
    route_id uuid not null references public.routes(id) on delete restrict,
    created_at timestamptz not null default pg_catalog.now(),
    updated_at timestamptz not null default pg_catalog.now()
);
alter table public.driver_applications enable row level security;
revoke all on table public.driver_applications from public, anon, authenticated;

-- Existing driver accounts were marked verified by the old public registration
-- RPC, so that flag cannot be treated as proof of approval. Move those accounts
-- to the pending queue and hide their old live locations until reviewed.
insert into public.driver_applications(user_id, vehicle_identifier, route_id)
select d.user_id, v.identifier, coalesce(d.route_id, v.route_id)
from public.drivers as d
join public.vehicles as v on v.id = d.vehicle_id
where coalesce(d.route_id, v.route_id) is not null
on conflict (user_id) do nothing;

update public.drivers set verified = false, updated_at = pg_catalog.now() where verified;
update public.vehicles as v set active = false, updated_at = pg_catalog.now()
where exists (select 1 from public.drivers as d where d.vehicle_id = v.id and not d.verified);

-- A passenger may browse any route. A verified driver may browse only their
-- assigned route. Pending drivers can still resolve their own vehicle profile.
create or replace function public.get_vehicles_for_route(p_route_id uuid)
returns table (
    id uuid,
    identifier text,
    route_id uuid,
    active boolean,
    latitude double precision,
    longitude double precision,
    recorded_at timestamptz,
    heading_degrees double precision
)
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_role public.user_role;
    v_driver public.drivers;
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;

    select u.role into v_role
    from public.users as u
    where u.id = v_uid;
    if not found then
        raise exception 'Registered profile is required';
    end if;

    if not exists (select 1 from public.routes as r where r.id = p_route_id) then
        raise exception 'Route not found';
    end if;

    if v_role = 'DRIVER' then
        select d.* into v_driver
        from public.drivers as d
        where d.user_id = v_uid;
        if not found or not v_driver.verified or v_driver.route_id is distinct from p_route_id then
            raise exception 'Verified driver assigned to this route is required';
        end if;
    elsif v_role <> 'PASSENGER' then
        raise exception 'Unsupported profile role';
    end if;

    return query
    select v.id,
           v.identifier,
           v.route_id,
           true,
           s.latitude,
           s.longitude,
           s.recorded_at,
           s.heading_degrees
    from public.vehicles as v
    join public.vehicle_current_state as s on s.vehicle_id = v.id
    where v.route_id = p_route_id
      and v.active
      and s.route_id = v.route_id
      and s.recorded_at >= pg_catalog.now() - interval '1 minute';
end;
$function$;

create or replace function public.get_all_vehicles()
returns table (
    id uuid,
    identifier text,
    route_id uuid,
    active boolean,
    latitude double precision,
    longitude double precision,
    recorded_at timestamptz,
    heading_degrees double precision
)
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_role public.user_role;
    v_driver public.drivers;
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;

    select u.role into v_role
    from public.users as u
    where u.id = v_uid;
    if not found then
        raise exception 'Registered profile is required';
    end if;

    if v_role = 'DRIVER' then
        select d.* into v_driver from public.drivers as d where d.user_id = v_uid;
        if not found then
            return;
        end if;
        if not v_driver.verified then
            return query
            select v.id, v.identifier, v.route_id, false,
                   null::double precision, null::double precision,
                   null::timestamptz, null::double precision
            from public.vehicles as v
            where v.id = v_driver.vehicle_id;
            return;
        end if;
    elsif v_role <> 'PASSENGER' then
        raise exception 'Unsupported profile role';
    end if;

    return query
    select v.id,
           v.identifier,
           v.route_id,
           coalesce(v.active and s.route_id = v.route_id
               and s.recorded_at >= pg_catalog.now() - interval '1 minute', false) as active,
           case when v.active and s.route_id = v.route_id
                     and s.recorded_at >= pg_catalog.now() - interval '1 minute'
                then s.latitude else null end,
           case when v.active and s.route_id = v.route_id
                     and s.recorded_at >= pg_catalog.now() - interval '1 minute'
                then s.longitude else null end,
           case when v.active and s.route_id = v.route_id
                     and s.recorded_at >= pg_catalog.now() - interval '1 minute'
                then s.recorded_at else null end,
           case when v.active and s.route_id = v.route_id
                     and s.recorded_at >= pg_catalog.now() - interval '1 minute'
                then s.heading_degrees else null end
    from public.vehicles as v
    left join public.vehicle_current_state as s on s.vehicle_id = v.id
    where (v_role = 'PASSENGER')
       or (v_role = 'DRIVER' and v.route_id = v_driver.route_id);
end;
$function$;

revoke all on function public.get_vehicles_for_route(uuid) from public, anon;
revoke all on function public.get_all_vehicles() from public, anon;
grant execute on function public.get_vehicles_for_route(uuid) to authenticated;
grant execute on function public.get_all_vehicles() to authenticated;

-- Passenger positions are written only through an RPC that derives the sender
-- from auth.uid(). Database triggers broadcast these rows to a driver-only topic.
create table if not exists public.passenger_current_state (
    passenger_id uuid primary key references public.users(id) on delete cascade,
    route_id uuid not null references public.routes(id) on delete cascade,
    latitude double precision not null check (latitude between -90 and 90),
    longitude double precision not null check (longitude between -180 and 180),
    recorded_at timestamptz not null,
    location_fix_at timestamptz not null,
    updated_at timestamptz not null default pg_catalog.now()
);

create index if not exists passenger_current_state_route_updated_idx
    on public.passenger_current_state (route_id, updated_at desc);
alter table public.passenger_current_state enable row level security;
revoke all on table public.passenger_current_state from public, anon, authenticated;

create or replace function public.broadcast_passenger_location()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    if tg_op = 'DELETE' then
        perform realtime.send(
            pg_catalog.jsonb_build_object('passenger_id', old.passenger_id, 'route_id', old.route_id),
            'passenger_location_removed',
            'safar:passenger-location:route:' || old.route_id::text,
            true
        );
        return null;
    end if;

    if tg_op = 'UPDATE' and old.route_id is distinct from new.route_id then
        perform realtime.send(
            pg_catalog.jsonb_build_object('passenger_id', old.passenger_id, 'route_id', old.route_id),
            'passenger_location_removed',
            'safar:passenger-location:route:' || old.route_id::text,
            true
        );
    end if;

    perform realtime.send(
        pg_catalog.jsonb_build_object(
            'passenger_id', new.passenger_id,
            'route_id', new.route_id,
            'latitude', new.latitude,
            'longitude', new.longitude,
            'recorded_at', new.recorded_at
        ),
        'passenger_location',
        'safar:passenger-location:route:' || new.route_id::text,
        true
    );
    return null;
end;
$function$;
revoke all on function public.broadcast_passenger_location() from public, anon, authenticated;

drop trigger if exists passenger_current_state_broadcast on public.passenger_current_state;
create trigger passenger_current_state_broadcast
after insert or update or delete on public.passenger_current_state
for each row execute function public.broadcast_passenger_location();

create or replace function public.publish_passenger_location(
    p_route_id uuid,
    p_latitude double precision,
    p_longitude double precision,
    p_recorded_at timestamptz
)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_now timestamptz := pg_catalog.clock_timestamp();
begin
    if v_uid is null then raise exception 'Authentication is required'; end if;
    if not exists (
        select 1 from public.users as u where u.id = v_uid and u.role = 'PASSENGER'
    ) then
        raise exception 'Registered passenger profile is required';
    end if;
    if not exists (select 1 from public.routes as r where r.id = p_route_id) then
        raise exception 'Route not found';
    end if;
    if p_latitude is null or p_longitude is null
       or p_latitude not between -90 and 90
       or p_longitude not between -180 and 180 then
        raise exception 'Invalid coordinates';
    end if;
    if p_recorded_at is null
       or p_recorded_at < v_now - interval '2 minutes'
       or p_recorded_at > v_now + interval '30 seconds' then
        raise exception 'Location fix is stale or device clock is out of sync';
    end if;

    insert into public.passenger_current_state(
        passenger_id, route_id, latitude, longitude, recorded_at, location_fix_at, updated_at
    ) values (v_uid, p_route_id, p_latitude, p_longitude, v_now, p_recorded_at, v_now)
    on conflict (passenger_id) do update set
        route_id = excluded.route_id,
        latitude = excluded.latitude,
        longitude = excluded.longitude,
        recorded_at = excluded.recorded_at,
        location_fix_at = excluded.location_fix_at,
        updated_at = excluded.updated_at
    where excluded.location_fix_at > public.passenger_current_state.location_fix_at;
end;
$function$;

create or replace function public.remove_passenger_location(p_route_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then raise exception 'Authentication is required'; end if;
    if not exists (
        select 1 from public.users as u where u.id = v_uid and u.role = 'PASSENGER'
    ) then
        raise exception 'Registered passenger profile is required';
    end if;
    delete from public.passenger_current_state
    where passenger_id = v_uid and route_id = p_route_id;
end;
$function$;

revoke all on function public.publish_passenger_location(uuid, double precision, double precision, timestamptz)
    from public, anon;
revoke all on function public.remove_passenger_location(uuid) from public, anon;
grant execute on function public.publish_passenger_location(uuid, double precision, double precision, timestamptz)
    to authenticated;
grant execute on function public.remove_passenger_location(uuid) to authenticated;

-- Only registered passengers and drivers assigned to a route may receive vehicle
-- updates. Clients cannot insert into this topic; vehicle events come from SQL triggers.
drop policy if exists safar_vehicle_location_broadcast_read on realtime.messages;
drop policy if exists safar_vehicle_location_broadcast_insert on realtime.messages;
drop policy if exists safar_vehicle_location_broadcast_read_scoped on realtime.messages;
drop policy if exists safar_vehicle_location_broadcast_insert_scoped on realtime.messages;
drop policy if exists safar_location_broadcast_scope_guard on realtime.messages;
drop policy if exists safar_location_broadcast_client_insert_guard on realtime.messages;
create policy safar_vehicle_location_broadcast_read_scoped
on realtime.messages for select to authenticated
using (
    extension = 'broadcast'
    and exists (
        select 1
        from public.routes as r
        join public.users as u on u.id = (select auth.uid())
        where realtime.messages.topic = 'safar:vehicle-location:route:' || r.id::text
          and (
              u.role = 'PASSENGER'
              or exists (
                  select 1 from public.drivers as d
                  where d.user_id = u.id and d.verified and d.route_id = r.id
              )
          )
    )
);

-- Passenger events are on a separate route topic readable only by verified
-- drivers assigned to that route. There is intentionally no client insert policy.
drop policy if exists safar_passenger_location_broadcast_read on realtime.messages;
drop policy if exists safar_passenger_location_broadcast_insert on realtime.messages;
create policy safar_passenger_location_broadcast_read
on realtime.messages for select to authenticated
using (
    extension = 'broadcast'
    and exists (
        select 1 from public.drivers as d
        join public.users as u on u.id = d.user_id
        join public.routes as r on r.id = d.route_id
        where d.user_id = (select auth.uid())
          and d.verified
          and u.role = 'DRIVER'
          and realtime.messages.topic = 'safar:passenger-location:route:' || r.id::text
    )
);

-- Restrictive guards keep any pre-existing permissive dashboard policy from
-- widening these topics. Database-triggered realtime.send runs as its owner;
-- client-authenticated inserts to either location topic remain denied.
create policy safar_location_broadcast_scope_guard
on realtime.messages as restrictive for select to public
using (
    extension = 'broadcast'
    and (
        exists (
            select 1
            from public.routes as r
            join public.users as u on u.id = (select auth.uid())
            where realtime.messages.topic = 'safar:vehicle-location:route:' || r.id::text
              and (
                  u.role = 'PASSENGER'
                  or exists (
                      select 1 from public.drivers as d
                      where d.user_id = u.id and d.verified and d.route_id = r.id
                  )
              )
        )
        or exists (
            select 1 from public.drivers as d
            join public.users as u on u.id = d.user_id
            join public.routes as r on r.id = d.route_id
            where d.user_id = (select auth.uid())
              and d.verified
              and u.role = 'DRIVER'
              and realtime.messages.topic = 'safar:passenger-location:route:' || r.id::text
        )
    )
);

create policy safar_location_broadcast_client_insert_guard
on realtime.messages as restrictive for insert to public
with check (
    extension <> 'broadcast'
    or (
        realtime.messages.topic not like 'safar:vehicle-location:route:%'
        and realtime.messages.topic not like 'safar:passenger-location:route:%'
    )
);

-- Registration records an application only. It never creates/reserves a vehicle
-- row; repeated requests for the same identity/details are idempotent.
drop function if exists public.register_driver(text, text, text);
drop function if exists public.register_driver(text, text, text, uuid);
create function public.register_driver(
    p_name text,
    p_phone text,
    p_vehicle_identifier text,
    p_route_id uuid
)
returns table (
    user_id uuid,
    route_id uuid,
    vehicle_identifier text,
    status text
)
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_name text := pg_catalog.btrim(p_name);
    v_phone text := pg_catalog.btrim(p_phone);
    v_identifier text := pg_catalog.btrim(p_vehicle_identifier);
    v_existing_driver public.drivers;
    v_vehicle public.vehicles;
    v_application public.driver_applications;
    v_user_role public.user_role;
begin
    if v_uid is null then raise exception 'Authentication is required'; end if;
    if v_name is null or v_name = '' then raise exception 'Name is required'; end if;
    if v_phone is null or v_phone = '' then raise exception 'Phone is required'; end if;
    if v_identifier is null or v_identifier = '' then raise exception 'Vehicle identifier is required'; end if;
    if not exists (select 1 from public.routes as r where r.id = p_route_id) then
        raise exception 'Route not found';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_uid::text, 1));
    select u.role into v_user_role from public.users as u where u.id = v_uid for update;
    if found and v_user_role <> 'DRIVER' then
        raise exception 'User role is already PASSENGER';
    end if;

    select d.* into v_existing_driver from public.drivers as d where d.user_id = v_uid for update;
    if found then
        select v.* into v_vehicle from public.vehicles as v where v.id = v_existing_driver.vehicle_id;
        if pg_catalog.lower(v_vehicle.identifier) <> pg_catalog.lower(v_identifier)
           or (v_existing_driver.route_id is not null
               and v_existing_driver.route_id is distinct from p_route_id) then
            raise exception 'Driver profile already exists with different vehicle or route';
        end if;
        update public.users as u
        set name = v_name, phone = v_phone, updated_at = pg_catalog.now()
        where u.id = v_uid;
        if not v_existing_driver.verified then
            insert into public.driver_applications(user_id, vehicle_identifier, route_id)
            values (v_uid, v_vehicle.identifier, p_route_id)
            on conflict (user_id) do nothing;
            select a.* into v_application
            from public.driver_applications as a
            where a.user_id = v_uid;
            if pg_catalog.lower(v_application.vehicle_identifier) <> pg_catalog.lower(v_identifier)
               or v_application.route_id is distinct from p_route_id then
                raise exception 'Driver application already exists with different vehicle or route';
            end if;
        end if;
        return query
        select v_uid, coalesce(v_existing_driver.route_id, p_route_id), v_vehicle.identifier,
               case when v_existing_driver.verified then 'APPROVED' else 'PENDING' end;
        return;
    end if;

    select a.* into v_application
    from public.driver_applications as a
    where a.user_id = v_uid
    for update;
    if found then
        if pg_catalog.lower(v_application.vehicle_identifier) <> pg_catalog.lower(v_identifier)
           or v_application.route_id is distinct from p_route_id then
            raise exception 'Driver application already exists with different vehicle or route';
        end if;
        update public.users as u
        set name = v_name, phone = v_phone, updated_at = pg_catalog.now()
        where u.id = v_uid;
        return query select v_uid, v_application.route_id, v_application.vehicle_identifier, 'PENDING';
        return;
    end if;

    insert into public.users(id, name, phone, role)
    values (v_uid, v_name, v_phone, 'DRIVER')
    on conflict (id) do update set name = excluded.name, phone = excluded.phone, updated_at = pg_catalog.now();

    insert into public.driver_applications(user_id, vehicle_identifier, route_id)
    values (v_uid, v_identifier, p_route_id);

    return query select v_uid, p_route_id, v_identifier, 'PENDING';
end;
$function$;

revoke all on function public.register_driver(text, text, text, uuid) from public, anon;
grant execute on function public.register_driver(text, text, text, uuid) to authenticated;

create or replace function public.get_driver_application()
returns table (
    user_id uuid,
    route_id uuid,
    vehicle_identifier text,
    status text
)
language sql
stable
security definer
set search_path = ''
as $function$
    select a.user_id, a.route_id, a.vehicle_identifier, 'PENDING'::text
    from public.driver_applications as a
    where a.user_id = (select auth.uid())
$function$;
revoke all on function public.get_driver_application() from public, anon;
grant execute on function public.get_driver_application() to authenticated;

-- This approval entry point is available only to the trusted backend role.
-- Do not expose a client RPC that can set drivers.verified.
create or replace function public.approve_driver_application(p_user_id uuid)
returns public.drivers
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_application public.driver_applications;
    v_driver public.drivers;
    v_vehicle public.vehicles;
begin
    select a.* into v_application
    from public.driver_applications as a
    where a.user_id = p_user_id
    for update;
    if not found then raise exception 'Driver application not found'; end if;

    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(pg_catalog.lower(v_application.vehicle_identifier), 2)
    );

    select d.* into v_driver
    from public.drivers as d
    where d.user_id = p_user_id
    for update;

    if found then
        select v.* into v_vehicle from public.vehicles as v where v.id = v_driver.vehicle_id for update;
        if pg_catalog.lower(v_vehicle.identifier) <> pg_catalog.lower(v_application.vehicle_identifier)
           or (v_driver.route_id is not null and v_driver.route_id is distinct from v_application.route_id) then
            raise exception 'Existing driver assignment does not match the application';
        end if;
        update public.drivers as d
        set verified = true, route_id = v_application.route_id, updated_at = pg_catalog.now()
        where d.user_id = p_user_id
        returning * into v_driver;
        update public.vehicles as v
        set active = false, route_id = v_application.route_id, updated_at = pg_catalog.now()
        where v.id = v_driver.vehicle_id;
    else
        insert into public.vehicles(identifier, route_id, active)
        values (v_application.vehicle_identifier, v_application.route_id, false)
        on conflict (identifier) do nothing;

        select v.* into v_vehicle
        from public.vehicles as v
        where pg_catalog.lower(v.identifier) = pg_catalog.lower(v_application.vehicle_identifier)
        order by v.created_at, v.id
        limit 1
        for update;
        if not found then raise exception 'Vehicle could not be assigned'; end if;
        if exists (select 1 from public.drivers as d where d.vehicle_id = v_vehicle.id) then
            raise exception 'Vehicle is already assigned to another driver';
        end if;

        update public.vehicles as v
        set route_id = v_application.route_id, active = false, updated_at = pg_catalog.now()
        where v.id = v_vehicle.id;
        insert into public.drivers(user_id, vehicle_id, route_id, verified)
        values (p_user_id, v_vehicle.id, v_application.route_id, true)
        returning * into v_driver;
    end if;

    delete from public.driver_applications as a where a.user_id = p_user_id;
    return v_driver;
end;
$function$;
revoke all on function public.approve_driver_application(uuid) from public, anon, authenticated;
grant execute on function public.approve_driver_application(uuid) to service_role;

create or replace function public.assign_driver_route(p_route_id uuid)
returns public.drivers
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_driver public.drivers;
begin
    if v_uid is null then raise exception 'Authentication is required'; end if;
    if not exists (select 1 from public.routes as r where r.id = p_route_id) then
        raise exception 'Route not found';
    end if;
    select d.* into v_driver from public.drivers as d where d.user_id = v_uid for update;
    if not found or not v_driver.verified then raise exception 'Verified driver profile is required'; end if;
    update public.drivers set route_id = p_route_id, updated_at = pg_catalog.now()
    where user_id = v_uid returning * into v_driver;
    update public.vehicles set route_id = p_route_id, updated_at = pg_catalog.now()
    where id = v_driver.vehicle_id;
    return v_driver;
end;
$function$;
revoke all on function public.assign_driver_route(uuid) from public, anon;
grant execute on function public.assign_driver_route(uuid) to authenticated;

-- Existing sessions remain owned by the user, but every state transition also
-- requires a currently verified driver. Keep the distance-anchor reset behavior.
create or replace function public.pause_work_session(p_session_id uuid)
returns public.work_sessions
language plpgsql security definer set search_path = ''
as $function$
declare v_uid uuid := auth.uid(); v_driver public.drivers; v_session public.work_sessions;
begin
    if v_uid is null then raise exception 'Authentication is required'; end if;
    select d.* into v_driver from public.drivers as d where d.user_id = v_uid for update;
    if not found or not v_driver.verified then raise exception 'Verified driver profile is required'; end if;
    select s.* into v_session from public.work_sessions as s
    where s.id = p_session_id and s.driver_id = v_uid for update;
    if not found then raise exception 'Work session not found'; end if;
    if v_session.status <> 'ACTIVE' then raise exception 'Work session is not active'; end if;
    update public.work_sessions set status = 'PAUSED', paused_at = pg_catalog.now(),
        distance_anchor_latitude = null, distance_anchor_longitude = null,
        distance_anchor_at = null, updated_at = pg_catalog.now()
    where id = p_session_id returning * into v_session;
    update public.vehicles set active = false, updated_at = pg_catalog.now() where id = v_session.vehicle_id;
    return v_session;
end;
$function$;

create or replace function public.resume_work_session(p_session_id uuid)
returns public.work_sessions
language plpgsql security definer set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid(); v_driver public.drivers; v_session public.work_sessions;
    v_added_pause bigint;
begin
    if v_uid is null then raise exception 'Authentication is required'; end if;
    select d.* into v_driver from public.drivers as d where d.user_id = v_uid for update;
    if not found or not v_driver.verified then raise exception 'Verified driver profile is required'; end if;
    select s.* into v_session from public.work_sessions as s
    where s.id = p_session_id and s.driver_id = v_uid for update;
    if not found then raise exception 'Work session not found'; end if;
    if v_session.status <> 'PAUSED' or v_session.paused_at is null then
        raise exception 'Work session is not paused';
    end if;
    v_added_pause := greatest(0,
        extract(epoch from (pg_catalog.now() - v_session.paused_at))::bigint);
    update public.work_sessions set status = 'ACTIVE',
        total_paused_seconds = total_paused_seconds + v_added_pause,
        paused_at = null, distance_anchor_latitude = null,
        distance_anchor_longitude = null, distance_anchor_at = null,
        updated_at = pg_catalog.now()
    where id = p_session_id returning * into v_session;
    update public.vehicles set active = true, updated_at = pg_catalog.now() where id = v_session.vehicle_id;
    return v_session;
end;
$function$;

create or replace function public.finish_work_session(p_session_id uuid)
returns public.work_sessions
language plpgsql security definer set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid(); v_driver public.drivers; v_session public.work_sessions;
    v_paused_total bigint; v_duration bigint;
begin
    if v_uid is null then raise exception 'Authentication is required'; end if;
    select d.* into v_driver from public.drivers as d where d.user_id = v_uid for update;
    if not found or not v_driver.verified then raise exception 'Verified driver profile is required'; end if;
    select s.* into v_session from public.work_sessions as s
    where s.id = p_session_id and s.driver_id = v_uid for update;
    if not found then raise exception 'Work session not found'; end if;
    if v_session.status = 'FINISHED' then return v_session; end if;
    v_paused_total := v_session.total_paused_seconds;
    if v_session.status = 'PAUSED' and v_session.paused_at is not null then
        v_paused_total := v_paused_total + greatest(0,
            extract(epoch from (pg_catalog.now() - v_session.paused_at))::bigint);
    end if;
    v_duration := greatest(0,
        extract(epoch from (pg_catalog.now() - v_session.started_at))::bigint - v_paused_total);
    update public.work_sessions set status = 'FINISHED', finished_at = pg_catalog.now(),
        paused_at = null, total_paused_seconds = v_paused_total,
        duration_seconds = v_duration, updated_at = pg_catalog.now()
    where id = p_session_id returning * into v_session;
    update public.vehicles set active = false, updated_at = pg_catalog.now() where id = v_session.vehicle_id;
    return v_session;
end;
$function$;

revoke all on function public.pause_work_session(uuid) from public, anon;
revoke all on function public.resume_work_session(uuid) from public, anon;
revoke all on function public.finish_work_session(uuid) from public, anon;
grant execute on function public.pause_work_session(uuid) to authenticated;
grant execute on function public.resume_work_session(uuid) to authenticated;
grant execute on function public.finish_work_session(uuid) to authenticated;

commit;
