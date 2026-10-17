CREATE INDEX queue_expiry ON queue_entry(performance_id,state,expires_at);
CREATE INDEX hold_expiry ON seat_hold(performance_id,state,expires_at);
CREATE INDEX seat_hold_inventory ON performance_seat(hold_id);
CREATE INDEX auth_refresh_expiry ON auth_session(refresh_expires);
