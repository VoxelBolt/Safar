-- Production hardening for the MVP workload.
-- Keep the existing current-state GPS architecture; add the missing FK index
-- and pin the trigger helper search_path for predictable, safer execution.

create index if not exists work_sessions_vehicle_idx
    on public.work_sessions (vehicle_id);

alter function public.set_updated_at()
    set search_path = public, pg_temp;
