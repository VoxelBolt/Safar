-- Registration / login rules for Safar.
--
-- Passenger identity = name + phone. Driver identity = name + phone + vehicle.
--  * Registration with a phone that already exists is rejected (both roles).
--  * Driver registration with a vehicle number that already exists is rejected.
--  * The same name with different numbers is a new user.
--  * Login requires every field to match; the error names the wrong field(s).
--  * A successful driver registration is approved immediately.
--
-- The app uses anonymous Supabase auth sessions, so "login" on a new session
-- re-binds the existing public.users row to the current auth.uid(). To make
-- that possible, every FK that points at users(id) / drivers(user_id) gets
-- ON UPDATE CASCADE.
--
-- Error codes (raised as exception messages, mapped to text in the app):
--   SAFAR_PHONE_TAKEN, SAFAR_VEHICLE_TAKEN, SAFAR_SESSION_HAS_PROFILE,
--   SAFAR_LOGIN_NOT_FOUND, SAFAR_LOGIN_ROLE_MISMATCH,
--   SAFAR_LOGIN_MISMATCH:<comma separated fields: name,phone,vehicle>

-- ---------------------------------------------------------------------------
-- Normalizers
-- ---------------------------------------------------------------------------
create or replace function public.safar_normalize_phone(p text)
returns text
language sql
immutable
set search_path = ''
as $$
    select pg_catalog.regexp_replace(coalesce(p, ''), '[^0-9+]', '', 'g');
$$;

create or replace function public.safar_normalize_name(p text)
returns text
language sql
immutable
set search_path = ''
as $$
    select pg_catalog.lower(pg_catalog.regexp_replace(pg_catalog.btrim(coalesce(p, '')), '\s+', ' ', 'g'));
$$;

create or replace function public.safar_normalize_vehicle(p text)
returns text
language sql
immutable
set search_path = ''
as $$
    select pg_catalog.upper(pg_catalog.regexp_replace(coalesce(p, ''), '[\s\-]', '', 'g'));
$$;

-- Best-effort unique indexes on normalized values. Skipped (with a notice) if
-- legacy data already contains duplicates; the RPCs below enforce the rule
-- under advisory locks anyway.
do $$
begin
    begin
        create unique index if not exists users_phone_normalized_unique
            on public.users (public.safar_normalize_phone(phone));
    exception when unique_violation then
        raise notice 'users_phone_normalized_unique skipped: duplicate phones in legacy data';
    end;
    begin
        create unique index if not exists vehicles_identifier_normalized_unique
            on public.vehicles (public.safar_normalize_vehicle(identifier));
    exception when unique_violation then
        raise notice 'vehicles_identifier_normalized_unique skipped: duplicate vehicles in legacy data';
    end;
end $$;

-- ---------------------------------------------------------------------------
-- ON UPDATE CASCADE for every FK referencing users(id) or drivers(user_id)
-- ---------------------------------------------------------------------------
do $$
declare
    r record;
begin
    for r in
        select c.conname,
               c.conrelid::regclass as tbl,
               pg_catalog.pg_get_constraintdef(c.oid) as def
        from pg_catalog.pg_constraint as c
        where c.contype = 'f'
          and c.confrelid in ('public.users'::regclass, 'public.drivers'::regclass)
          and c.confupdtype <> 'c'
    loop
        execute pg_catalog.format('alter table %s drop constraint %I', r.tbl, r.conname);
        execute pg_catalog.format('alter table %s add constraint %I %s on update cascade', r.tbl, r.conname, r.def);
    end loop;
end $$;

-- ---------------------------------------------------------------------------
-- Passenger registration
-- ---------------------------------------------------------------------------
create or replace function public.register_passenger(
    p_name text,
    p_phone text
)
returns public.users
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_name text := pg_catalog.regexp_replace(pg_catalog.btrim(coalesce(p_name, '')), '\s+', ' ', 'g');
    v_phone text := public.safar_normalize_phone(p_phone);
    v_current public.users;
