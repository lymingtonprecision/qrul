# Quick Report Usage Logger (QRUL)

**The problem:** we have many Quick Reports in IFS but no idea which
ones are actively being used or which ones are no longer in use and can
be removed. IFS does not provide any tracking of their use and, due to
being SQL queries, there’s no way to update a table or write to a
message queue to log their use ourselves.

**The solution:** an as‐simple‐as‐possible TCP service to which Quick
Report use events can be sent and which logs them to Apache Kafka.

Whilst you can’t do anything that would modify the database itself you
_can_ connect to external services. It’s a bit of a hack but by sticking
a no‐op function call into the `where` clause of our Quick Reports that
sends a “<user> ran <report>” message out to an external service we can
track the usage of the reports.

So, that’s what this is: a simple TCP server that listens for reports of
people running Quick Reports and logs them to Apache Kafka.

## Running the service

First, ensure you have a Kafka topic to which it can log
events—something like:

    kafka-topics.sh \
      --create \
      --topic quick-report-usage \
      --zookeeper zookeeper:2181 \
      --replication-factor 1 \
      --partitions 1 \
      --config compression.type=lz4 \
      --config cleanup.policy=delete \
      --config retention.ms=-1 \
      --config retention.bytes=-1

… establishes a topic with some sensible defaults.

The following environment variables must be provided:

* `KAFKA_BROKERS`, a comma separated list of Kafka brokers to publish to
* `TOPIC`, the name of the topic to log events to

You may optionally specify the port on which to run the service:

* `QRUL_PORT`, the port to run on, defaults to `13478`

You can then run the service:

* … via Leiningen:

        lein run
* … using a compiled `.jar`:

        java -jar <path/to/qrul-standalone.jar>
* … or as a Docker container:

        docker run \
          -d \
          --name=qrul \
          -e KAFKA_BROKERS=<broker list> \
          -e TOPIC=quick-report-usage \
          lymingtonprecision/qrul

## Logging Quick Report Usage

The protocol for logging use of a Quick Report via the service is very
simple:

1. Connect to the service.
2. Send a line of text in the format `<quick report id>:<user id>:<unix epoch>`
3. You’re done, disconnect.

So, there’s only a single message type and it looks like:

    18:AHARPER:1461055587549
    5:DLARSON:1461063867729
    113:HBERRY:1461064047382
    76:DSNYDER:1461064221936

Each message is terminated by a new line. The time stamp (the last
element) must be provided as the number of milliseconds since
`1970-01-01 00:00:00` (the start of the UNIX epoch.)

A suitable PL/SQL function to send such a message for the current IFS
user given either the Quick Report ID or title would be:

```sql
/* Sends a Quick Report usage message to the QRUL service, indicating
 * the current IFS user has used the specified quick report.
 *
 * Either the quick report ID or title may be specified.
 *
 * Returns 0 on success _or_ failure, so as not to prevent reports from
 * being run if there is a problem with the logging service.
 *
 * Returns -1 if an invalid report was specified.
 */
create or replace function log_qr_usage(
  quick_report_ varchar2
)
return number is
  qr_id_ number;
  user_id_ varchar2(30) := ifsapp.fnd_session_api.get_fnd_user;

  c_ utl_tcp.connection;
  r_ pls_integer;
begin
  if (regexp_like(quick_report_, '^\d$')) then
    qr_id_ := to_number(quick_report_);
  end if;

  select
    qr.quick_report_id
  into
    qr_id_
  from ifsapp.quick_report qr
  where (qr_id_ is not null and qr.quick_report_id = qr_id_)
     or lower(qr.description) = lower(quick_report_)
  ;

  c_ := utl_tcp.open_connection(
    remote_host => 'qrul',
    remote_port => 13478,
    charset => 'UTF8'
  );

  r_ := utl_tcp.write_line(
    c_,
    qr_id_ || ':' || user_id_ || ':' || ifsapp.lpe_timestamp_api.unix_epoch
  );

  utl_tcp.close_connection(c_);

  return 0;
exception
  when NO_DATA_FOUND then
    return 1;
  when OTHERS then
    return 0;
end;
/
```

This can then be used as a no-op clause in the reports as such:

```sql
select
  ip.planner_buyer planner,
  count(ip.part_no) active_parts
from ifsapp.inventory_part ip
where log_qr_usage('parts per planner') = 0
  and ip.part_status = 'A'
  and (
    '&PLANNER' is null
    or lower(ip.planner_buyer) like lower('%&PLANNER%)
  )
```

(Assumes the above is saved as a Quick Report with the title “Parts per
Planner”.)

## Output

The service logs the Quick Report usage events to the
`quick-report-usage` Kafka topic. Each event is logged as a JSON
formatted string (without a key) as follows:

    {"quick_report":18,
     "user_id":"AHARPER",
     "timestamp":"20160419084627.549Z"}

(The timestamps are converted from UNIX epochs to basic ISO8601 strings.)

## License

Copyright © 2016 Lymington Precision Engineers Co. Ltd.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
