create table schedule_transfer (
       id serial primary key,
       user_id integer,
       amount decimal(10,2),
       state varchar(200) default 'PENDING',
       created timestamp default now()
       )
