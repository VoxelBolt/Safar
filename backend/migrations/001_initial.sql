-- Safar Stage 03 — Backend & API
-- Supabase/PostgreSQL schema for MVP.
-- Run after enabling Anonymous Sign-Ins in Supabase Auth.
-- No service_role key is required by the Android application.

create extension if not exists pgcrypto;

do $$ begin
    create type public.user_role as enum ('PASSENGER', 'DRIVER');
exception
    when duplicate_object then null;
end $$;

do $$ begin
    create type public.work_session_status as enum ('ACTIVE', 'PAUSED', 'FINISHED');
exception
    when duplicate_object then null;
end $$;

create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

create table if not exists public.routes (
    id uuid primary key default gen_random_uuid(),
    number text not null unique,
    path jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint routes_number_not_blank check (length(trim(number)) > 0),
    constraint routes_path_is_array check (jsonb_typeof(path) = 'array')
);

create table if not exists public.users (
    id uuid primary key references auth.users(id) on delete cascade,
    name text not null,
    phone text not null,
    role public.user_role not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint users_name_not_blank check (length(trim(name)) > 0),
    constraint users_phone_not_blank check (length(trim(phone)) > 0)
);

create unique index if not exists users_phone_unique
    on public.users (phone);

create table if not exists public.vehicles (
    id uuid primary key default gen_random_uuid(),
    identifier text not null unique,
    route_id uuid references public.routes(id) on delete set null,
    active boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint vehicles_identifier_not_blank check (length(trim(identifier)) > 0)
);

create index if not exists vehicles_route_idx
    on public.vehicles (route_id);

create index if not exists vehicles_active_route_idx
    on public.vehicles (route_id, active);

