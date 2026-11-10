-- Safar: login for returning users, normalized duplicate checks for phone and
-- vehicle numbers, immediate driver approval, and ON UPDATE CASCADE so a login
-- can move an existing profile onto the device's new anonymous session.
--
-- Rules implemented here:
--   * passenger: name + phone; a phone can belong to only one account,
--     the same name with another phone is a different person;
--   * driver: name + phone + vehicle number; phone and vehicle number are
--     unique, the same name with other numbers is a different person;
--   * login requires every field to match and reports which one is wrong;
--   * a new driver is approved immediately (no manual moderation).
--
-- Apply once: Supabase Dashboard -> SQL Editor -> paste this file -> Run.
-- Everything runs in one transaction: on any error nothing is changed.

begin;

-- ---------------------------------------------------------------------------
-- 1. Normalization helpers
-- ---------------------------------------------------------------------------

-- Digits only. A leading international "00" is dropped and a 9-digit local
-- Tajik number gets the 992 country code, so "+992 93 123-45-67",
-- "992931234567" and "93 123 45 67" are the same phone.
create or replace function public.safar_normalize_phone(p_phone text)
returns text
language sql
immutable
set search_path = ''
as $function$
    select case
        when d.digits = '' then null
        when pg_catalog.length(d.digits) = 9 then '992' || d.digits
        else d.digits
    end
    from (
        select pg_catalog.regexp_replace(
            pg_catalog.regexp_replace(coalesce(p_phone, ''), '[^0-9]', '', 'g'),
            '^00',
            ''
        ) as digits
    ) as d
$function$;

-- Upper-case without spaces, dots, dashes or underscores. Cyrillic letters that
-- look like Latin ones on plates are mapped to Latin: "01 АА 1234" = "01AA1234".
create or replace function public.safar_normalize_vehicle(p_identifier text)
returns text
language sql
immutable
set search_path = ''
as $function$
    select nullif(
        pg_catalog.upper(
            pg_catalog.translate(
                pg_catalog.regexp_replace(coalesce(p_identifier, ''), '[[:space:]._-]+', '', 'g'),
                'АВЕКМНОРСТУХавекмнорстух',
                'ABEKMHOPCTYXabekmhopctyx'
            )
        ),
        ''
    )
$function$;

-- Display form of a name: whitespace runs collapsed to one space, trimmed.
create or replace function public.safar_clean_name(p_name text)
returns text
language sql
immutable
set search_path = ''
as $function$
    select pg_catalog.btrim(
        pg_catalog.regexp_replace(coalesce(p_name, ''), '[[:space:]]+', ' ', 'g')
    )
$function$;

-- Comparison form of a name: clean and case-insensitive.
create or replace function public.safar_normalize_name(p_name text)
returns text
language sql
immutable
set search_path = ''
as $function$
    select pg_catalog.lower(public.safar_clean_name(p_name))
$function$;

revoke all on function public.safar_normalize_phone(text) from public, anon, authenticated;
revoke all on function public.safar_normalize_vehicle(text) from public, anon, authenticated;
revoke all on function public.safar_clean_name(text) from public, anon, authenticated;
revoke all on function public.safar_normalize_name(text) from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- 2. Let a profile id move to a new auth user: every foreign key that points
--    at users(id) or drivers(user_id) follows the change (ON UPDATE CASCADE),
--    so trips, statistics and favorites stay with the account after login.
-- ---------------------------------------------------------------------------
do $do$
declare
    r record;
    v_definition text;
begin
    for r in
        select c.conname,
               c.conrelid::pg_catalog.regclass as table_name,
               pg_catalog.pg_get_constraintdef(c.oid) as definition
        from pg_catalog.pg_constraint as c
        where c.contype = 'f'
          and c.confrelid in ('public.users'::pg_catalog.regclass, 'public.drivers'::pg_catalog.regclass)
          and c.confupdtype <> 'c'
    loop
        v_definition := pg_catalog.regexp_replace(
            r.definition,
            ' ON UPDATE (NO ACTION|RESTRICT|SET NULL|SET DEFAULT)',
            ''
        );
        v_definition := pg_catalog.regexp_replace(
            v_definition,
            '(REFERENCES [^(]+\([^)]*\))',
            '\1 ON UPDATE CASCADE'
        );
        execute pg_catalog.format('alter table %s drop constraint %I', r.table_name, r.conname);
        execute pg_catalog.format('alter table %s add constraint %I %s', r.table_name, r.conname, v_definition);
    end loop;
end;
$do$;

