FROM develar/java:8u45
MAINTAINER Adam Harper <docker@adam-harper.com>

COPY target/qrul-standalone.jar /qrul.jar

ENV JVM_FLAGS -server \
  -Xms128M \
  -Xmx512M \
  -XX:+UseConcMarkSweepGC \
  -XX:+CMSParallelRemarkEnabled \
  -XX:+CMSScavengeBeforeRemark \
  -XX:+ScavengeBeforeFullGC \
  -Xloggc:"./log/gc.log" \
  -XX:+PrintGCDateStamps \
  -XX:+PrintGCDetails \
  -verbose:gc \
  -XX:+UseGCLogFileRotation \
  -XX:NumberOfGCLogFiles=10 \
  -XX:GCLogFileSize=100M \
  -Dcom.sun.management.jmxremote.port=7778 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Dsun.net.inetaddr.ttl=60 \
  -Duser.timezone=UTC

EXPOSE 7778 13478

CMD ["-jar", "/qrul.jar"]