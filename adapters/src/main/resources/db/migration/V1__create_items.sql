create table items
(
    id         uuid primary key,
    name       text        not null constraint items_name_key unique,
    created_at timestamptz not null
);