-- ---------------------------------------------------------------------------
-- 3. Registration
-- ---------------------------------------------------------------------------

drop function if exists public.register_passenger(text, text);
create function public.register_passenger(
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
    v_name text := public.safar_clean_name(p_name);
    v_phone text := public.safar_normalize_phone(p_phone);
    v_existing public.users;
    v_user public.users;
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;
    if v_name = '' then
        raise exception 'SAFAR_NAME_REQUIRED';
    end if;
    if v_phone is null or pg_catalog.length(v_phone) not between 9 and 15 then
        raise exception 'SAFAR_INVALID_PHONE';
    end if;

    -- Serializes sign-ups and logins for the same phone number.
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('safar-phone:' || v_phone, 0));

    select u.* into v_existing from public.users as u where u.id = v_uid for update;
    if found and v_existing.role <> 'PASSENGER' then
        raise exception 'User role is already DRIVER';
    end if;

    if exists (
        select 1
        from public.users as u
        where u.id <> v_uid
          and public.safar_normalize_phone(u.phone) = v_phone
    ) then
        raise exception 'SAFAR_PHONE_TAKEN';
    end if;

    insert into public.users (id, name, phone, role)
    values (v_uid, v_name, '+' || v_phone, 'PASSENGER')
    on conflict (id) do update
        set name = excluded.name,
            phone = excluded.phone,
            updated_at = pg_catalog.now()
    returning * into v_user;

    return v_user;
end;
$function$;

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
#variable_conflict use_column
declare
    v_uid uuid := auth.uid();
    v_name text := public.safar_clean_name(p_name);
    v_phone text := public.safar_normalize_phone(p_phone);
    v_vehicle_key text := public.safar_normalize_vehicle(p_vehicle_identifier);
    v_role public.user_role;
    v_driver public.drivers;
    v_vehicle public.vehicles;
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;
    if v_name = '' then
        raise exception 'SAFAR_NAME_REQUIRED';
    end if;
    if v_phone is null or pg_catalog.length(v_phone) not between 9 and 15 then
        raise exception 'SAFAR_INVALID_PHONE';
    end if;
    if v_vehicle_key is null then
        raise exception 'SAFAR_VEHICLE_REQUIRED';
    end if;
    if p_route_id is null or not exists (select 1 from public.routes as r where r.id = p_route_id) then
        raise exception 'Route not found';
    end if;

    -- Lock order: user, phone, vehicle. Concurrent sign-ups with the same phone
    -- or vehicle number are serialized, so the duplicate checks cannot race.
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_uid::text, 1));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('safar-phone:' || v_phone, 0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('safar-vehicle:' || v_vehicle_key, 0));

    select u.role into v_role from public.users as u where u.id = v_uid for update;
    if found and v_role <> 'DRIVER' then
        raise exception 'User role is already PASSENGER';
    end if;

    if exists (
        select 1
        from public.users as u
        where u.id <> v_uid
          and public.safar_normalize_phone(u.phone) = v_phone
    ) then
        raise exception 'SAFAR_PHONE_TAKEN';
    end if;

    if exists (
        select 1
        from public.drivers as d
        join public.vehicles as v on v.id = d.vehicle_id
        where d.user_id <> v_uid
          and public.safar_normalize_vehicle(v.identifier) = v_vehicle_key
    ) or exists (
        select 1
        from public.driver_applications as a
        where a.user_id <> v_uid
          and public.safar_normalize_vehicle(a.vehicle_identifier) = v_vehicle_key
    ) then
        raise exception 'SAFAR_VEHICLE_TAKEN';
    end if;

    -- Repeated registration on the same session (for example after a timeout
    -- on the phone) is idempotent and also approves an old pending profile.
    select d.* into v_driver from public.drivers as d where d.user_id = v_uid for update;
    if found then
        select v.* into v_vehicle from public.vehicles as v where v.id = v_driver.vehicle_id for update;
        if public.safar_normalize_vehicle(v_vehicle.identifier) is distinct from v_vehicle_key
           or (v_driver.route_id is not null and v_driver.route_id <> p_route_id) then
            raise exception 'Driver profile already exists with different vehicle or route';
        end if;

        update public.users as u
        set name = v_name,
            phone = '+' || v_phone,
            updated_at = pg_catalog.now()
        where u.id = v_uid;

        update public.drivers as d
        set verified = true,
            route_id = coalesce(d.route_id, p_route_id),
            updated_at = pg_catalog.now()
        where d.user_id = v_uid
        returning d.* into v_driver;

        delete from public.driver_applications as a where a.user_id = v_uid;

        return query select v_uid, v_driver.route_id, v_vehicle.identifier, 'APPROVED'::text;
        return;
    end if;

    insert into public.users (id, name, phone, role)
    values (v_uid, v_name, '+' || v_phone, 'DRIVER')
    on conflict (id) do update
        set name = excluded.name,
            phone = excluded.phone,
            updated_at = pg_catalog.now();

    -- Reuse a fleet vehicle row with the same normalized number that has no
    -- driver (the duplicate check above guarantees nobody else drives it).
    select v.* into v_vehicle
    from public.vehicles as v
    where public.safar_normalize_vehicle(v.identifier) = v_vehicle_key
    order by v.created_at, v.id
    limit 1
    for update;

    if found then
        update public.vehicles as v
        set route_id = p_route_id,
            active = false,
            updated_at = pg_catalog.now()
        where v.id = v_vehicle.id
        returning v.* into v_vehicle;
    else
        insert into public.vehicles (identifier, route_id, active)
        values (v_vehicle_key, p_route_id, false)
        returning * into v_vehicle;
    end if;

    delete from public.driver_applications as a where a.user_id = v_uid;

    insert into public.drivers (user_id, vehicle_id, route_id, verified)
    values (v_uid, v_vehicle.id, p_route_id, true)
    returning * into v_driver;

    return query select v_uid, p_route_id, v_vehicle.identifier, 'APPROVED'::text;
