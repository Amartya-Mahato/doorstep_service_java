create table service_types (
    id               bigint primary key,
    name             varchar(100) not null,
    duration_minutes int          not null,
    required_skill   varchar(50)  not null
);

create table zones (
    id   bigint primary key,
    name varchar(100) not null
);

create table zone_pincodes (
    zone_id bigint      not null references zones (id),
    pincode varchar(10) not null,
    primary key (zone_id, pincode),
    unique (pincode) -- a pincode belongs to exactly one zone
);

create table technicians (
    id          bigint primary key,
    name        varchar(100) not null,
    zone_id     bigint       not null references zones (id),
    shift_start time         not null,
    shift_end   time         not null
);

create table technician_skills (
    technician_id bigint      not null references technicians (id),
    skill         varchar(50) not null,
    primary key (technician_id, skill)
);

create table bookings (
    id              uuid primary key,
    customer_phone  varchar(20) not null,
    service_id      bigint      not null references service_types (id),
    pincode         varchar(10) not null,
    start_time      timestamp   not null,
    end_time        timestamp   not null,
    technician_id   bigint      not null references technicians (id),
    status          varchar(20) not null,
    idempotency_key varchar(255),
    created_at      timestamp   not null,
    unique (idempotency_key)
);

create index idx_bookings_tech_start on bookings (technician_id, start_time);
create index idx_bookings_phone on bookings (customer_phone, start_time);
