insert into service_types (id, name, duration_minutes, required_skill) values
    (1, 'Battery replacement',  60, 'BATTERY_FIT'),
    (2, 'Jump-start',           30, 'JUMPSTART'),
    (3, 'Battery health check', 30, 'DIAGNOSTICS');

insert into zones (id, name) values
    (1, 'Bengaluru South'),
    (2, 'Bengaluru North');

insert into zone_pincodes (zone_id, pincode) values
    (1, '560001'), (1, '560002'), (1, '560003'),
    (2, '560101'), (2, '560102'), (2, '560103');

insert into technicians (id, name, zone_id, shift_start, shift_end) values
    (1, 'Asha',   1, '08:00:00', '20:00:00'),
    (2, 'Bharat', 1, '08:00:00', '20:00:00'),
    (3, 'Chitra', 1, '08:00:00', '20:00:00'),
    (4, 'Dev',    2, '08:00:00', '20:00:00'),
    (5, 'Esha',   2, '08:00:00', '20:00:00'),
    (6, 'Farid',  2, '08:00:00', '20:00:00');

insert into technician_skills (technician_id, skill) values
    (1, 'BATTERY_FIT'), (1, 'JUMPSTART'), (1, 'DIAGNOSTICS'),
    (2, 'JUMPSTART'),   (2, 'DIAGNOSTICS'),
    (3, 'BATTERY_FIT'),
    (4, 'BATTERY_FIT'), (4, 'JUMPSTART'), (4, 'DIAGNOSTICS'),
    (5, 'JUMPSTART'),
    (6, 'BATTERY_FIT'), (6, 'DIAGNOSTICS');