end;
$function$;

-- Profile editing uses the same phone rules as registration.
drop function if exists public.update_current_user(text, text);
create function public.update_current_user(
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
    v_name text := public.safar_clean_name(p_name);
    v_phone text := public.safar_normalize_phone(p_phone);
    v_user public.users;
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;
    if v_name = '' then
        raise exception 'SAFAR_NAME_REQUIRED';
    end if;
    if v_phone is null or pg_catalog.length(v_phone) not between 9 and 15 then
        raise exception 'SAFAR_INVALID_PHONE';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('safar-phone:' || v_phone, 0));

    if exists (
        select 1
        from public.users as u
        where u.id <> v_uid
          and public.safar_normalize_phone(u.phone) = v_phone
    ) then
        raise exception 'SAFAR_PHONE_TAKEN';
    end if;

    update public.users as u
    set name = v_name,
        phone = '+' || v_phone,
        updated_at = pg_catalog.now()
    where u.id = v_uid
    returning u.* into v_user;

    if not found then
        raise exception 'Profile not registered';
    end if;
    return v_user;
end;
$function$;

-- ---------------------------------------------------------------------------
-- 4. Login
-- ---------------------------------------------------------------------------

-- Internal: moves an existing profile onto the caller's auth user. Never
-- exposed to clients, otherwise anyone could take over any profile id.
create or replace function public.safar_bind_profile_to_session(p_profile_id uuid, p_uid uuid)
returns public.users
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_user public.users;
begin
    if p_profile_id = p_uid then
        select u.* into v_user from public.users as u where u.id = p_uid;
        return v_user;
    end if;

    -- Never overwrite a profile that already belongs to this auth user.
    if exists (select 1 from public.users as u where u.id = p_uid) then
        raise exception 'SAFAR_SESSION_HAS_PROFILE';
    end if;

    -- A live passenger marker belongs to the old device and must not move.
    delete from public.passenger_current_state as s where s.passenger_id = p_profile_id;

    update public.users as u
    set id = p_uid,
        updated_at = pg_catalog.now()
    where u.id = p_profile_id
    returning u.* into v_user;

    if not found then
        raise exception 'SAFAR_LOGIN_NOT_FOUND';
    end if;
    return v_user;
end;
$function$;
revoke all on function public.safar_bind_profile_to_session(uuid, uuid) from public, anon, authenticated;

create or replace function public.login_passenger(p_name text, p_phone text)
returns public.users
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_name_key text := public.safar_normalize_name(p_name);
    v_phone text := public.safar_normalize_phone(p_phone);
    v_profile public.users;
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;
    if v_name_key = '' then
        raise exception 'SAFAR_NAME_REQUIRED';
    end if;
    if v_phone is null then
        raise exception 'SAFAR_INVALID_PHONE';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('safar-phone:' || v_phone, 0));

    select u.* into v_profile
    from public.users as u
    where public.safar_normalize_phone(u.phone) = v_phone
    order by (u.role = 'PASSENGER') desc,
             (public.safar_normalize_name(u.name) = v_name_key) desc,
             u.created_at
    limit 1
    for update;

    if not found then
        raise exception 'SAFAR_LOGIN_NOT_FOUND';
    end if;
    if v_profile.role <> 'PASSENGER' then
        raise exception 'SAFAR_LOGIN_ROLE_DRIVER';
    end if;
    if public.safar_normalize_name(v_profile.name) <> v_name_key then
        raise exception 'SAFAR_LOGIN_MISMATCH:name';
    end if;

    return public.safar_bind_profile_to_session(v_profile.id, v_uid);