create table if not exists public.drivers (
    user_id uuid primary key references public.users(id) on delete cascade,
    vehicle_id uuid not null unique references public.vehicles(id) on delete restrict,
    route_id uuid references public.routes(id) on delete set null,
    verified boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists drivers_route_idx
    on public.drivers (route_id);

create table if not exists public.work_sessions (
    id uuid primary key default gen_random_uuid(),
    driver_id uuid not null references public.drivers(user_id) on delete cascade,
    vehicle_id uuid not null references public.vehicles(id) on delete restrict,
    started_at timestamptz not null default now(),
    paused_at timestamptz,
    finished_at timestamptz,
    total_paused_seconds bigint not null default 0,
    duration_seconds bigint not null default 0,
    distance_meters double precision not null default 0,
    status public.work_session_status not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint work_sessions_time_order check (
        finished_at is null or finished_at >= started_at
    ),
    constraint work_sessions_non_negative check (
        total_paused_seconds >= 0
        and duration_seconds >= 0
        and distance_meters >= 0
    )
);

create index if not exists work_sessions_driver_status_idx
    on public.work_sessions (driver_id, status);

create index if not exists work_sessions_driver_started_idx
    on public.work_sessions (driver_id, started_at desc);

create unique index if not exists one_open_work_session_per_driver
    on public.work_sessions (driver_id)
    where status in ('ACTIVE', 'PAUSED');

create table if not exists public.vehicle_locations (
    id bigint generated always as identity primary key,
    vehicle_id uuid not null references public.vehicles(id) on delete cascade,
    latitude double precision not null,
    longitude double precision not null,
    recorded_at timestamptz not null default now(),
    constraint vehicle_locations_latitude_range check (latitude between -90 and 90),
    constraint vehicle_locations_longitude_range check (longitude between -180 and 180)
);

create index if not exists vehicle_locations_vehicle_time_idx
    on public.vehicle_locations (vehicle_id, recorded_at desc);

create table if not exists public.favorite_vehicles (
    user_id uuid not null references public.users(id) on delete cascade,
    vehicle_id uuid not null references public.vehicles(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, vehicle_id)
);

create index if not exists favorite_vehicles_vehicle_idx
    on public.favorite_vehicles (vehicle_id);

create or replace view public.vehicle_current_locations
with (security_invoker = true)
as
select
    v.id,
    v.identifier,
    v.route_id,
    (v.active and loc.recorded_at >= now() - interval '2 minutes') as active,
    loc.latitude,
    loc.longitude,
    loc.recorded_at
from public.vehicles v
left join lateral (
    select
        l.latitude,
        l.longitude,
        l.recorded_at
    from public.vehicle_locations l
    where l.vehicle_id = v.id
    order by l.recorded_at desc
    limit 1
) loc on true;

drop trigger if exists routes_set_updated_at on public.routes;
create trigger routes_set_updated_at
before update on public.routes
for each row execute function public.set_updated_at();

drop trigger if exists users_set_updated_at on public.users;
create trigger users_set_updated_at
before update on public.users
for each row execute function public.set_updated_at();

drop trigger if exists vehicles_set_updated_at on public.vehicles;
create trigger vehicles_set_updated_at
before update on public.vehicles
for each row execute function public.set_updated_at();

drop trigger if exists drivers_set_updated_at on public.drivers;
create trigger drivers_set_updated_at
before update on public.drivers
for each row execute function public.set_updated_at();

drop trigger if exists work_sessions_set_updated_at on public.work_sessions;
create trigger work_sessions_set_updated_at
before update on public.work_sessions
for each row execute function public.set_updated_at();

-- Public read access is intentionally limited to authenticated users.
grant select on public.routes to authenticated;
grant select on public.vehicles to authenticated;
grant select on public.vehicle_current_locations to authenticated;
grant select on public.users to authenticated;
grant update (name, phone) on public.users to authenticated;
grant select on public.drivers to authenticated;
grant select on public.work_sessions to authenticated;
grant select on public.vehicle_locations to authenticated;
grant select, insert, delete on public.favorite_vehicles to authenticated;

-- Work sessions and locations are mutated through controlled RPC functions.
revoke insert, update, delete on public.work_sessions from authenticated;
revoke insert, update, delete on public.vehicle_locations from authenticated;
revoke insert, update, delete on public.drivers from authenticated;
revoke insert, update, delete on public.vehicles from authenticated;
revoke insert, update, delete on public.routes from authenticated;
revoke insert on public.users from authenticated;

alter table public.routes enable row level security;
alter table public.users enable row level security;
alter table public.vehicles enable row level security;
alter table public.drivers enable row level security;
alter table public.work_sessions enable row level security;
alter table public.vehicle_locations enable row level security;
alter table public.favorite_vehicles enable row level security;

drop policy if exists routes_read_authenticated on public.routes;
create policy routes_read_authenticated
on public.routes for select
to authenticated
using (true);

drop policy if exists users_read_own on public.users;
create policy users_read_own
on public.users for select
to authenticated
using (id = (select auth.uid()));

drop policy if exists users_update_own on public.users;
create policy users_update_own
on public.users for update
to authenticated
using (id = (select auth.uid()))
with check (id = (select auth.uid()));

drop policy if exists vehicles_read_active_or_own on public.vehicles;
create policy vehicles_read_active_or_own
on public.vehicles for select
to authenticated
using (
    active = true
    or exists (
        select 1
        from public.drivers d
        where d.user_id = (select auth.uid())
          and d.vehicle_id = vehicles.id
    )
);

drop policy if exists drivers_read_own on public.drivers;
create policy drivers_read_own
on public.drivers for select
to authenticated
using (user_id = (select auth.uid()));

drop policy if exists work_sessions_read_own on public.work_sessions;
create policy work_sessions_read_own
on public.work_sessions for select
to authenticated
using (driver_id = (select auth.uid()));

drop policy if exists vehicle_locations_read_active_or_own on public.vehicle_locations;
create policy vehicle_locations_read_active_or_own
on public.vehicle_locations for select
to authenticated
using (
    exists (
        select 1
        from public.vehicles v
        where v.id = vehicle_locations.vehicle_id
          and v.active = true
    )
    or exists (
        select 1
        from public.drivers d
        where d.user_id = (select auth.uid())
          and d.vehicle_id = vehicle_locations.vehicle_id
    )
);

drop policy if exists favorites_read_own on public.favorite_vehicles;
create policy favorites_read_own
on public.favorite_vehicles for select
to authenticated
using (user_id = (select auth.uid()));

drop policy if exists favorites_insert_own on public.favorite_vehicles;
create policy favorites_insert_own
on public.favorite_vehicles for insert
to authenticated
with check (user_id = (select auth.uid()));

drop policy if exists favorites_delete_own on public.favorite_vehicles;
create policy favorites_delete_own
on public.favorite_vehicles for delete
to authenticated
using (user_id = (select auth.uid()));

-- Registration RPCs: anonymous Supabase users are already in the authenticated
-- Postgres role, which gives RLS a stable auth.uid() without SMS/OAuth.
create or replace function public.register_passenger(
    p_name text,
    p_phone text
)
returns public.users
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_user public.users;
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;

    if not exists (select 1 from auth.users where id = v_uid) then
        raise exception 'Authenticated user does not exist';
    end if;

    select * into v_user from public.users where id = v_uid;

    if found and v_user.role <> 'PASSENGER' then
        raise exception 'User role is already DRIVER';
    end if;

    insert into public.users(id, name, phone, role)
    values (v_uid, trim(p_name), trim(p_phone), 'PASSENGER')
    on conflict (id) do update
      set name = excluded.name,
          phone = excluded.phone,
          updated_at = now();

    return (select u from public.users u where u.id = v_uid);
end;
$$;

create or replace function public.register_driver(
    p_name text,
    p_phone text,
    p_vehicle_identifier text
)
returns table (
    id uuid,
    name text,
    phone text,
    role public.user_role,
    vehicle_id uuid,
    route_id uuid,
    verified boolean
)
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
    v_vehicle public.vehicles;
    v_existing_driver public.drivers;
    v_identifier text := trim(p_vehicle_identifier);
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;

    if v_identifier is null or v_identifier = '' then
        raise exception 'Vehicle identifier is required';
    end if;

    insert into public.vehicles(identifier, route_id, active)
    values (v_identifier, null, false)
    on conflict (identifier) do nothing;

    select v.* into v_vehicle
    from public.vehicles v
    where lower(v.identifier) = lower(v_identifier)
    for update;

    if not found then
        raise exception 'Vehicle could not be registered';
    end if;

    select d.* into v_existing_driver
    from public.drivers d
    where d.vehicle_id = v_vehicle.id;

    if found and v_existing_driver.user_id <> v_uid then
        raise exception 'Vehicle is already assigned to another driver';
    end if;

    if exists (
        select 1
        from public.users u
        where u.id = v_uid
          and u.role <> 'DRIVER'
    ) then
        raise exception 'User role is already PASSENGER';
    end if;

    insert into public.users(id, name, phone, role)
    values (v_uid, trim(p_name), trim(p_phone), 'DRIVER')
    on conflict on constraint users_pkey do update
      set name = excluded.name,
          phone = excluded.phone,
          updated_at = now();

    insert into public.drivers(user_id, vehicle_id, route_id, verified)
    values (v_uid, v_vehicle.id, v_vehicle.route_id, true)
    on conflict (user_id) do update
      set vehicle_id = excluded.vehicle_id,
          route_id = excluded.route_id,
          verified = true,
          updated_at = now();

    return query
    select u.id,
           u.name,
           u.phone,
           u.role,
           drv.vehicle_id,
           drv.route_id,
           drv.verified
    from public.users u
    join public.drivers drv on drv.user_id = u.id
    where u.id = v_uid;
end;
$$;

create or replace function public.assign_driver_route(
    p_route_id uuid
)
returns public.drivers
language plpgsql
security definer
set search_path = public, pg_temp
as $$
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

    select * into v_driver
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

    return v_driver;
end;
$$;

create or replace function public.update_current_user(
    p_name text,
    p_phone text
)
returns public.users
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;

    update public.users
    set name = trim(p_name),
        phone = trim(p_phone),
        updated_at = now()
    where id = v_uid;

    if not found then
        raise exception 'Profile not registered';
    end if;

    return (select u from public.users u where u.id = v_uid);
end;
$$;

create or replace function public.add_favorite_vehicle(
    p_vehicle_id uuid
)
returns public.favorite_vehicles
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
    v_favorite public.favorite_vehicles;
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;

    if not exists (select 1 from public.users where id = v_uid) then
        raise exception 'Profile not registered';
    end if;

    if not exists (select 1 from public.vehicles where id = p_vehicle_id) then
        raise exception 'Vehicle not found';
    end if;

    insert into public.favorite_vehicles(user_id, vehicle_id)
    values (v_uid, p_vehicle_id)
    on conflict (user_id, vehicle_id) do update
      set created_at = public.favorite_vehicles.created_at;

    select * into v_favorite
    from public.favorite_vehicles
    where user_id = v_uid and vehicle_id = p_vehicle_id;

    return v_favorite;
end;
$$;

create or replace function public.remove_favorite_vehicle(
    p_vehicle_id uuid
)
returns void
language sql
security definer
set search_path = public, pg_temp
as $$
    delete from public.favorite_vehicles
    where user_id = auth.uid()
      and vehicle_id = p_vehicle_id;
$$;

create or replace function public.get_current_work_session()
returns public.work_sessions
language sql
security definer
set search_path = public, pg_temp
as $$
    select ws
    from public.work_sessions ws
    where ws.driver_id = auth.uid()
      and ws.status in ('ACTIVE', 'PAUSED')
    order by ws.started_at desc
    limit 1;
$$;

create or replace function public.start_work_session()
returns public.work_sessions
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
    v_driver public.drivers;
    v_session public.work_sessions;
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;

    select * into v_driver
    from public.drivers
    where user_id = v_uid
    for update;

    if not found or not v_driver.verified then
        raise exception 'Verified driver profile is required';
    end if;

    if v_driver.route_id is null then
        raise exception 'Driver route is not assigned yet';
    end if;

    select * into v_session
    from public.work_sessions
    where driver_id = v_uid
      and status in ('ACTIVE', 'PAUSED')
    order by started_at desc
    limit 1
    for update;

    if found then
        if v_session.status = 'PAUSED'
           and v_session.paused_at is not null
           and now() - v_session.paused_at > interval '6 hours' then
            update public.work_sessions
            set status = 'FINISHED',
                finished_at = now(),
                duration_seconds = greatest(
                    0,
                    extract(epoch from (
                        coalesce(paused_at, now()) - started_at
                    ))::bigint - total_paused_seconds
                )
            where id = v_session.id;
        else
            return v_session;
        end if;
    end if;

    insert into public.work_sessions(driver_id, vehicle_id, started_at, status)
    values (v_uid, v_driver.vehicle_id, now(), 'ACTIVE')
    returning * into v_session;

    update public.vehicles
    set active = true,
        updated_at = now()
    where id = v_driver.vehicle_id;

    return v_session;
end;
$$;

create or replace function public.pause_work_session(
    p_session_id uuid
)
returns public.work_sessions
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_session public.work_sessions;
begin
    select * into v_session
    from public.work_sessions
    where id = p_session_id
      and driver_id = auth.uid()
    for update;

    if not found then
        raise exception 'Work session not found';
    end if;

    if v_session.status <> 'ACTIVE' then
        raise exception 'Work session is not active';
    end if;

    update public.work_sessions
    set status = 'PAUSED',
        paused_at = now(),
        updated_at = now()
    where id = p_session_id
    returning * into v_session;

    update public.vehicles
    set active = false,
        updated_at = now()
    where id = v_session.vehicle_id;

    return v_session;
end;
$$;

create or replace function public.resume_work_session(
    p_session_id uuid
)
returns public.work_sessions
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_session public.work_sessions;
    v_added_pause bigint;
begin
    select * into v_session
    from public.work_sessions
    where id = p_session_id
      and driver_id = auth.uid()
    for update;

    if not found then
        raise exception 'Work session not found';
    end if;

    if v_session.status <> 'PAUSED' or v_session.paused_at is null then
        raise exception 'Work session is not paused';
    end if;

    v_added_pause := greatest(
        0,
        extract(epoch from (now() - v_session.paused_at))::bigint
    );

    update public.work_sessions
    set status = 'ACTIVE',
        total_paused_seconds = total_paused_seconds + v_added_pause,
        paused_at = null,
        updated_at = now()
    where id = p_session_id
    returning * into v_session;

    update public.vehicles
    set active = true,
        updated_at = now()
    where id = v_session.vehicle_id;

    return v_session;
end;
$$;

create or replace function public.finish_work_session(
    p_session_id uuid
)
returns public.work_sessions
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_session public.work_sessions;
    v_paused_total bigint;
    v_duration bigint;
begin
    select * into v_session
    from public.work_sessions
    where id = p_session_id
      and driver_id = auth.uid()
    for update;

    if not found then
        raise exception 'Work session not found';
    end if;

    if v_session.status = 'FINISHED' then
        return v_session;
    end if;

    v_paused_total := v_session.total_paused_seconds;

    if v_session.status = 'PAUSED' and v_session.paused_at is not null then
        v_paused_total := v_paused_total + greatest(
            0,
            extract(epoch from (now() - v_session.paused_at))::bigint
        );
    end if;

    v_duration := greatest(
        0,
        extract(epoch from (now() - v_session.started_at))::bigint - v_paused_total
    );

    update public.work_sessions
    set status = 'FINISHED',
        finished_at = now(),
        paused_at = null,
        total_paused_seconds = v_paused_total,
        duration_seconds = v_duration,
        updated_at = now()
    where id = p_session_id
    returning * into v_session;

    update public.vehicles
    set active = false,
        updated_at = now()
    where id = v_session.vehicle_id;

    return v_session;
end;
$$;

create or replace function public.submit_vehicle_location(
    p_latitude double precision,
    p_longitude double precision,
    p_recorded_at timestamptz
)
returns public.vehicle_locations
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_driver public.drivers;
    v_active_session public.work_sessions;
    v_location public.vehicle_locations;
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;

    if p_latitude < -90 or p_latitude > 90
       or p_longitude < -180 or p_longitude > 180 then
        raise exception 'Invalid coordinates';
    end if;

    select * into v_driver
    from public.drivers
    where user_id = auth.uid();

    if not found then
        raise exception 'Driver profile not found';
    end if;

    select * into v_active_session
    from public.work_sessions
    where driver_id = auth.uid()
      and status = 'ACTIVE'
    order by started_at desc
    limit 1;

    if not found then
        raise exception 'An active work session is required';
    end if;

    insert into public.vehicle_locations(vehicle_id, latitude, longitude, recorded_at)
    values (v_driver.vehicle_id, p_latitude, p_longitude, p_recorded_at)
    returning * into v_location;

    return v_location;
end;
$$;

revoke all on function public.register_passenger(text, text) from public, anon;
revoke all on function public.register_driver(text, text, text) from public, anon;
revoke all on function public.assign_driver_route(uuid) from public, anon;
revoke all on function public.update_current_user(text, text) from public, anon;
revoke all on function public.add_favorite_vehicle(uuid) from public, anon;
revoke all on function public.remove_favorite_vehicle(uuid) from public, anon;
revoke all on function public.get_current_work_session() from public, anon;
revoke all on function public.start_work_session() from public, anon;
revoke all on function public.pause_work_session(uuid) from public, anon;
revoke all on function public.resume_work_session(uuid) from public, anon;
revoke all on function public.finish_work_session(uuid) from public, anon;
revoke all on function public.submit_vehicle_location(double precision, double precision, timestamptz) from public, anon;

grant execute on function public.register_passenger(text, text) to authenticated;
grant execute on function public.register_driver(text, text, text) to authenticated;
grant execute on function public.assign_driver_route(uuid) to authenticated;
grant execute on function public.update_current_user(text, text) to authenticated;
grant execute on function public.add_favorite_vehicle(uuid) to authenticated;
grant execute on function public.remove_favorite_vehicle(uuid) to authenticated;
grant execute on function public.get_current_work_session() to authenticated;
grant execute on function public.start_work_session() to authenticated;
grant execute on function public.pause_work_session(uuid) to authenticated;
grant execute on function public.resume_work_session(uuid) to authenticated;
grant execute on function public.finish_work_session(uuid) to authenticated;
grant execute on function public.submit_vehicle_location(double precision, double precision, timestamptz) to authenticated;

-- Demo seed matching the existing Stage 02 mock identifiers. These are route records,
-- not active live vehicles; real driver vehicles become active only while working.
insert into public.routes (number, path)
values
    ('15', '[]'::jsonb),
    ('8', '[]'::jsonb),
    ('3', '[]'::jsonb)
on conflict (number) do nothing;

insert into public.vehicles (identifier, route_id, active)
select '1234ТТ01', id, false from public.routes where number = '15'
on conflict (identifier) do nothing;

insert into public.vehicles (identifier, route_id, active)
select '5678ТТ02', id, false from public.routes where number = '8'
on conflict (identifier) do nothing;

insert into public.vehicles (identifier, route_id, active)
select '9012ТТ03', id, false from public.routes where number = '3'
on conflict (identifier) do nothing;

