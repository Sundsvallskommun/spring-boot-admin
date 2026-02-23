create table if not exists event
(
    id          bigint auto_increment primary key,
    instance_id varchar(255) not null,
    event_type  varchar(50)  not null,
    version     bigint       not null,
    timestamp   timestamp(3) not null,
    event_json  longtext     not null,
    created_at  timestamp default current_timestamp,
    index idx_timestamp (timestamp),
    index idx_instance_timestamp (instance_id, timestamp),
    constraint uk_instance_version unique (instance_id, version)
);

create table if not exists shedlock
(
    name       varchar(64)  not null,
    lock_until timestamp(3) not null default current_timestamp(3) on update current_timestamp(3),
    locked_at  timestamp(3) not null default current_timestamp(3),
    locked_by  varchar(255) not null,
    primary key (name)
);