end;
$function$;

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
    v_profile public.users;
    v_profile_vehicle text;
    v_wrong text[] := '{}';
begin
    if v_uid is null then
        raise exception 'Authentication is required';
    end if;
    if v_name_key = '' then
        raise exception 'SAFAR_NAME_REQUIRED';
    end if;
    if v_phone is null then
        raise exception 'SAFAR_INVALID_PHONE';
    end if;
    if v_vehicle_key is null then
        raise exception 'SAFAR_VEHICLE_REQUIRED';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('safar-phone:' || v_phone, 0));

    select u.* into v_profile
    from public.users as u
    where public.safar_normalize_phone(u.phone) = v_phone
    order by (u.role = 'DRIVER') desc,
             (public.safar_normalize_name(u.name) = v_name_key) desc,
             u.created_at
    limit 1
    for update;

    if found and v_profile.role <> 'DRIVER' then
        raise exception 'SAFAR_LOGIN_ROLE_PASSENGER';
    end if;

    if not found then
        -- Unknown phone: look the driver up by vehicle number so the app can say
        -- that the phone (not the whole account) is wrong.
        select u.* into v_profile
        from public.drivers as d
        join public.vehicles as v on v.id = d.vehicle_id
        join public.users as u on u.id = d.user_id
        where public.safar_normalize_vehicle(v.identifier) = v_vehicle_key
        order by u.created_at
        limit 1
        for update of u;

        if not found then
            raise exception 'SAFAR_LOGIN_NOT_FOUND';
        end if;
        v_wrong := pg_catalog.array_append(v_wrong, 'phone');
    end if;

    if public.safar_normalize_name(v_profile.name) <> v_name_key then
        v_wrong := pg_catalog.array_append(v_wrong, 'name');
    end if;

    select v.identifier into v_profile_vehicle
    from public.drivers as d
    join public.vehicles as v on v.id = d.vehicle_id
    where d.user_id = v_profile.id;
    if not found then
        select a.vehicle_identifier into v_profile_vehicle
        from public.driver_applications as a
        where a.user_id = v_profile.id;
    end if;
    if v_profile_vehicle is null
       or public.safar_normalize_vehicle(v_profile_vehicle) is distinct from v_vehicle_key then
        v_wrong := pg_catalog.array_append(v_wrong, 'vehicle');
    end if;

    if pg_catalog.cardinality(v_wrong) > 0 then
        raise exception 'SAFAR_LOGIN_MISMATCH:%', pg_catalog.array_to_string(v_wrong, ',');
    end if;

    return public.safar_bind_profile_to_session(v_profile.id, v_uid);
end;
$function$;

-- ---------------------------------------------------------------------------
-- 5. Drivers are approved right away now: approve applications that are still
--    waiting. Conflicting ones (for example a vehicle number that already
--    belongs to another driver) stay pending and are listed as NOTICEs.
-- ---------------------------------------------------------------------------
do $do$
declare
    r record;
begin
    if pg_catalog.to_regclass('public.driver_applications') is null then
        return;
    end if;
    for r in select a.user_id from public.driver_applications as a loop
        begin
            perform public.approve_driver_application(r.user_id);
        exception when others then
            raise notice 'Safar: driver application % was left pending: %', r.user_id, sqlerrm;
        end;
    end loop;
end;
$do$;

-- ---------------------------------------------------------------------------
-- 6. Grants: only signed-in (including anonymous) app sessions may call these.
-- ---------------------------------------------------------------------------
revoke all on function public.register_passenger(text, text) from public, anon;
grant execute on function public.register_passenger(text, text) to authenticated;
revoke all on function public.register_driver(text, text, text, uuid) from public, anon;
grant execute on function public.register_driver(text, text, text, uuid) to authenticated;
revoke all on function public.update_current_user(text, text) from public, anon;
grant execute on function public.update_current_user(text, text) to authenticated;
revoke all on function public.login_passenger(text, text) from public, anon;
grant execute on function public.login_passenger(text, text) to authenticated;
revoke all on function public.login_driver(text, text, text) from public, anon;
grant execute on function public.login_driver(text, text, text) to authenticated;

notify pgrst, 'reload schema';

commit;
