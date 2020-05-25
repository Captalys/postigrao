create table balance (
       id serial primary key,
       user_id integer,
       amount decimal(10,2),
       created timestamp default now()
       )