begin
    if v_uid is null then raise exception 'Authentication is required'; end if;
    if not exists (select 1 from auth.users as au where au.id = v_uid) then
        raise exception 'Authenticated user does not exist';
    end if;
    if v_name = '' then raise exception 'Name is required'; end if;
    if v_phone = '' then raise exception 'Phone is required'; end if;

    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('safar_phone:' || v_phone, 0));

    if exists (
        select 1 from public.users as u
        where public.safar_normalize_phone(u.phone) = v_phone and u.id <> v_uid
    ) then
        raise exception 'SAFAR_PHONE_TAKEN';
    end if;

    select u.* into v_current from public.users as u where u.id = v_uid for update;
    if found then
        if v_current.role <> 'PASSENGER'
           or public.safar_normalize_phone(v_current.phone) <> v_phone then
            -- This anonymous session already belongs to another profile.
            raise exception 'SAFAR_SESSION_HAS_PROFILE';
        end if;
        update public.users as u
        set name = v_name, updated_at = pg_catalog.now()
        where u.id = v_uid;
    else
        insert into public.users(id, name, phone, role)
        values (v_uid, v_name, v_phone, 'PASSENGER');
    end if;

    return (select u from public.users as u where u.id = v_uid);
end;
$function$;

revoke all on function public.register_passenger(text, text) from public, anon;
grant execute on function public.register_passenger(text, text) to authenticated;

