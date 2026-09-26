-- This function is invoked only by the current-state trigger; it must not be
-- callable as an RPC by client roles.
revoke all on function public.broadcast_vehicle_location()
    from public, anon, authenticated;
