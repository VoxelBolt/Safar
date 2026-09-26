-- Stage 06.1: one current location per vehicle + route-scoped Supabase Broadcast.
-- public.vehicle_locations remains only as an unused legacy table; the live path
-- below never inserts GPS points into it.

drop function if exists public.submit_vehicle_location(double precision, double precision, timestamptz);

create table if not exists public.vehicle_current_state (
    vehicle_id uuid primary key references public.vehicles(id) on delete cascade,
    route_id uuid not null references public.routes(id) on delete cascade,
    latitude double precision not null,
    longitude double precision not null,
    recorded_at timestamptz not null,
    updated_at timestamptz not null default now(),
    constraint vehicle_current_state_latitude_range check (latitude between -90 and 90),
    constraint vehicle_current_state_longitude_range check (longitude between -180 and 180)
);

create index if not exists vehicle_current_state_route_idx
    on public.vehicle_current_state (route_id);

create index if not exists vehicle_current_state_recorded_at_idx
    on public.vehicle_current_state (recorded_at desc);

alter table public.vehicle_current_state enable row level security;

revoke all on public.vehicle_current_state from anon;
revoke all on public.vehicle_current_state from authenticated;
grant select on public.vehicle_current_state to authenticated;

drop policy if exists vehicle_current_state_read_authenticated
    on public.vehicle_current_state;

create policy vehicle_current_state_read_authenticated
on public.vehicle_current_state
for select
to authenticated
using (true);

drop view if exists public.vehicle_current_locations;

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
        and s.recorded_at >= now() - interval '25 seconds'
    ) as active,
    s.latitude,
    s.longitude,
    s.recorded_at
from public.vehicles v
left join public.vehicle_current_state s
    on s.vehicle_id = v.id;

grant select on public.vehicle_current_locations to authenticated;

create function public.submit_vehicle_location(
    p_latitude double precision,
    p_longitude double precision,
    p_recorded_at timestamptz
)
returns public.vehicle_current_state
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    v_driver public.drivers;
    v_state public.vehicle_current_state;
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;

    if p_latitude < -90 or p_latitude > 90
       or p_longitude < -180 or p_longitude > 180 then
        raise exception 'Invalid coordinates';
    end if;

    select *
    into v_driver
    from public.drivers
    where user_id = auth.uid();

    if not found then
        raise exception 'Driver profile not found';
    end if;

    if v_driver.route_id is null then
        raise exception 'Driver route is not assigned yet';
    end if;

    if not v_driver.verified then
        raise exception 'Driver is not verified';
    end if;

    update public.vehicles
    set active = true,
        route_id = v_driver.route_id,
        updated_at = now()
    where id = v_driver.vehicle_id;

    insert into public.vehicle_current_state(
        vehicle_id,
        route_id,
        latitude,
        longitude,
        recorded_at,
        updated_at
    )
    values (
        v_driver.vehicle_id,
        v_driver.route_id,
        p_latitude,
        p_longitude,
        p_recorded_at,
        now()
    )
    on conflict (vehicle_id) do update
    set route_id = excluded.route_id,
        latitude = excluded.latitude,
        longitude = excluded.longitude,
        recorded_at = excluded.recorded_at,
        updated_at = now()
    where excluded.recorded_at >= public.vehicle_current_state.recorded_at
    returning * into v_state;

    if v_state.vehicle_id is null then
        select *
        into v_state
        from public.vehicle_current_state
        where vehicle_id = v_driver.vehicle_id;
    end if;

    return v_state;
end;
$function$;

revoke all on function public.submit_vehicle_location(double precision, double precision, timestamptz)
    from public, anon;

grant execute on function public.submit_vehicle_location(double precision, double precision, timestamptz)
    to authenticated;

create or replace function public.broadcast_vehicle_location()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    if tg_op = 'DELETE' then
        perform realtime.send(
            jsonb_build_object(
                'vehicle_id', old.vehicle_id,
                'route_id', old.route_id
            ),
            'vehicle_location_removed',
            'safar:vehicle-location:route:' || old.route_id::text,
            true
        );
        return null;
    end if;

    if tg_op = 'UPDATE'
       and old.route_id is distinct from new.route_id then
        perform realtime.send(
            jsonb_build_object(
                'vehicle_id', old.vehicle_id,
                'route_id', old.route_id
            ),
            'vehicle_location_removed',
            'safar:vehicle-location:route:' || old.route_id::text,
            true
        );
    end if;

    perform realtime.send(
        jsonb_build_object(
            'vehicle_id', new.vehicle_id,
            'route_id', new.route_id,
            'latitude', new.latitude,
            'longitude', new.longitude,
            'recorded_at', new.recorded_at
        ),
        'vehicle_location',
        'safar:vehicle-location:route:' || new.route_id::text,
        true
    );

    return null;