-- ---------------------------------------------------------------------------
-- Driver registration (approved immediately)
-- ---------------------------------------------------------------------------
create or replace function public.register_driver(
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
    v_name text := pg_catalog.regexp_replace(pg_catalog.btrim(coalesce(p_name, '')), '\s+', ' ', 'g');
    v_phone text := public.safar_normalize_phone(p_phone);
    v_identifier text := pg_catalog.btrim(coalesce(p_vehicle_identifier, ''));
    v_vehicle_key text := public.safar_normalize_vehicle(p_vehicle_identifier);
    v_current public.users;
    v_driver public.drivers;
    v_vehicle public.vehicles;
begin
    if v_uid is null then raise exception 'Authentication is required'; end if;
    if not exists (select 1 from auth.users as au where au.id = v_uid) then
        raise exception 'Authenticated user does not exist';
    end if;
    if v_name = '' then raise exception 'Name is required'; end if;
    if v_phone = '' then raise exception 'Phone is required'; end if;
    if v_vehicle_key = '' then raise exception 'Vehicle identifier is required'; end if;
    if p_route_id is null or not exists (select 1 from public.routes as r where r.id = p_route_id) then
        raise exception 'Route not found';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('safar_phone:' || v_phone, 0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('safar_vehicle:' || v_vehicle_key, 0));

    if exists (
        select 1 from public.users as u
        where public.safar_normalize_phone(u.phone) = v_phone and u.id <> v_uid
    ) then
        raise exception 'SAFAR_PHONE_TAKEN';
    end if;

    if exists (
        select 1
        from public.vehicles as v
        join public.drivers as d on d.vehicle_id = v.id
        where public.safar_normalize_vehicle(v.identifier) = v_vehicle_key
          and d.user_id <> v_uid
    ) or exists (
        select 1 from public.driver_applications as a
        where public.safar_normalize_vehicle(a.vehicle_identifier) = v_vehicle_key
          and a.user_id <> v_uid
    ) then
        raise exception 'SAFAR_VEHICLE_TAKEN';
    end if;

    select u.* into v_current from public.users as u where u.id = v_uid for update;
    if found and (v_current.role <> 'DRIVER'
                  or public.safar_normalize_phone(v_current.phone) <> v_phone) then
        raise exception 'SAFAR_SESSION_HAS_PROFILE';
    end if;

    select d.* into v_driver from public.drivers as d where d.user_id = v_uid for update;
    if found then
        select v.* into v_vehicle from public.vehicles as v where v.id = v_driver.vehicle_id;
        if public.safar_normalize_vehicle(v_vehicle.identifier) <> v_vehicle_key then
            raise exception 'SAFAR_SESSION_HAS_PROFILE';
        end if;
        update public.users as u set name = v_name, updated_at = pg_catalog.now() where u.id = v_uid;
        update public.drivers as d
        set verified = true, route_id = p_route_id, updated_at = pg_catalog.now()
        where d.user_id = v_uid;
        update public.vehicles as v set route_id = p_route_id, updated_at = pg_catalog.now()
        where v.id = v_vehicle.id;
        delete from public.driver_applications as a where a.user_id = v_uid;
        return query select v_uid, p_route_id, v_vehicle.identifier, 'APPROVED'::text;
        return;
    end if;

    if v_current.id is null then
        insert into public.users(id, name, phone, role)
        values (v_uid, v_name, v_phone, 'DRIVER');
    else
        update public.users as u set name = v_name, updated_at = pg_catalog.now() where u.id = v_uid;
    end if;

    -- Reuse an unassigned vehicle row with the same number, otherwise create it.
    select v.* into v_vehicle
    from public.vehicles as v
    where public.safar_normalize_vehicle(v.identifier) = v_vehicle_key
    limit 1
    for update;
    if not found then
        insert into public.vehicles(identifier, route_id, active)
        values (v_identifier, p_route_id, false)
        returning * into v_vehicle;
    else
        update public.vehicles as v set route_id = p_route_id, updated_at = pg_catalog.now()
        where v.id = v_vehicle.id;
    end if;

    insert into public.drivers(user_id, vehicle_id, route_id, verified)
    values (v_uid, v_vehicle.id, p_route_id, true);

    delete from public.driver_applications as a where a.user_id = v_uid;

    return query select v_uid, p_route_id, v_vehicle.identifier, 'APPROVED'::text;
end;
$function$;

revoke all on function public.register_driver(text, text, text, uuid) from public, anon;
grant execute on function public.register_driver(text, text, text, uuid) to authenticated;

-- ---------------------------------------------------------------------------
-- Login helpers
-- ---------------------------------------------------------------------------
-- Moves an existing profile to the current anonymous auth session.
create or replace function public.safar_rebind_profile(p_old_id uuid, p_new_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
begin
    if p_old_id = p_new_id then return; end if;
    if exists (select 1 from public.users as u where u.id = p_new_id) then
        raise exception 'SAFAR_SESSION_HAS_PROFILE';
    end if;
    -- FKs referencing users(id) / drivers(user_id) cascade the new id.
    update public.users as u set id = p_new_id, updated_at = pg_catalog.now() where u.id = p_old_id;
end;
$function$;

revoke all on function public.safar_rebind_profile(uuid, uuid) from public, anon, authenticated;

create or replace function public.login_passenger(
    p_name text,
    p_phone text
)
returns public.users
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_name_key text := public.safar_normalize_name(p_name);
    v_phone text := public.safar_normalize_phone(p_phone);
    v_user public.users;
begin
    if v_uid is null then raise exception 'Authentication is required'; end if;
    if v_name_key = '' then raise exception 'Name is required'; end if;
    if v_phone = '' then raise exception 'Phone is required'; end if;

    select u.* into v_user
    from public.users as u
    where public.safar_normalize_phone(u.phone) = v_phone
    for update;

    if not found then
        if exists (
            select 1 from public.users as u
            where u.role = 'PASSENGER' and public.safar_normalize_name(u.name) = v_name_key
        ) then
            raise exception 'SAFAR_LOGIN_MISMATCH:phone';
        end if;
        raise exception 'SAFAR_LOGIN_NOT_FOUND';
    end if;
    if v_user.role <> 'PASSENGER' then
        raise exception 'SAFAR_LOGIN_ROLE_MISMATCH';
    end if;
    if public.safar_normalize_name(v_user.name) <> v_name_key then
        raise exception 'SAFAR_LOGIN_MISMATCH:name';
    end if;

    perform public.safar_rebind_profile(v_user.id, v_uid);
    return (select u from public.users as u where u.id = v_uid);
end;
$function$;

revoke all on function public.login_passenger(text, text) from public, anon;
grant execute on function public.login_passenger(text, text) to authenticated;

create or replace function public.login_driver(
    p_name text,
    p_phone text,
    p_vehicle_identifier text
)
returns public.users
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_name_key text := public.safar_normalize_name(p_name);
    v_phone text := public.safar_normalize_phone(p_phone);
    v_vehicle_key text := public.safar_normalize_vehicle(p_vehicle_identifier);
    v_user public.users;
    v_vehicle_owner uuid;
    v_driver_vehicle text;
    v_mismatch text[] := array[]::text[];
begin
    if v_uid is null then raise exception 'Authentication is required'; end if;
    if v_name_key = '' then raise exception 'Name is required'; end if;
    if v_phone = '' then raise exception 'Phone is required'; end if;
    if v_vehicle_key = '' then raise exception 'Vehicle identifier is required'; end if;

    select u.* into v_user
    from public.users as u
    where public.safar_normalize_phone(u.phone) = v_phone
    for update;

    if not found then
        -- Phone is unknown. If the vehicle exists, the phone is the wrong field.
        select d.user_id into v_vehicle_owner
        from public.drivers as d
        join public.vehicles as v on v.id = d.vehicle_id
        where public.safar_normalize_vehicle(v.identifier) = v_vehicle_key;
        if v_vehicle_owner is not null then
            if not exists (
                select 1 from public.users as u
                where u.id = v_vehicle_owner and public.safar_normalize_name(u.name) = v_name_key
            ) then
                raise exception 'SAFAR_LOGIN_MISMATCH:name,phone';
            end if;
            raise exception 'SAFAR_LOGIN_MISMATCH:phone';
        end if;
        raise exception 'SAFAR_LOGIN_NOT_FOUND';
    end if;
    if v_user.role <> 'DRIVER' then
        raise exception 'SAFAR_LOGIN_ROLE_MISMATCH';
    end if;

    select v.identifier into v_driver_vehicle
    from public.drivers as d
    join public.vehicles as v on v.id = d.vehicle_id
    where d.user_id = v_user.id;
    if v_driver_vehicle is null then
        -- Legacy pending application without a drivers row.
        select a.vehicle_identifier into v_driver_vehicle
        from public.driver_applications as a where a.user_id = v_user.id;
    end if;

    if public.safar_normalize_name(v_user.name) <> v_name_key then
        v_mismatch := v_mismatch || 'name';
    end if;
    if v_driver_vehicle is null or public.safar_normalize_vehicle(v_driver_vehicle) <> v_vehicle_key then
        v_mismatch := v_mismatch || 'vehicle';
    end if;
    if pg_catalog.array_length(v_mismatch, 1) > 0 then
        raise exception 'SAFAR_LOGIN_MISMATCH:%', pg_catalog.array_to_string(v_mismatch, ',');
    end if;

    perform public.safar_rebind_profile(v_user.id, v_uid);
    update public.drivers as d set verified = true, updated_at = pg_catalog.now()
    where d.user_id = v_uid and not d.verified;
    return (select u from public.users as u where u.id = v_uid);
end;
$function$;

revoke all on function public.login_driver(text, text, text) from public, anon;
grant execute on function public.login_driver(text, text, text) to authenticated;

-- ---------------------------------------------------------------------------
-- Registration is now approved immediately: approve the pending queue.
-- ---------------------------------------------------------------------------
do $$
declare
    r record;
begin
    for r in select a.user_id from public.driver_applications as a loop
        begin
            perform public.approve_driver_application(r.user_id);
        exception when others then
            raise notice 'Could not auto-approve driver application %: %', r.user_id, sqlerrm;
        end;
    end loop;
    update public.drivers set verified = true, updated_at = pg_catalog.now() where not verified;
end $$;