end;
$function$;

drop trigger if exists vehicle_current_state_broadcast
    on public.vehicle_current_state;

create trigger vehicle_current_state_broadcast
after insert or update or delete
on public.vehicle_current_state
for each row
execute function public.broadcast_vehicle_location();

drop policy if exists safar_vehicle_location_broadcast_read
    on realtime.messages;

create policy safar_vehicle_location_broadcast_read
on realtime.messages
for select
to authenticated
using (
    extension = 'broadcast'
    and topic like 'safar:vehicle-location:route:%'
);

-- Route geometry belongs to the database, not to a fake transport data source.
update public.routes
set path = '[
  {"latitude":40.279267,"longitude":69.622175},
  {"latitude":40.278050,"longitude":69.623768},
  {"latitude":40.277269,"longitude":69.625775},
  {"latitude":40.277000,"longitude":69.628000},
  {"latitude":40.277269,"longitude":69.630225},
  {"latitude":40.278050,"longitude":69.632232},
  {"latitude":40.279267,"longitude":69.633825},
  {"latitude":40.280800,"longitude":69.634848},
  {"latitude":40.282500,"longitude":69.635200},
  {"latitude":40.284200,"longitude":69.634848},
  {"latitude":40.285733,"longitude":69.633825},
  {"latitude":40.286950,"longitude":69.632232},
  {"latitude":40.287731,"longitude":69.630225},
  {"latitude":40.288000,"longitude":69.628000},
  {"latitude":40.287731,"longitude":69.625775},
  {"latitude":40.286950,"longitude":69.623768},
  {"latitude":40.285733,"longitude":69.622175},
  {"latitude":40.284200,"longitude":69.621152},
  {"latitude":40.282500,"longitude":69.620800},
  {"latitude":40.280800,"longitude":69.621152},
  {"latitude":40.279267,"longitude":69.622175}
]'::jsonb
where number = '3';

update public.routes
set path = '[
  {"latitude":40.27680,"longitude":69.60690},
  {"latitude":40.27920,"longitude":69.61030},
  {"latitude":40.28200,"longitude":69.61140},
  {"latitude":40.28510,"longitude":69.61060},
  {"latitude":40.28810,"longitude":69.60840},
  {"latitude":40.29060,"longitude":69.61000},
  {"latitude":40.29240,"longitude":69.61340},
  {"latitude":40.29280,"longitude":69.61720},
  {"latitude":40.29120,"longitude":69.62030},
  {"latitude":40.28850,"longitude":69.62080},
  {"latitude":40.28580,"longitude":69.61900},
  {"latitude":40.28300,"longitude":69.61600},
  {"latitude":40.28040,"longitude":69.61440},
  {"latitude":40.27810,"longitude":69.61120},
  {"latitude":40.27680,"longitude":69.60690}
]'::jsonb
where number = '8';

update public.routes
set path = '[
  {"latitude":40.285360,"longitude":69.612710},
  {"latitude":40.284386,"longitude":69.610719},
  {"latitude":40.283160,"longitude":69.609440},
  {"latitude":40.281800,"longitude":69.609000},
  {"latitude":40.280440,"longitude":69.609440},
  {"latitude":40.279214,"longitude":69.610719},
  {"latitude":40.278240,"longitude":69.612710},
  {"latitude":40.277615,"longitude":69.615219},
  {"latitude":40.277400,"longitude":69.618000},
  {"latitude":40.277615,"longitude":69.620781},
  {"latitude":40.278240,"longitude":69.623290},
  {"latitude":40.279214,"longitude":69.625281},
  {"latitude":40.280440,"longitude":69.626560},
  {"latitude":40.281800,"longitude":69.627000},
  {"latitude":40.283160,"longitude":69.626560},
  {"latitude":40.284386,"longitude":69.625281},
  {"latitude":40.285360,"longitude":69.623290},
  {"latitude":40.285985,"longitude":69.620781},
  {"latitude":40.286200,"longitude":69.618000},
  {"latitude":40.285985,"longitude":69.615219},
  {"latitude":40.285360,"longitude":69.612710}
]'::jsonb
where number = '15';
